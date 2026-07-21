import time
import asyncio
from collections import deque

from channels.db import database_sync_to_async
from channels.generic.websocket import AsyncJsonWebsocketConsumer
from django.db import transaction
from django.conf import settings
from django.utils import timezone

from .engine import GameError
from .models import Match, Player, Room, token_hash


class RoomConsumer(AsyncJsonWebsocketConsumer):
    async def connect(self):
        self.room_code = self.scope["url_route"]["kwargs"]["code"].upper()
        self.token = self._auth_token()
        self.message_times = deque()
        self.action_ids = deque(maxlen=100)
        self.player = await self._authenticate()
        if self.player is None:
            await self.close(code=4401)
            return

        self.group_name = f"room_{self.room_code}"
        await self.channel_layer.group_add(self.group_name, self.channel_name)
        await self.accept()
        await self._mark_seen()
        await self.send_json({"type": "state", "game": await self._room_state()})
        await self.channel_layer.group_send(self.group_name, {
            "type": "player.presence", "symbol": self.player.symbol, "connected": True,
        })

    async def disconnect(self, close_code):
        if hasattr(self, "group_name"):
            disconnected_at = await self._mark_seen()
            await self.channel_layer.group_send(self.group_name, {
                "type": "player.presence", "symbol": self.player.symbol, "connected": False,
            })
            await self.channel_layer.group_discard(self.group_name, self.channel_name)
            asyncio.create_task(self._close_after_grace(disconnected_at))

    async def _close_after_grace(self, disconnected_at):
        await asyncio.sleep(settings.ONLINE_RECONNECT_GRACE_SECONDS)
        state = await self._forfeit_if_still_away(disconnected_at)
        if state:
            await self.channel_layer.group_send(self.group_name, {"type": "game.state", "game": state})

    async def receive_json(self, content, **kwargs):
        if not self._within_rate_limit():
            await self.send_json({"type": "error", "error": "rate_limited"})
            return
        action_id = content.get("action_id")
        if action_id and action_id in self.action_ids:
            return
        if content.get("type") == "rematch":
            try:
                state = await self._request_rematch()
            except GameError as error:
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
        if content.get("type") != "action" or not isinstance(content.get("action"), dict):
            await self.send_json({"type": "error", "error": "invalid_message"})
            return
        try:
            state = await self._apply_action(content["action"])
        except GameError as error:
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
        if action_id:
            self.action_ids.append(action_id)

    async def game_state(self, event):
        await self.send_json(
            {
                "type": "state",
                "game": event["game"],
                "action_id": event.get("action_id"),
            }
        )

    async def player_presence(self, event):
        await self.send_json({
            "type": "presence", "symbol": event["symbol"],
            "connected": event["connected"],
        })

    def _auth_token(self):
        for key, value in self.scope.get("headers", []):
            if key.lower() == b"authorization":
                authorization = value.decode("utf-8")
                if authorization.startswith("Bearer "):
                    return authorization[7:]
        query = self.scope.get("query_string", b"").decode("utf-8")
        for item in query.split("&"):
            key, _, value = item.partition("=")
            if key == "token":
                return value
        return ""

    def _within_rate_limit(self):
        now = time.monotonic()
        while self.message_times and now - self.message_times[0] > 10:
            self.message_times.popleft()
        if len(self.message_times) >= 20:
            return False
        self.message_times.append(now)
        return True

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
            player = Player.objects.select_for_update().select_related("room").get(id=self.player.id)
            room = Room.objects.select_for_update().get(id=player.room_id)
            if not player.is_active or player.last_seen_at > disconnected_at or room.state in (Room.State.CLOSED, Room.State.FINISHED):
                return None
            player.is_active = False
            player.save(update_fields=("is_active", "last_seen_at"))
            room.close_for_player(player.symbol)
            room.save()
            match = room.matches.select_for_update().filter(finished_at__isnull=True).first()
            if match:
                winner = match.o_account if player.symbol == "X" else match.x_account
                match.finish("disconnect_timeout", winner)
                from accounts.models import AccountNotification
                AccountNotification.objects.create(account=winner,kind="game_event",title="Match completed",body="Your opponent did not reconnect in time.",data={"room_code":room.code,"game_key":room.game_key})
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
                match = room.matches.select_for_update().filter(finished_at__isnull=True).first()
                if match:
                    winner = match.x_account if game.status.value == "x_won" else match.o_account
                    match.finish(game.status.value, winner)
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
                match = room.matches.select_for_update().filter(finished_at__isnull=True).first()
                if match:
                    winner = match.o_account if player.symbol == "X" else match.x_account
                    match.finish(room.outcome_reason, winner)
            return room.public_state()
