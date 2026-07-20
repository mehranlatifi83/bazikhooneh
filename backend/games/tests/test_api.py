from rest_framework.test import APITestCase

from games.models import Player, Room, token_hash


class RoomApiTests(APITestCase):
    def test_create_and_join_room(self):
        created = self.client.post("/api/v1/rooms/", {}, format="json")
        self.assertEqual(201, created.status_code)
        code = created.data["game"]["room_code"]
        self.assertEqual("X", created.data["symbol"])
        self.assertTrue(Player.objects.filter(
            room__code=code,
            reconnect_token_hash=token_hash(created.data["reconnect_token"]),
        ).exists())

        joined = self.client.post("/api/v1/rooms/join/", {"code": code.lower()}, format="json")
        self.assertEqual(200, joined.status_code)
        self.assertEqual("O", joined.data["symbol"])
        self.assertEqual(Room.State.ACTIVE, Room.objects.get(code=code).state)

    def test_third_player_cannot_join(self):
        created = self.client.post("/api/v1/rooms/", {}, format="json")
        code = created.data["game"]["room_code"]
        self.client.post("/api/v1/rooms/join/", {"code": code}, format="json")
        response = self.client.post("/api/v1/rooms/join/", {"code": code}, format="json")
        self.assertEqual(409, response.status_code)

    def test_unknown_room_returns_not_found(self):
        response = self.client.post("/api/v1/rooms/join/", {"code": "ABC123"}, format="json")
        self.assertEqual(404, response.status_code)
