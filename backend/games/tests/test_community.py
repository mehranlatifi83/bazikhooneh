from asgiref.sync import async_to_sync
from channels.testing import WebsocketCommunicator
from rest_framework.test import APITestCase
from django.core.cache import cache
from django.test import override_settings
from accounts.models import Account, AccountNotification, Friendship

from config.asgi import application
from games.models import CommunityMembership, CommunityRoom


class CommunityRoomApiTests(APITestCase):
    def setUp(self):
        cache.clear()

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

    def test_chat_message_has_reliable_rest_delivery_and_history(self):
        token = self.register("chat_owner")
        self.authenticate(token)
        code = self.client.post("/api/v1/community/rooms/", {
            "title": "Reliable chat"}, format="json").data["code"]
        sent = self.client.post(f"/api/v1/community/rooms/{code}/messages/", {
            "text": "persist this message"}, format="json")
        self.assertEqual(201, sent.status_code)
        self.assertGreater(sent.data["id"], 0)
        history = self.client.get(f"/api/v1/community/rooms/{code}/messages/")
        self.assertEqual("persist this message", history.data["results"][-1]["text"])
        deleted = self.client.delete(
            f"/api/v1/community/rooms/{code}/messages/{sent.data['id']}/")
        self.assertEqual(204, deleted.status_code)
        history = self.client.get(f"/api/v1/community/rooms/{code}/messages/")
        self.assertEqual([], history.data["results"])

    def test_member_can_invite_friend_to_existing_room(self):
        owner_token = self.register("invite_owner")
        self.authenticate(owner_token)
        code = self.client.post("/api/v1/community/rooms/", {
            "title": "Friends room"}, format="json").data["code"]
        self.register("invited_friend")
        owner = Account.objects.get(username="invite_owner")
        friend = Account.objects.get(username="invited_friend")
        Friendship.objects.create(requester=owner, recipient=friend,
                                  status=Friendship.STATUS_ACCEPTED)
        self.authenticate(owner_token)
        response = self.client.post(f"/api/v1/community/rooms/{code}/invite/", {
            "username": "invited_friend"}, format="json")
        self.assertEqual(201, response.status_code)
        self.assertTrue(AccountNotification.objects.filter(
            account=friend, kind="room_invite", data__room_code=code).exists())

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

    @override_settings(TURN_HOST="turn.example.test", TURN_SHARED_SECRET="test-secret",
                       TURN_USERNAME="", TURN_PASSWORD="")
    def test_ice_credentials_are_short_lived_and_user_specific(self):
        token = self.register("turn_user")
        self.authenticate(token)
        response = self.client.get("/api/v1/webrtc/ice-servers/")
        self.assertEqual(200, response.status_code)
        turn = response.data["ice_servers"][1]
        expiry, account_id = turn["username"].split(":", 1)
        self.assertGreater(int(expiry), 0)
        self.assertTrue(account_id)
        self.assertTrue(turn["credential"])
        self.assertIn("transport=udp", turn["urls"][0])

    def test_tic_matchmaking_starts_game_for_both_players(self):
        first_token = self.register("quick_first")
        self.authenticate(first_token)
        waiting = self.client.post("/api/v1/matchmaking/", {
            "game_key": "three_piece_tic_tac_toe"}, format="json")
        self.assertEqual(202, waiting.status_code)
        second_token = self.register("quick_second")
        self.authenticate(second_token)
        matched = self.client.post("/api/v1/matchmaking/", {
            "game_key": "three_piece_tic_tac_toe"}, format="json")
        self.assertEqual(200, matched.status_code)
        self.assertEqual("O", matched.data["player"]["symbol"])
        self.authenticate(first_token)
        first = self.client.get("/api/v1/matchmaking/")
        self.assertEqual("matched", first.data["status"])
        self.assertEqual("X", first.data["player"]["symbol"])
        self.assertEqual(matched.data["player"]["game"]["room_code"],
                         first.data["player"]["game"]["room_code"])

    def test_member_joins_game_through_shared_room(self):
        owner_token = self.register("game_owner")
        self.authenticate(owner_token)
        code = self.client.post("/api/v1/community/rooms/", {
            "title": "One room for everything"}, format="json").data["code"]
        started = self.client.post(f"/api/v1/community/rooms/{code}/game/", {
            "game_key": "three_piece_tic_tac_toe"}, format="json")
        self.assertEqual(201, started.status_code)

        member_token = self.register("game_member")
        self.authenticate(member_token)
        self.assertEqual(200, self.client.post("/api/v1/community/rooms/join/", {
            "code": code}, format="json").status_code)
        joined = self.client.post(f"/api/v1/community/rooms/{code}/game/join/", {}, format="json")
        self.assertEqual(200, joined.status_code)
        self.assertEqual("O", joined.data["player"]["symbol"])
        details = self.client.get(f"/api/v1/community/rooms/{code}/")
        self.assertEqual(2, len(details.data["active_game"]["participants"]))
        self.assertTrue(details.data["active_game"]["is_participant"])
        self.assertNotIn("legacy_room_code", details.data["active_game"])

    def test_ludo_matchmaking_creates_shared_game_session(self):
        first_token = self.register("ludo_first")
        self.authenticate(first_token)
        self.assertEqual(202, self.client.post("/api/v1/matchmaking/", {
            "game_key": "ludo"}, format="json").status_code)
        second_token = self.register("ludo_second")
        self.authenticate(second_token)
        matched = self.client.post("/api/v1/matchmaking/", {
            "game_key": "ludo"}, format="json")
        self.assertEqual(200, matched.status_code)
        self.assertEqual("ludo", matched.data["room"]["active_game"]["game_key"])
        self.assertEqual(2, len(matched.data["room"]["active_game"]["participants"]))


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
            application, f"/ws/v1/community/{self.code}/",
            headers=[(b"host", b"localhost"),
                     (b"authorization", f"Bearer {self.token}".encode())])
        self.assertTrue((await socket.connect())[0])
        self.assertEqual("room_state", (await socket.receive_json_from())["event"])
        self.assertEqual("presence", (await socket.receive_json_from())["event"])
        await socket.send_json_to({"type": "chat.send", "text": "Hello room"})
        message = await socket.receive_json_from()
        self.assertEqual("chat_message", message["event"])
        self.assertEqual("Hello room", message["message"]["text"])
        await socket.disconnect()
