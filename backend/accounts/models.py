import hashlib
import secrets
import uuid
from datetime import timedelta

from django.contrib.auth.base_user import AbstractBaseUser, BaseUserManager
from django.contrib.auth.models import PermissionsMixin
from django.db import models
from django.utils import timezone


def hash_token(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def invite_expiry():
    return timezone.now() + timedelta(hours=24)


class AccountManager(BaseUserManager):
    use_in_migrations = True

    def create_user(self, username, password=None, **extra_fields):
        if not username:
            raise ValueError("A username is required")
        username = str(username).strip().lower()
        email = self.normalize_email(extra_fields.pop("email", ""))
        extra_fields.setdefault("display_name", username)
        account = self.model(username=username, email=email, **extra_fields)
        account.set_password(password)
        account.save(using=self._db)
        return account

    def create_superuser(self, username, email="", password=None, **extra_fields):
        extra_fields.setdefault("is_staff", True)
        extra_fields.setdefault("is_superuser", True)
        extra_fields.setdefault("is_active", True)
        if not extra_fields["is_staff"] or not extra_fields["is_superuser"]:
            raise ValueError("A superuser must have is_staff and is_superuser enabled")
        return self.create_user(username, password, email=email, **extra_fields)


class Account(AbstractBaseUser, PermissionsMixin):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    # Keep the existing database column so deployed password hashes survive
    # the transition from the legacy Account implementation.
    password = models.CharField(max_length=128, db_column="password_hash")
    username = models.CharField(max_length=30, unique=True, db_index=True)
    display_name = models.CharField(max_length=40)
    email = models.EmailField(blank=True, db_index=True)
    email_verified = models.BooleanField(default=False)
    avatar_color = models.CharField(max_length=7, default="#2E7D32")
    is_active = models.BooleanField(default=True)
    is_staff = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    username_changed_at = models.DateTimeField(null=True, blank=True)

    objects = AccountManager()

    USERNAME_FIELD = "username"
    REQUIRED_FIELDS = ["email", "display_name"]

    def __str__(self):
        return self.username


class AccountToken(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, related_name="tokens", on_delete=models.CASCADE)
    token_hash = models.CharField(max_length=64, unique=True, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()
    last_used_at = models.DateTimeField(default=timezone.now)
    session = models.ForeignKey(
        "AccountSession", related_name="access_tokens", on_delete=models.CASCADE,
        null=True, blank=True)

    @classmethod
    def issue(cls, account: Account, session=None, lifetime=timedelta(hours=1)):
        raw = secrets.token_urlsafe(40)
        cls.objects.create(
            account=account,
            token_hash=hash_token(raw),
            session=session,
            expires_at=timezone.now() + lifetime,
        )
        return raw


class AccountSession(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    family_id = models.UUIDField(default=uuid.uuid4, db_index=True, editable=False)
    account = models.ForeignKey(Account, related_name="sessions", on_delete=models.CASCADE)
    refresh_token_hash = models.CharField(max_length=64, unique=True, db_index=True)
    device_name = models.CharField(max_length=120, blank=True)
    app_version = models.CharField(max_length=40, blank=True)
    ip_address = models.GenericIPAddressField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    last_used_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField()
    revoked_at = models.DateTimeField(null=True, blank=True)
    replaced_by = models.ForeignKey(
        "self", null=True, blank=True, on_delete=models.SET_NULL,
        related_name="replaces")

    @classmethod
    def issue(cls, account, device_name="", app_version="", ip_address=None,
              family_id=None):
        raw = secrets.token_urlsafe(48)
        session = cls.objects.create(
            account=account,
            family_id=family_id or uuid.uuid4(),
            refresh_token_hash=hash_token(raw),
            device_name=str(device_name)[:120],
            app_version=str(app_version)[:40],
            ip_address=ip_address,
            expires_at=timezone.now() + timedelta(days=30),
        )
        access = AccountToken.issue(account, session=session)
        return session, access, raw

    def revoke_family(self):
        now = timezone.now()
        AccountSession.objects.filter(
            account=self.account, family_id=self.family_id, revoked_at__isnull=True
        ).update(revoked_at=now)
        AccountToken.objects.filter(
            session__account=self.account, session__family_id=self.family_id
        ).delete()


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


class PushDevice(models.Model):
    account = models.ForeignKey(Account, on_delete=models.CASCADE, related_name="push_devices")
    token = models.CharField(max_length=512, unique=True)
    platform = models.CharField(max_length=16, default="android")
    app_version = models.CharField(max_length=32, blank=True)
    locale = models.CharField(max_length=16, blank=True)
    active = models.BooleanField(default=True)
    last_seen_at = models.DateTimeField(auto_now=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [models.Index(fields=("account", "active"))]


class PushDelivery(models.Model):
    notification = models.ForeignKey(AccountNotification, on_delete=models.CASCADE,
                                     related_name="push_deliveries")
    device = models.ForeignKey(PushDevice, on_delete=models.CASCADE,
                               related_name="deliveries")
    status = models.CharField(max_length=16, default="pending")
    attempts = models.PositiveSmallIntegerField(default=0)
    available_at = models.DateTimeField(default=timezone.now)
    sent_at = models.DateTimeField(null=True, blank=True)
    last_error = models.CharField(max_length=300, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [models.UniqueConstraint(
            fields=("notification", "device"), name="unique_push_delivery")]
        indexes = [models.Index(fields=("status", "available_at"))]


class SecurityEvent(models.Model):
    account = models.ForeignKey(Account, on_delete=models.SET_NULL, null=True, blank=True, related_name="security_events")
    event = models.CharField(max_length=40)
    ip_address = models.GenericIPAddressField(null=True, blank=True)
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ("-created_at",)
