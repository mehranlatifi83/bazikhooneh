import asyncio

from channels.db import database_sync_to_async
from channels.generic.websocket import AsyncJsonWebsocketConsumer
from django.db import transaction
from django.conf import settings
from django.utils import timezone

from .engine import GameError
from .models import Match, Player, Room, token_hash
from .models import LudoRoom, LudoSeat, LudoMatch
from .ludo_engine import roll as ludo_roll, move as ludo_move, run_bots
from .websocket_security import (
    bearer_token,
    claim_action,
    connection_allowed,
    message_allowed,
    release_action,
    has_active_presence,
    update_presence,
)


class RoomConsumer(AsyncJsonWebsocketConsumer):
    async def connect(self):
        self.room_code = self.scope["url_route"]["kwargs"]["code"].upper()
        self.token = self._auth_token()
        if not self.token or not await connection_allowed(self.scope, self.token):
            await self.close(code=4429 if self.token else 4401)
            return
        self.player = await self._authenticate()
        if self.player is None:
            await self.close(code=4401)
            return

        self.group_name = f"room_{self.room_code}"
        await self.channel_layer.group_add(self.group_name, self.channel_name)
        await self.accept()
        await self._mark_seen()
        connection_count = await update_presence(
            "tic", self.player.id, self.channel_name
        )
        await self.send_json({"type": "state", "game": await self._room_state()})
        if connection_count == 1:
            await self.channel_layer.group_send(
                self.group_name,
                {
                    "type": "player.presence",
                    "symbol": self.player.symbol,
                    "connected": True,
                },
            )

    async def disconnect(self, close_code):
        if hasattr(self, "group_name"):
            disconnected_at = await self._mark_seen()
            remaining = await update_presence(
                "tic", self.player.id, self.channel_name, connected=False
            )
            if remaining == 0:
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "player.presence",
                        "symbol": self.player.symbol,
                        "connected": False,
                    },
                )
            await self.channel_layer.group_discard(self.group_name, self.channel_name)
            if remaining == 0:
                asyncio.create_task(self._close_after_grace(disconnected_at))

    async def _close_after_grace(self, disconnected_at):
        await asyncio.sleep(settings.ONLINE_RECONNECT_GRACE_SECONDS)
        if await has_active_presence("tic", self.player.id):
            return
        state = await self._forfeit_if_still_away(disconnected_at)
        if state:
            await self.channel_layer.group_send(
                self.group_name, {"type": "game.state", "game": state}
            )

    async def receive_json(self, content, **kwargs):
        if not await message_allowed(self.token, 20):
            await self.send_json({"type": "error", "error": "rate_limited"})
            return
        if content.get("type") == "ping":
            await update_presence("tic", self.player.id, self.channel_name)
            await self._mark_seen()
            await self.send_json(
                {"type": "pong", "server_time": timezone.now().isoformat()}
            )
            return
        action_id = content.get("action_id")
        if action_id and not await claim_action(self.token, str(action_id)[:80]):
            return
        if content.get("type") == "rematch":
            try:
                state = await self._request_rematch()
            except GameError as error:
                await release_action(self.token, action_id)
                await self.send_json({"type": "error", "error": str(error)})
                return
            await self.channel_layer.group_send(
                self.group_name, {"type": "game.state", "game": state}
            )
            return
        if content.get("type") == "leave":
            state = await self._leave_room()
            await self.channel_layer.group_send(
                self.group_name, {"type": "game.state", "game": state}
            )
            return
        if content.get("type") != "action" or not isinstance(
            content.get("action"), dict
        ):
            await release_action(self.token, action_id)
            await self.send_json({"type": "error", "error": "invalid_message"})
            return
        try:
            state = await self._apply_action(content["action"])
        except GameError as error:
            await release_action(self.token, action_id)
            await self.send_json({"type": "error", "error": str(error)})
            return
        await self.channel_layer.group_send(
            self.group_name,
            {
                "type": "game.state",
                "game": state,
                "action_id": content.get("action_id"),
            },
        )
        community = await self._record_community_event(content["action"], state)
        if community:
            await self.channel_layer.group_send(
                community["group"],
                {"type": "community.event", "message": community["message"]},
            )

    async def game_state(self, event):
        await self.send_json(
            {
                "type": "state",
                "game": event["game"],
                "action_id": event.get("action_id"),
            }
        )

    async def player_presence(self, event):
        await self.send_json(
            {
                "type": "presence",
                "symbol": event["symbol"],
                "connected": event["connected"],
            }
        )

    def _auth_token(self):
        return bearer_token(self.scope)

    @database_sync_to_async
    def _authenticate(self):
        try:
            return Player.objects.select_related("room").get(
                room__code=self.room_code,
                reconnect_token_hash=token_hash(self.token),
                is_active=True,
            )
        except Player.DoesNotExist:
            return None

    @database_sync_to_async
    def _room_state(self):
        return Room.objects.get(code=self.room_code).public_state()

    @database_sync_to_async
    def _mark_seen(self):
        now = timezone.now()
        Player.objects.filter(id=self.player.id).update(last_seen_at=now)
        return now

    @database_sync_to_async
    def _forfeit_if_still_away(self, disconnected_at):
        with transaction.atomic():
            player = (
                Player.objects.select_for_update()
                .select_related("room")
                .get(id=self.player.id)
            )
            room = Room.objects.select_for_update().get(id=player.room_id)
            if (
                not player.is_active
                or player.last_seen_at > disconnected_at
                or room.state in (Room.State.CLOSED, Room.State.FINISHED)
            ):
                return None
            player.is_active = False
            player.save(update_fields=("is_active", "last_seen_at"))
            room.close_for_player(player.symbol)
            room.save()
            match = (
                room.matches.select_for_update()
                .filter(finished_at__isnull=True)
                .first()
            )
            if match:
                winner = match.o_account if player.symbol == "X" else match.x_account
                match.finish("disconnect_timeout", winner)
                from accounts.models import AccountNotification

                AccountNotification.objects.create(
                    account=winner,
                    kind="game_event",
                    title="Match completed",
                    body="Your opponent did not reconnect in time.",
                    data={"room_code": room.code, "game_key": room.game_key},
                )
            return room.public_state()

    @database_sync_to_async
    def _apply_action(self, action):
        with transaction.atomic():
            room = Room.objects.select_for_update().get(code=self.room_code)
            player = Player.objects.get(id=self.player.id)
            if room.state != Room.State.ACTIVE:
                raise GameError("room_not_active")
            game = room.game().apply(player.symbol, action)
            room.apply_game(game)
            room.save()
            if room.state == Room.State.FINISHED:
                match = (
                    room.matches.select_for_update()
                    .filter(finished_at__isnull=True)
                    .first()
                )
                if match:
                    match.finish(
                        game.status.value,
                        match.x_account
                        if game.status.value == "x_won"
                        else match.o_account,
                    )
            return room.public_state()

    @database_sync_to_async
    def _request_rematch(self):
        with transaction.atomic():
            room = Room.objects.select_for_update().get(code=self.room_code)
            if room.state != Room.State.FINISHED:
                raise GameError("game_not_finished")
            if room.players.filter(is_active=True).count() != 2:
                raise GameError("opponent_left")
            room.request_rematch(self.player.symbol)
            room.save()
            if room.state == Room.State.ACTIVE:
                Match.start_for_room(room)
            return room.public_state()

    @database_sync_to_async
    def _leave_room(self):
        with transaction.atomic():
            room = Room.objects.select_for_update().get(code=self.room_code)
            player = Player.objects.select_for_update().get(id=self.player.id)
            if player.is_active:
                player.is_active = False
                player.save(update_fields=("is_active", "last_seen_at"))
                room.close_for_player(player.symbol)
                room.save()
                match = (
                    room.matches.select_for_update()
                    .filter(finished_at__isnull=True)
                    .first()
                )
                if match:
                    match.finish(
                        room.outcome_reason,
                        match.o_account if player.symbol == "X" else match.x_account,
                    )
            return room.public_state()

    @database_sync_to_async
    def _record_community_event(self, action, state):
        from .models import CommunityEvent, CommunityGameSession

        session = (
            CommunityGameSession.objects.filter(tic_tac_toe_room__code=self.room_code)
            .select_related("room")
            .first()
        )
        if not session:
            return None
        event = CommunityEvent.objects.create(
            room=session.room,
            actor=self.player.account,
            kind="game_action",
            payload={
                "game_key": session.game_key,
                "action": action,
                "state": state,
                "session_id": session.id,
            },
        )
        return {
            "group": f"community_{session.room.code}",
            "message": {
                "event": "game_event",
                "id": event.id,
                "kind": event.kind,
                "payload": event.payload,
                "created_at": event.created_at.isoformat(),
            },
        }


class LudoRoomConsumer(AsyncJsonWebsocketConsumer):
    async def connect(self):
        self.room_code = self.scope["url_route"]["kwargs"]["code"].upper()
        self.token = self._auth_token()
        if not self.token or not await connection_allowed(self.scope, self.token):
            await self.close(code=4429 if self.token else 4401)
            return
        self.seat = await self._authenticate()
        if not self.seat:
            await self.close(code=4401)
            return
        self.group_name = f"ludo_{self.room_code}"
        await self.channel_layer.group_add(self.group_name, self.channel_name)
        await self.accept()
        await update_presence("ludo", self.seat.id, self.channel_name)
        await self.send_json({"type": "state", "payload": await self._payload()})

    async def disconnect(self, code):
        if hasattr(self, "group_name"):
            await update_presence(
                "ludo", self.seat.id, self.channel_name, connected=False
            )
            await self.channel_layer.group_discard(self.group_name, self.channel_name)

    async def receive_json(self, content, **kwargs):
        if not await message_allowed(self.token, 20):
            await self.send_json({"type": "error", "error": "rate_limited"})
            return
        if content.get("type") == "ping":
            await update_presence("ludo", self.seat.id, self.channel_name)
            await self.send_json(
                {"type": "pong", "server_time": timezone.now().isoformat()}
            )
            return
        action_id = str(content.get("action_id", ""))[:80]
        if action_id and not await claim_action(self.token, action_id):
            return
        try:
            payload = await self._action(content)
        except ValueError as error:
            await release_action(self.token, action_id)
            await self.send_json({"type": "error", "error": str(error)})
            return
        await self.channel_layer.group_send(
            self.group_name, {"type": "ludo.state", "payload": payload}
        )

    async def ludo_state(self, event):
        await self.send_json({"type": "state", "payload": event["payload"]})

    def _auth_token(self):
        return bearer_token(self.scope)

    @database_sync_to_async
    def _authenticate(self):
        return (
            LudoSeat.objects.select_related("room", "account")
            .filter(
                room__code=self.room_code,
                reconnect_token_hash=token_hash(self.token),
                active=True,
                is_bot=False,
            )
            .first()
        )

    @database_sync_to_async
    def _payload(self):
        from .views import ludo_payload

        return ludo_payload(LudoRoom.objects.get(code=self.room_code))

    @database_sync_to_async
    def _action(self, content):
        from .views import ludo_payload

        with transaction.atomic():
            room = LudoRoom.objects.select_for_update().get(code=self.room_code)
            state = room.game_state
            if room.state != "active":
                raise ValueError("room_not_active")
            if state["current_player"] != self.seat.color:
                raise ValueError("not_your_turn")
            state["events"] = []
            kind = content.get("type")
            if kind == "roll":
                ludo_roll(state)
            elif kind == "move":
                ludo_move(state, int(content.get("piece", -1)))
            else:
                raise ValueError("invalid_action")
            run_bots(state)
            room.game_state = state
            room.version += 1
            if state["winner"] >= 0:
                room.state = "finished"
                match = LudoMatch.objects.filter(
                    room=room, finished_at__isnull=True
                ).first()
                winner_seat = room.seats.filter(color=state["winner"]).first()
                if match:
                    match.winner = winner_seat.account if winner_seat else None
                    match.finished_at = timezone.now()
                    match.save()
            room.save()
            payload = ludo_payload(room)
            from .models import CommunityEvent, CommunityGameSession

            session = (
                CommunityGameSession.objects.filter(ludo_room=room)
                .select_related("room")
                .first()
            )
            if session:
                CommunityEvent.objects.create(
                    room=session.room,
                    actor=self.seat.account,
                    kind="game_action",
                    payload={
                        "game_key": "ludo",
                        "action": {"type": kind, "piece": content.get("piece")},
                        "events": state.get("events", []),
                        "session_id": session.id,
                    },
                )
            return payload
