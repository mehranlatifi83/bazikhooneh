from rest_framework.test import APITestCase

from games.models import Player, Room, token_hash


class RoomApiTests(APITestCase):
    def setUp(self):
        response = self.client.post(
            "/api/v1/accounts/register/",
            {
                "username": "player_one",
                "display_name": "Player One",
                "password": "secure-pass-123",
            },
            format="json",
        )
        self.client.credentials(
            HTTP_AUTHORIZATION=f"Bearer {response.data['access_token']}"
        )

    def test_create_and_join_room(self):
        created = self.client.post("/api/v1/rooms/", {}, format="json")
        self.assertEqual(201, created.status_code)
        code = created.data["game"]["room_code"]
        self.assertEqual("X", created.data["symbol"])
        self.assertTrue(
            Player.objects.filter(
                room__code=code,
                reconnect_token_hash=token_hash(created.data["reconnect_token"]),
            ).exists()
        )

        second = self.client.post(
            "/api/v1/accounts/register/",
            {
                "username": "player_two",
                "display_name": "Player Two",
                "password": "secure-pass-456",
            },
            format="json",
        )
        self.client.credentials(
            HTTP_AUTHORIZATION=f"Bearer {second.data['access_token']}"
        )
        joined = self.client.post(
            "/api/v1/rooms/join/", {"code": code.lower()}, format="json"
        )
        self.assertEqual(200, joined.status_code)
        self.assertEqual("O", joined.data["symbol"])
        self.assertEqual(Room.State.ACTIVE, Room.objects.get(code=code).state)

    def test_third_player_cannot_join(self):
        created = self.client.post("/api/v1/rooms/", {}, format="json")
        code = created.data["game"]["room_code"]
        second = self.client.post(
            "/api/v1/accounts/register/",
            {
                "username": "player_two",
                "display_name": "Player Two",
                "password": "secure-pass-456",
            },
            format="json",
        )
        self.client.credentials(
            HTTP_AUTHORIZATION=f"Bearer {second.data['access_token']}"
        )
        self.client.post("/api/v1/rooms/join/", {"code": code}, format="json")
        third = self.client.post(
            "/api/v1/accounts/register/",
            {
                "username": "player_three",
                "display_name": "Player Three",
                "password": "secure-pass-789",
            },
            format="json",
        )
        self.client.credentials(
            HTTP_AUTHORIZATION=f"Bearer {third.data['access_token']}"
        )
        response = self.client.post(
            "/api/v1/rooms/join/", {"code": code}, format="json"
        )
        self.assertEqual(409, response.status_code)

    def test_unknown_room_returns_not_found(self):
        response = self.client.post(
            "/api/v1/rooms/join/", {"code": "ABC123"}, format="json"
        )
        self.assertEqual(404, response.status_code)

    def test_ludo_room_fills_empty_seats_with_bots(self):
        created = self.client.post("/api/v1/ludo/rooms/")
        self.assertEqual(201, created.status_code)
        code = created.data["room_code"]
        started = self.client.post(f"/api/v1/ludo/rooms/{code}/start/")
        self.assertEqual(200, started.status_code)
        self.assertEqual(4, len(started.data["seats"]))
        self.assertEqual(3, sum(1 for seat in started.data["seats"] if seat["is_bot"]))

    def test_room_creation_requires_account(self):
        self.client.credentials()
        response = self.client.post("/api/v1/rooms/", {}, format="json")
        self.assertEqual(403, response.status_code)
