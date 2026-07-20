from rest_framework.test import APITestCase

from .models import Account, AccountToken
from games.models import Match, Player, Room


class AccountApiTests(APITestCase):
    registration = {
        "username": "Mehran_83",
        "display_name": "مهران",
        "password": "a-secure-password",
    }

    def test_register_profile_update_and_logout(self):
        registered = self.client.post("/api/v1/accounts/register/", self.registration, format="json")
        self.assertEqual(201, registered.status_code)
        self.assertEqual("mehran_83", registered.data["account"]["username"])
        account = Account.objects.get(username="mehran_83")
        self.assertNotEqual(self.registration["password"], account.password_hash)

        token = registered.data["access_token"]
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
        updated = self.client.patch("/api/v1/accounts/me/", {
            "display_name": "مهران لطیفی", "avatar_color": "#1565C0"
        }, format="json")
        self.assertEqual(200, updated.status_code)
        self.assertEqual("مهران لطیفی", updated.data["display_name"])

        logged_out = self.client.post("/api/v1/accounts/logout/")
        self.assertEqual(204, logged_out.status_code)
        self.assertFalse(AccountToken.objects.filter(account=account).exists())

    def test_login_rejects_wrong_password_and_accepts_correct_password(self):
        self.client.post("/api/v1/accounts/register/", self.registration, format="json")
        wrong = self.client.post("/api/v1/accounts/login/", {
            "username": "mehran_83", "password": "wrong-password"
        }, format="json")
        self.assertEqual(401, wrong.status_code)
        correct = self.client.post("/api/v1/accounts/login/", {
            "username": "MEHRAN_83", "password": "a-secure-password"
        }, format="json")
        self.assertEqual(200, correct.status_code)

    def test_duplicate_username_is_rejected_case_insensitively(self):
        self.client.post("/api/v1/accounts/register/", self.registration, format="json")
        duplicate = self.client.post("/api/v1/accounts/register/", {
            **self.registration, "username": "MEHRAN_83"
        }, format="json")
        self.assertEqual(400, duplicate.status_code)

    def test_profile_stats_and_match_history(self):
        first = self.client.post("/api/v1/accounts/register/", self.registration, format="json")
        first_account = Account.objects.get(username="mehran_83")
        second_account = Account(username="opponent", display_name="Opponent")
        second_account.set_password("another-password")
        second_account.save()
        room = Room.create_unique()
        Player.create_with_token(room, "X", first_account)
        Player.create_with_token(room, "O", second_account)
        match = Match.start_for_room(room)
        match.finish("x_won", first_account)

        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {first.data['access_token']}")
        profile = self.client.get("/api/v1/accounts/me/")
        history = self.client.get("/api/v1/matches/")

        self.assertEqual({"played": 1, "wins": 1, "losses": 0}, profile.data["stats"])
        self.assertEqual("win", history.data["results"][0]["result"])
        self.assertEqual("opponent", history.data["results"][0]["opponent"]["username"])
