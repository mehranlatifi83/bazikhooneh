import base64
import hashlib
import hmac
import re
import time

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from django.conf import settings
from django.db import transaction
from django.db.models import Q

from accounts.models import AccountNotification
from accounts.push import enqueue_notifications
from .models import (
    CommunityCall,
    CommunityMembership,
)


def account_payload(account):
    return {
        "id": str(account.id),
        "username": account.username,
        "display_name": account.display_name,
        "avatar_color": account.avatar_color,
    }


def member_payload(membership):
    return {
        **account_payload(membership.account),
        "role": membership.role,
        "status": membership.status,
        "call_banned": membership.call_banned,
        "chat_muted_until": membership.chat_muted_until.isoformat()
        if membership.chat_muted_until
        else None,
    }


def message_payload(message):
    return {
        "id": message.id,
        "sender": account_payload(message.sender),
        "text": "" if message.deleted_at else message.text,
        "reply_to": message.reply_to_id,
        "edited_at": message.edited_at.isoformat() if message.edited_at else None,
        "deleted": bool(message.deleted_at),
        "created_at": message.created_at.isoformat(),
    }


def call_payload(call):
    if not call:
        return None
    return {
        "id": str(call.id),
        "creator_id": str(call.creator_id),
        "title": call.title,
        "status": call.status,
        "mic_policy": call.mic_policy,
        "max_participants": call.max_participants,
        "started_at": call.started_at.isoformat(),
        "moderator_ids": [
            str(value)
            for value in call.room.memberships.filter(
                status="active", role__in=("owner", "admin")
            ).values_list("account_id", flat=True)
        ],
        "participants": [
            {
                **account_payload(item.account),
                "mic_enabled": item.mic_enabled,
                "can_speak": item.can_speak,
                "status": item.status,
            }
            for item in call.participants.select_related("account").filter(
                status="joined"
            )
        ],
    }


def room_payload(room, account=None):
    active_call = room.calls.filter(status=CommunityCall.Status.ACTIVE).first()
    active_game = (
        room.game_sessions.filter(state__in=("waiting", "active"))
        .filter(
            Q(game_key="three_piece_tic_tac_toe", tic_tac_toe_room__isnull=False)
            | Q(game_key="ludo", ludo_room__isnull=False)
        )
        .order_by("-created_at")
        .first()
    )
    membership = None
    if account:
        membership = room.memberships.filter(account=account).first()
    pending_requests = []
    if membership and membership.can_moderate:
        pending_requests = [
            {"id": item.id, "account": account_payload(item.account)}
            for item in room.join_requests.select_related("account").filter(
                status="pending"
            )
        ]
    active_game_payload = None
    if active_game:
        legacy = active_game.tic_tac_toe_room or active_game.ludo_room
        if active_game.game_key == "three_piece_tic_tac_toe":
            participants = [
                account_payload(item.account)
                for item in legacy.players.select_related("account").filter(
                    is_active=True, account__isnull=False
                )
            ]
            capacity = 2
        else:
            participants = [
                account_payload(item.account)
                for item in legacy.seats.select_related("account").filter(
                    active=True, is_bot=False, account__isnull=False
                )
            ]
            capacity = 4
        active_game_payload = {
            "id": active_game.id,
            "game_key": active_game.game_key,
            "state": active_game.state,
            "participants": participants,
            "capacity": capacity,
            "is_participant": bool(
                account
                and any(value["id"] == str(account.id) for value in participants)
            ),
        }
    return {
        "id": str(room.id),
        "code": room.code,
        "title": room.title,
        "privacy": room.privacy,
        "join_policy": room.join_policy,
        "max_members": room.max_members,
        "is_closed": room.is_closed,
        "membership": member_payload(membership) if membership else None,
        "members": [
            member_payload(item)
            for item in room.memberships.select_related("account").filter(
                status="active"
            )
        ],
        "active_call": call_payload(active_call),
        "pending_join_requests": pending_requests,
        "active_game": active_game_payload,
        "websocket_path": f"/ws/v1/community/{room.code}/",
    }


def active_membership(room, account):
    return (
        room.memberships.select_related("account")
        .filter(account=account, status=CommunityMembership.Status.ACTIVE)
        .first()
    )


def broadcast(room, event, payload):
    async_to_sync(get_channel_layer().group_send)(
        f"community_{room.code}",
        {
            "type": "community.event",
            "message": {
                "event": event,
                **payload,
            },
        },
    )


def ice_server_payload(account):
    servers = [{"urls": [f"stun:{settings.TURN_HOST}:3478"]}]
    if settings.TURN_SHARED_SECRET:
        username = f"{int(time.time()) + 3600}:{account.id}"
        digest = hmac.new(
            settings.TURN_SHARED_SECRET.encode(), username.encode(), hashlib.sha1
        ).digest()
        credential = base64.b64encode(digest).decode()
    elif settings.TURN_USERNAME and settings.TURN_PASSWORD:
        username, credential = settings.TURN_USERNAME, settings.TURN_PASSWORD
    else:
        return servers
    servers.append(
        {
            "urls": [
                f"turn:{settings.TURN_HOST}:3478?transport=udp",
                f"turn:{settings.TURN_HOST}:3478?transport=tcp",
            ],
            "username": username,
            "credential": credential,
        }
    )
    return servers


def notify_room_members(room, actor, kind, title, body, data):
    recipients = (
        room.memberships.filter(status="active")
        .exclude(account=actor)
        .values_list("account_id", flat=True)
    )
    notifications = AccountNotification.objects.bulk_create(
        [
            AccountNotification(
                account_id=account_id, kind=kind, title=title, body=body, data=data
            )
            for account_id in recipients
        ]
    )
    transaction.on_commit(lambda: enqueue_notifications(notifications))


def create_message_notifications(room, sender, text, reply, message):
    usernames = set(re.findall(r"(?<![\w@])@([a-zA-Z0-9_]{3,30})", text.lower()))
    recipients = set(
        room.memberships.filter(status="active", account__username__in=usernames)
        .exclude(account=sender)
        .values_list("account_id", flat=True)
    )
    if reply and reply.sender_id != sender.id:
        recipients.add(reply.sender_id)
    notifications = AccountNotification.objects.bulk_create(
        [
            AccountNotification(
                account_id=account_id,
                kind="room_message",
                title="New room message",
                body=f"{sender.display_name} mentioned or replied to you in {room.title}",
                data={"room_code": room.code, "message_id": message.id},
            )
            for account_id in recipients
        ]
    )
    transaction.on_commit(lambda: enqueue_notifications(notifications))
