import hashlib
import secrets
import uuid
from datetime import timedelta

from django.contrib.auth.hashers import check_password, make_password
from django.db import models
from django.utils import timezone


def hash_token(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class Account(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    username = models.CharField(max_length=30, unique=True, db_index=True)
    display_name = models.CharField(max_length=40)
    password_hash = models.CharField(max_length=128)
    avatar_color = models.CharField(max_length=7, default="#2E7D32")
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

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
