from datetime import timedelta

from rest_framework.test import APITestCase
from django.core import mail
from django.contrib.auth.models import User
from django.utils import timezone

from .models import Account, AccountToken, SecurityEvent
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

    def test_authentication_throttles_last_used_database_writes(self):
        self.authenticated()
        token = AccountToken.objects.get()
        recent = timezone.now() - timedelta(minutes=1)
        AccountToken.objects.filter(pk=token.pk).update(last_used_at=recent)
        self.client.get("/api/v1/accounts/me/")
        token.refresh_from_db()
        self.assertEqual(recent, token.last_used_at)

        stale = timezone.now() - timedelta(minutes=16)
        AccountToken.objects.filter(pk=token.pk).update(last_used_at=stale)
        self.client.get("/api/v1/accounts/me/")
        token.refresh_from_db()
        self.assertGreater(token.last_used_at, stale)

    def test_untrusted_forwarded_ip_is_not_logged(self):
        self.client.post(
            "/api/v1/accounts/login/",
            {"username": "missing_user", "password": "wrong-password"},
            format="json",
            REMOTE_ADDR="203.0.113.10",
            HTTP_X_FORWARDED_FOR="198.51.100.20",
        )
        self.assertEqual("203.0.113.10", str(SecurityEvent.objects.latest("created_at").ip_address))

    def test_refresh_token_rotates_and_reuse_revokes_family(self):
        registered = self.client.post(
            "/api/v1/accounts/register/",
            {**self.registration, "device_name": "Test phone", "app_version": "1.0"},
            format="json",
        )
        first_refresh = registered.data["refresh_token"]
        rotated = self.client.post(
            "/api/v1/accounts/refresh/",
            {"refresh_token": first_refresh, "app_version": "1.1"},
            format="json",
        )
        self.assertEqual(200, rotated.status_code)
        self.assertNotEqual(first_refresh, rotated.data["refresh_token"])

        reuse = self.client.post(
            "/api/v1/accounts/refresh/",
            {"refresh_token": first_refresh},
            format="json",
        )
        self.assertEqual(401, reuse.status_code)
        rejected_family = self.client.post(
            "/api/v1/accounts/refresh/",
            {"refresh_token": rotated.data["refresh_token"]},
            format="json",
        )
        self.assertEqual(401, rejected_family.status_code)

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

        self.assertEqual(1, profile.data["stats"]["played"])
        self.assertEqual("three_piece_tic_tac_toe", profile.data["stats"]["by_game"][0]["game_key"])
        self.assertEqual("win", history.data["results"][0]["result"])
        self.assertEqual("opponent", history.data["results"][0]["opponent"]["username"])

    def authenticated(self, username="first_user", email="first@example.com"):
        response = self.client.post("/api/v1/accounts/register/", {
            "username": username, "display_name": "First", "password": "old-password", "email": email
        }, format="json")
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {response.data['access_token']}")
        return response, Account.objects.get(username=username)

    def test_username_change_requires_password_and_enforces_cooldown(self):
        self.authenticated()
        denied = self.client.post("/api/v1/accounts/username/", {
            "username": "new_user", "current_password": "wrong-password"
        }, format="json")
        self.assertEqual(400, denied.status_code)
        changed = self.client.post("/api/v1/accounts/username/", {
            "username": "new_user", "current_password": "old-password"
        }, format="json")
        self.assertEqual(200, changed.status_code)
        cooldown = self.client.post("/api/v1/accounts/username/", {
            "username": "another_user", "current_password": "old-password"
        }, format="json")
        self.assertEqual(429, cooldown.status_code)

    def test_password_change_revokes_other_sessions(self):
        _, account = self.authenticated()
        AccountToken.issue(account)
        changed = self.client.post("/api/v1/accounts/password/", {
            "current_password": "old-password", "new_password": "new-password"
        }, format="json")
        self.assertEqual(204, changed.status_code)
        self.assertEqual(1, AccountToken.objects.filter(account=account).count())
        self.assertTrue(account.__class__.objects.get(pk=account.pk).check_password("new-password"))

    def test_email_verification_and_password_reset(self):
        _, account = self.authenticated()
        requested = self.client.post("/api/v1/accounts/email/request/", {"email": "verified@example.com"}, format="json")
        verification_code = mail.outbox[-1].body.split(": ", 1)[1].splitlines()[0]
        confirmed = self.client.post("/api/v1/accounts/email/confirm/", {
            "code": verification_code
        }, format="json")
        self.assertEqual(200, confirmed.status_code)
        self.assertTrue(confirmed.data["email_verified"])
        self.client.credentials()
        reset = self.client.post("/api/v1/accounts/password-reset/request/", {"email": "verified@example.com"}, format="json")
        reset_code = mail.outbox[-1].body.split(": ", 1)[1].splitlines()[0]
        result = self.client.post("/api/v1/accounts/password-reset/confirm/", {
            "code": reset_code, "new_password": "reset-password"
        }, format="json")
        self.assertEqual(204, result.status_code)
        self.assertTrue(Account.objects.get(pk=account.pk).check_password("reset-password"))

    def test_friend_request_presence_and_game_invite(self):
        first, first_account = self.authenticated()
        self.client.credentials()
        second = self.client.post("/api/v1/accounts/register/", {
            "username": "second_user", "display_name": "Second", "password": "second-password"
        }, format="json")
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {first.data['access_token']}")
        self.assertEqual(201, self.client.post("/api/v1/accounts/friends/", {"username": "second_user"}, format="json").status_code)
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {second.data['access_token']}")
        friends = self.client.get("/api/v1/accounts/friends/")
        request_id = friends.data["requests"][0]["request_id"]
        self.assertEqual(200, self.client.post(f"/api/v1/accounts/friends/requests/{request_id}/accept/").status_code)
        invited = self.client.post("/api/v1/accounts/invites/", {"username": "first_user"}, format="json")
        self.assertEqual(201, invited.status_code)
        self.assertEqual(6, len(invited.data["room"]["code"]))
        self.assertGreaterEqual(invited.data["room"]["max_members"], 4)

    def test_django_admin_credentials_create_matching_game_account(self):
        User.objects.create_superuser("site_admin", "admin@example.com", "admin-password")
        response=self.client.post("/api/v1/accounts/login/",{"username":"site_admin","password":"admin-password"},format="json")
        self.assertEqual(200,response.status_code)
        self.assertTrue(Account.objects.get(username="site_admin").check_password("admin-password"))

    def test_search_reject_remove_block_report_and_notifications(self):
        first,_=self.authenticated()
        self.client.credentials()
        second=self.client.post("/api/v1/accounts/register/",{"username":"second_user","display_name":"Second","password":"second-password"},format="json")
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {first.data['access_token']}")
        self.assertEqual(1,len(self.client.get("/api/v1/accounts/users/search/?q=second").data["results"]))
        self.client.post("/api/v1/accounts/friends/",{"username":"second_user"},format="json")
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {second.data['access_token']}")
        notification=self.client.get("/api/v1/accounts/notifications/")
        self.assertEqual(1,notification.data["unread"])
        request_id=self.client.get("/api/v1/accounts/friends/").data["requests"][0]["request_id"]
        self.assertEqual(204,self.client.delete(f"/api/v1/accounts/friends/requests/{request_id}/").status_code)
        self.assertEqual(201,self.client.post("/api/v1/accounts/blocks/",{"username":"first_user"},format="json").status_code)
        self.assertEqual(201,self.client.post("/api/v1/accounts/reports/",{"username":"first_user","reason":"abuse"},format="json").status_code)
