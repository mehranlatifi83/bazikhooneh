from asgiref.sync import async_to_sync
from channels.testing import WebsocketCommunicator
from rest_framework.test import APITestCase
from django.core.cache import cache

from config.asgi import application
from games.models import CommunityMembership, CommunityRoom


class CommunityRoomApiTests(APITestCase):
    def register(self, username):
        response = self.client.post("/api/v1/accounts/register/", {
            "username": username, "display_name": username.title(),
            "password": "secure-password-123",
        }, format="json")
        self.assertEqual(201, response.status_code)
        return response.data["access_token"]

    def authenticate(self, token):
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")

    def test_room_survives_switching_between_games(self):
        owner_token = self.register("room_owner")
        self.authenticate(owner_token)
        created = self.client.post("/api/v1/community/rooms/", {
            "title": "Friday games", "privacy": "private",
        }, format="json")
        self.assertEqual(201, created.status_code)
        code = created.data["code"]

        tic = self.client.post(f"/api/v1/community/rooms/{code}/game/", {
            "game_key": "three_piece_tic_tac_toe",
        }, format="json")
        self.assertEqual(201, tic.status_code)
        ludo = self.client.post(f"/api/v1/community/rooms/{code}/game/", {
            "game_key": "ludo", "third_six_penalty": True,
        }, format="json")
        self.assertEqual(201, ludo.status_code)
        self.assertEqual("ludo", ludo.data["game_key"])
        room = CommunityRoom.objects.get(code=code)
        self.assertEqual(2, room.game_sessions.count())
        self.assertEqual("ended", room.game_sessions.order_by("created_at").first().state)

    def test_moderation_and_optional_call(self):
        owner_token = self.register("owner_user")
        self.authenticate(owner_token)
        code = self.client.post("/api/v1/community/rooms/", {"title": "Room"},
                                format="json").data["code"]
        member_token = self.register("member_user")
        self.authenticate(member_token)
        self.assertEqual(200, self.client.post("/api/v1/community/rooms/join/", {
            "code": code}, format="json").status_code)

        self.authenticate(owner_token)
        call = self.client.post(f"/api/v1/community/rooms/{code}/call/", {
            "mic_policy": "request", "max_participants": 4,
        }, format="json")
        self.assertEqual(201, call.status_code)
        muted = self.client.post(f"/api/v1/community/rooms/{code}/moderate/", {
            "username": "member_user", "action": "mute_chat", "minutes": 5,
        }, format="json")
        self.assertEqual(200, muted.status_code)
        self.assertIsNotNone(muted.data["chat_muted_until"])

    def test_banned_member_cannot_rejoin(self):
        owner_token = self.register("ban_owner")
        self.authenticate(owner_token)
        code = self.client.post("/api/v1/community/rooms/", {}, format="json").data["code"]
        member_token = self.register("banned_member")
        self.authenticate(member_token)
        self.client.post("/api/v1/community/rooms/join/", {"code": code}, format="json")
        self.authenticate(owner_token)
        self.client.post(f"/api/v1/community/rooms/{code}/moderate/", {
            "username": "banned_member", "action": "ban"}, format="json")
        self.authenticate(member_token)
        self.assertEqual(403, self.client.post("/api/v1/community/rooms/join/", {
            "code": code}, format="json").status_code)


class CommunityRoomWebSocketTests(APITestCase):
    def setUp(self):
        cache.clear()
        first = self.client.post("/api/v1/accounts/register/", {
            "username": "socket_owner", "display_name": "Owner",
            "password": "secure-password-123"}, format="json")
        self.token = first.data["access_token"]
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {self.token}")
        self.code = self.client.post("/api/v1/community/rooms/", {
            "title": "Live room"}, format="json").data["code"]

    def test_chat_and_call_signaling(self):
        async_to_sync(self._flow)()

    async def _flow(self):
        socket = WebsocketCommunicator(
            application, f"/ws/v1/community/{self.code}/?token={self.token}",
            headers=[(b"host", b"localhost")])
        self.assertTrue((await socket.connect())[0])
        self.assertEqual("room_state", (await socket.receive_json_from())["event"])
        self.assertEqual("presence", (await socket.receive_json_from())["event"])
        await socket.send_json_to({"type": "chat.send", "text": "Hello room"})
        message = await socket.receive_json_from()
        self.assertEqual("chat_message", message["event"])
        self.assertEqual("Hello room", message["message"]["text"])
        await socket.disconnect()
