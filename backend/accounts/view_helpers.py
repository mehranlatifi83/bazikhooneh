from datetime import timedelta
from django.conf import settings
from django.db import models
from django.utils import timezone

from .models import (
    AccountSession,
    AccountToken,
    SecurityEvent,
)


def profile_payload(account):
    from django.db.models import Q
    from games.models import Match, LudoMatch

    completed = Match.objects.filter(
        Q(x_account=account) | Q(o_account=account), finished_at__isnull=False
    )
    wins = completed.filter(winner=account).count()
    ludo_completed = LudoMatch.objects.filter(
        participants=account, finished_at__isnull=False
    )
    ludo_wins = ludo_completed.filter(winner=account).count()
    by_game = [
        {"game_key": item["game_key"], "played": item["played"]}
        for item in completed.values("game_key").annotate(played=models.Count("id"))
    ]
    if ludo_completed.exists():
        by_game.append({"game_key": "ludo", "played": ludo_completed.count()})
    return {
        "id": str(account.id),
        "username": account.username,
        "display_name": account.display_name,
        "avatar_color": account.avatar_color,
        "email": account.email,
        "email_verified": account.email_verified,
        "username_changed_at": account.username_changed_at.isoformat()
        if account.username_changed_at
        else None,
        "next_username_change_at": (
            account.username_changed_at + timedelta(days=30)
        ).isoformat()
        if account.username_changed_at
        else None,
        "created_at": account.created_at.isoformat(),
        "stats": {
            "played": completed.count() + ludo_completed.count(),
            "wins": wins + ludo_wins,
            "losses": completed.exclude(winner=account).count()
            + ludo_completed.exclude(winner=account).count(),
            "by_game": by_game,
        },
    }


def client_ip(request):
    remote = request.META.get("REMOTE_ADDR", "")
    trusted = set(getattr(settings, "TRUSTED_PROXY_IPS", ()))
    forwarded = request.META.get("HTTP_X_FORWARDED_FOR", "").split(",")[0].strip()
    return forwarded if remote in trusted and forwarded else remote or None


def issue_session(request, account):
    # Compatibility window for already-installed clients that predate refresh
    # tokens. New clients always send app_version and receive the secure pair.
    if not str(request.data.get("app_version", "")).strip():
        return {
            "access_token": AccountToken.issue(account, lifetime=timedelta(days=30)),
            "token_type": "Bearer",
            "account": profile_payload(account),
            "legacy_session": True,
        }
    session, access, refresh = AccountSession.issue(
        account,
        device_name=request.data.get("device_name", ""),
        app_version=request.data.get("app_version", ""),
        ip_address=client_ip(request),
    )
    return {
        "access_token": access,
        "refresh_token": refresh,
        "token_type": "Bearer",
        "access_expires_in": 3600,
        "refresh_expires_in": 2592000,
        "session_id": str(session.id),
        "account": profile_payload(account),
    }


def log_security(request, event, account=None, **metadata):
    SecurityEvent.objects.create(
        account=account, event=event, ip_address=client_ip(request), metadata=metadata
    )


def account_summary(account):
    online = AccountToken.objects.filter(
        account=account,
        last_used_at__gte=timezone.now() - timedelta(minutes=5),
        expires_at__gt=timezone.now(),
    ).exists()
    return {
        "username": account.username,
        "display_name": account.display_name,
        "avatar_color": account.avatar_color,
        "online": online,
    }
