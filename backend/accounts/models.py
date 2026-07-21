import hashlib
import secrets
import uuid
from datetime import timedelta

from django.contrib.auth.hashers import check_password, make_password
from django.db import models
from django.utils import timezone


def hash_token(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def invite_expiry():
    return timezone.now() + timedelta(hours=24)


class Account(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    username = models.CharField(max_length=30, unique=True, db_index=True)
    display_name = models.CharField(max_length=40)
    email = models.EmailField(blank=True, db_index=True)
    email_verified = models.BooleanField(default=False)
    password_hash = models.CharField(max_length=128)
    avatar_color = models.CharField(max_length=7, default="#2E7D32")
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    username_changed_at = models.DateTimeField(null=True, blank=True)

    @property
    def is_authenticated(self):
        return True

    def set_password(self, password: str):
        self.password_hash = make_password(password)

    def check_password(self, password: str) -> bool:
        return check_password(password, self.password_hash)


class AccountToken(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, related_name="tokens", on_delete=models.CASCADE)
    token_hash = models.CharField(max_length=64, unique=True, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()
    last_used_at = models.DateTimeField(auto_now=True)

    @classmethod
    def issue(cls, account: Account):
        raw = secrets.token_urlsafe(40)
        cls.objects.create(
            account=account,
            token_hash=hash_token(raw),
            expires_at=timezone.now() + timedelta(days=30),
        )
        return raw


class UsernameReservation(models.Model):
    username = models.CharField(max_length=30, unique=True)
    account = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="reserved_usernames")
    expires_at = models.DateTimeField()


class OneTimeToken(models.Model):
    PURPOSE_EMAIL = "verify_email"
    PURPOSE_PASSWORD = "reset_password"
    account = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="one_time_tokens")
    purpose = models.CharField(max_length=24)
    token_hash = models.CharField(max_length=64, unique=True, db_index=True)
    pending_email = models.EmailField(blank=True)
    expires_at = models.DateTimeField()
    used_at = models.DateTimeField(null=True, blank=True)

    @classmethod
    def issue(cls, account, purpose, pending_email=""):
        raw = secrets.token_urlsafe(24)
        cls.objects.create(account=account, purpose=purpose, token_hash=hash_token(raw),
                           pending_email=pending_email, expires_at=timezone.now() + timedelta(hours=1))
        return raw


class Friendship(models.Model):
    STATUS_PENDING = "pending"
    STATUS_ACCEPTED = "accepted"
    requester = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="sent_friendships")
    recipient = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="received_friendships")
    status = models.CharField(max_length=12, default=STATUS_PENDING)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=("requester", "recipient"), name="unique_friend_request")]


class GameInvite(models.Model):
    sender = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="sent_game_invites")
    recipient = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="received_game_invites")
    game_key = models.CharField(max_length=40)
    room_code = models.CharField(max_length=6)
    created_at = models.DateTimeField(auto_now_add=True)
    accepted_at = models.DateTimeField(null=True, blank=True)
    expires_at = models.DateTimeField(default=invite_expiry)

    @property
    def is_expired(self):
        return self.expires_at <= timezone.now()


class UserBlock(models.Model):
    blocker = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="blocks_made")
    blocked = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="blocks_received")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=("blocker", "blocked"), name="unique_user_block")]


class UserReport(models.Model):
    reporter = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="reports_made")
    reported = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="reports_received")
    reason = models.CharField(max_length=40)
    details = models.CharField(max_length=500, blank=True)
    status = models.CharField(max_length=16, default="open")
    created_at = models.DateTimeField(auto_now_add=True)


class AccountNotification(models.Model):
    account = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="notifications")
    kind = models.CharField(max_length=32)
    title = models.CharField(max_length=120)
    body = models.CharField(max_length=300)
    data = models.JSONField(default=dict, blank=True)
    read_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ("-created_at",)


class SecurityEvent(models.Model):
    account = models.ForeignKey(Account, on_delete=models.SET_NULL, null=True, blank=True, related_name="security_events")
    event = models.CharField(max_length=40)
    ip_address = models.GenericIPAddressField(null=True, blank=True)
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ("-created_at",)
