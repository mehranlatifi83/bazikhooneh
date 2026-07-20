from channels.db import database_sync_to_async
from channels.generic.websocket import AsyncJsonWebsocketConsumer
from django.db import transaction

from .engine import GameError
from .models import Player, Room, token_hash


class RoomConsumer(AsyncJsonWebsocketConsumer):
    async def connect(self):
        self.room_code = self.scope["url_route"]["kwargs"]["code"].upper()
        self.token = self._query_token()
        self.player = await self._authenticate()
        if self.player is None:
            await self.close(code=4401)
            return

        self.group_name = f"room_{self.room_code}"
        await self.channel_layer.group_add(self.group_name, self.channel_name)
        await self.accept()
        await self.send_json({"type": "state", "game": await self._room_state()})

    async def disconnect(self, close_code):
        if hasattr(self, "group_name"):
            await self.channel_layer.group_discard(self.group_name, self.channel_name)

    async def receive_json(self, content, **kwargs):
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

    async def game_state(self, event):
        await self.send_json(
            {
                "type": "state",
                "game": event["game"],
                "action_id": event.get("action_id"),
            }
        )

    def _query_token(self):
        query = self.scope.get("query_string", b"").decode("utf-8")
        for item in query.split("&"):
            key, _, value = item.partition("=")
            if key == "token":
                return value
        return ""

    @database_sync_to_async
    def _authenticate(self):
        try:
            return Player.objects.select_related("room").get(
                room__code=self.room_code,
                reconnect_token_hash=token_hash(self.token),
            )
        except Player.DoesNotExist:
            return None

    @database_sync_to_async
    def _room_state(self):
        return Room.objects.get(code=self.room_code).public_state()

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
            return room.public_state()
