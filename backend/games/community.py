import base64
import hashlib
import hmac
import re
import secrets
import time
from datetime import timedelta

from asgiref.sync import async_to_sync
from channels.db import database_sync_to_async
from channels.generic.websocket import AsyncJsonWebsocketConsumer
from channels.layers import get_channel_layer
from django.conf import settings
from django.db import models, transaction
from django.db.models import Count, Q
from django.utils import timezone
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from accounts.models import AccountNotification, AccountToken, Friendship, hash_token
from .ludo_engine import initial_state
from .models import (
    CommunityCall, CommunityCallParticipant, CommunityEvent, CommunityGameSession,
    CommunityJoinRequest, CommunityMembership, CommunityMessage, CommunityRoom,
    CommunitySpeakRequest, LudoMatch, LudoRoom, LudoSeat, Match, MatchmakingTicket,
    Player, Room, token_hash,
)
from .views import ludo_payload, player_payload
from .websocket_security import (
    bearer_token, connection_allowed, message_allowed, update_presence,
)


def account_payload(account):
    return {"id": str(account.id), "username": account.username,
            "display_name": account.display_name, "avatar_color": account.avatar_color}


def member_payload(membership):
    return {**account_payload(membership.account), "role": membership.role,
            "status": membership.status, "call_banned": membership.call_banned,
            "chat_muted_until": membership.chat_muted_until.isoformat()
            if membership.chat_muted_until else None}


def message_payload(message):
    return {"id": message.id, "sender": account_payload(message.sender),
            "text": "" if message.deleted_at else message.text,
            "reply_to": message.reply_to_id, "edited_at": message.edited_at.isoformat()
            if message.edited_at else None, "deleted": bool(message.deleted_at),
            "created_at": message.created_at.isoformat()}


def call_payload(call):
    if not call:
        return None
    return {"id": str(call.id), "creator_id": str(call.creator_id), "title": call.title,
            "status": call.status, "mic_policy": call.mic_policy,
            "max_participants": call.max_participants,
            "started_at": call.started_at.isoformat(),
            "moderator_ids": [str(value) for value in call.room.memberships.filter(
                status="active", role__in=("owner", "admin")).values_list("account_id", flat=True)],
            "participants": [
                {**account_payload(item.account), "mic_enabled": item.mic_enabled,
                 "can_speak": item.can_speak, "status": item.status}
                for item in call.participants.select_related("account").filter(status="joined")
            ]}


def room_payload(room, account=None):
    active_call = room.calls.filter(status=CommunityCall.Status.ACTIVE).first()
    active_game = room.game_sessions.filter(state__in=("waiting", "active")).order_by("-created_at").first()
    membership = None
    if account:
        membership = room.memberships.filter(account=account).first()
    pending_requests = []
    if membership and membership.can_moderate:
        pending_requests = [{"id": item.id, "account": account_payload(item.account)} for item in
                            room.join_requests.select_related("account").filter(status="pending")]
    active_game_payload = None
    if active_game:
        legacy = active_game.tic_tac_toe_room or active_game.ludo_room
        if active_game.game_key == "three_piece_tic_tac_toe":
            participants = [account_payload(item.account) for item in
                            legacy.players.select_related("account").filter(
                                is_active=True, account__isnull=False)]
            capacity = 2
        else:
            participants = [account_payload(item.account) for item in
                            legacy.seats.select_related("account").filter(
                                active=True, is_bot=False, account__isnull=False)]
            capacity = 4
        active_game_payload = {"id": active_game.id, "game_key": active_game.game_key,
                               "state": active_game.state, "participants": participants,
                               "capacity": capacity,
                               "is_participant": bool(account and any(
                                   value["id"] == str(account.id) for value in participants))}
    return {"id": str(room.id), "code": room.code, "title": room.title,
            "privacy": room.privacy, "join_policy": room.join_policy,
            "max_members": room.max_members, "is_closed": room.is_closed,
            "membership": member_payload(membership) if membership else None,
            "members": [member_payload(item) for item in
                        room.memberships.select_related("account").filter(status="active")],
            "active_call": call_payload(active_call),
            "pending_join_requests": pending_requests,
            "active_game": active_game_payload,
            "websocket_path": f"/ws/v1/community/{room.code}/"}


def active_membership(room, account):
    return room.memberships.select_related("account").filter(
        account=account, status=CommunityMembership.Status.ACTIVE).first()


def broadcast(room, event, payload):
    async_to_sync(get_channel_layer().group_send)(
        f"community_{room.code}", {"type": "community.event", "message": {
            "event": event, **payload,
        }})


def ice_server_payload(account):
    servers = [{"urls": [f"stun:{settings.TURN_HOST}:3478"]}]
    if settings.TURN_SHARED_SECRET:
        username = f"{int(time.time()) + 3600}:{account.id}"
        digest = hmac.new(settings.TURN_SHARED_SECRET.encode(), username.encode(),
                          hashlib.sha1).digest()
        credential = base64.b64encode(digest).decode()
    elif settings.TURN_USERNAME and settings.TURN_PASSWORD:
        username, credential = settings.TURN_USERNAME, settings.TURN_PASSWORD
    else:
        return servers
    servers.append({"urls": [f"turn:{settings.TURN_HOST}:3478?transport=udp",
                              f"turn:{settings.TURN_HOST}:3478?transport=tcp"],
                    "username": username, "credential": credential})
    return servers


def notify_room_members(room, actor, kind, title, body, data):
    recipients = room.memberships.filter(status="active").exclude(
        account=actor).values_list("account_id", flat=True)
    AccountNotification.objects.bulk_create([
        AccountNotification(account_id=account_id, kind=kind, title=title,
                            body=body, data=data) for account_id in recipients
    ])


def create_message_notifications(room, sender, text, reply, message):
    usernames = set(re.findall(r"(?<![\w@])@([a-zA-Z0-9_]{3,30})", text.lower()))
    recipients = set(room.memberships.filter(
        status="active", account__username__in=usernames).exclude(
        account=sender).values_list("account_id", flat=True))
    if reply and reply.sender_id != sender.id:
        recipients.add(reply.sender_id)
    AccountNotification.objects.bulk_create([
        AccountNotification(account_id=account_id, kind="room_message",
            title="New room message",
            body=f"{sender.display_name} mentioned or replied to you in {room.title}",
            data={"room_code": room.code, "message_id": message.id})
        for account_id in recipients
    ])


class CommunityRoomsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        rooms = CommunityRoom.objects.filter(Q(
            memberships__account=request.user, memberships__status="active") |
            Q(privacy=CommunityRoom.Privacy.PUBLIC), is_closed=False
        ).distinct().order_by("-updated_at")
        return Response({"results": [room_payload(room, request.user) for room in rooms]})

    @transaction.atomic
    def post(self, request):
        title = str(request.data.get("title", "")).strip()
        room = CommunityRoom.create_unique(request.user, title)
        privacy = str(request.data.get("privacy", CommunityRoom.Privacy.PRIVATE))
        join_policy = str(request.data.get("join_policy", CommunityRoom.JoinPolicy.OPEN))
        if privacy in CommunityRoom.Privacy.values:
            room.privacy = privacy
        if join_policy in CommunityRoom.JoinPolicy.values:
            room.join_policy = join_policy
        room.max_members = max(2, min(32, int(request.data.get("max_members", 16))))
        room.save()
        CommunityEvent.objects.create(room=room, actor=request.user, kind="room_created")
        return Response(room_payload(room, request.user), status=201)


class CommunityRoomView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, code):
        room = CommunityRoom.objects.filter(code=code.upper(), is_closed=False).first()
        if not room:
            return Response({"error": "room_not_found"}, status=404)
        membership = active_membership(room, request.user)
        if not membership and room.privacy == CommunityRoom.Privacy.PRIVATE:
            return Response({"error": "not_a_member"}, status=403)
        return Response(room_payload(room, request.user))

    @transaction.atomic
    def patch(self, request, code):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        membership = active_membership(room, request.user) if room else None
        if not membership or not membership.can_moderate:
            return Response({"error": "not_allowed"}, status=403)
        if "title" in request.data:
            title = str(request.data["title"]).strip()
            if not title or len(title) > 80:
                return Response({"error": "invalid_title"}, status=400)
            room.title = title
        if "privacy" in request.data:
            privacy = str(request.data["privacy"])
            if privacy not in CommunityRoom.Privacy.values:
                return Response({"error": "invalid_privacy"}, status=400)
            room.privacy = privacy
        if "join_policy" in request.data:
            join_policy = str(request.data["join_policy"])
            if join_policy not in CommunityRoom.JoinPolicy.values:
                return Response({"error": "invalid_join_policy"}, status=400)
            room.join_policy = join_policy
        if "max_members" in request.data:
            room.max_members = max(2, min(32, int(request.data["max_members"])))
        room.save()
        transaction.on_commit(lambda: broadcast(room, "room_updated", {"room": room_payload(room)}))
        return Response(room_payload(room, request.user))


class CommunityJoinView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request):
        code = str(request.data.get("code", "")).strip().upper()
        room = CommunityRoom.objects.select_for_update().filter(code=code, is_closed=False).first()
        if not room:
            return Response({"error": "room_not_found"}, status=404)
        membership = room.memberships.filter(account=request.user).first()
        if membership and membership.status == CommunityMembership.Status.BANNED:
            return Response({"error": "banned"}, status=403)
        if room.memberships.filter(status="active").count() >= room.max_members:
            return Response({"error": "room_full"}, status=409)
        if room.join_policy == CommunityRoom.JoinPolicy.REQUEST:
            join_request, _ = CommunityJoinRequest.objects.update_or_create(
                room=room, account=request.user, defaults={"status": "pending", "resolved_at": None})
            transaction.on_commit(lambda: broadcast(room, "join_request", {
                "request_id": join_request.id, "account": account_payload(request.user)}))
            return Response({"detail": "join_requested"}, status=202)
        membership, _ = CommunityMembership.objects.get_or_create(room=room, account=request.user)
        membership.status = CommunityMembership.Status.ACTIVE
        membership.save()
        CommunityEvent.objects.create(room=room, actor=request.user, kind="member_joined")
        from accounts.models import GameInvite
        GameInvite.objects.filter(recipient=request.user, room_code=room.code,
                                  accepted_at__isnull=True).update(accepted_at=timezone.now())
        transaction.on_commit(lambda: broadcast(room, "member_joined", {
            "member": member_payload(membership)}))
        return Response(room_payload(room, request.user))


class CommunityLeaveView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        membership = active_membership(room, request.user) if room else None
        if not membership:
            return Response({"error": "not_a_member"}, status=404)
        if membership.role == CommunityMembership.Role.OWNER:
            replacement = room.memberships.filter(status="active").exclude(id=membership.id).order_by(
                models.Case(models.When(role="admin", then=0), default=1), "joined_at").first()
            if replacement:
                replacement.role = CommunityMembership.Role.OWNER
                replacement.save(update_fields=("role",))
                room.owner = replacement.account
                room.save(update_fields=("owner", "updated_at"))
            else:
                room.is_closed = True
                room.save(update_fields=("is_closed", "updated_at"))
        membership.status = CommunityMembership.Status.LEFT
        membership.save(update_fields=("status", "last_seen_at"))
        transaction.on_commit(lambda: broadcast(room, "member_left", {
            "account_id": str(request.user.id)}))
        return Response(status=204)


class CommunityMessagesView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, code):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        if not room or not active_membership(room, request.user):
            return Response({"error": "not_a_member"}, status=403)
        before = request.query_params.get("before")
        messages = room.messages.select_related("sender").filter(
            deleted_at__isnull=True).order_by("-id")
        if before and before.isdigit():
            messages = messages.filter(id__lt=int(before))
        items = list(messages[:50])
        items.reverse()
        return Response({"results": [message_payload(item) for item in items]})

    @transaction.atomic
    def post(self, request, code):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        if not member:
            return Response({"error": "not_a_member"}, status=403)
        if member.chat_muted_until and member.chat_muted_until > timezone.now():
            return Response({"error": "chat_muted"}, status=403)
        text = str(request.data.get("text", "")).strip()
        if not text or len(text) > 2000:
            return Response({"error": "invalid_message"}, status=400)
        reply_id = request.data.get("reply_to")
        reply = CommunityMessage.objects.select_related("sender").filter(
            id=reply_id, room=room).first() if reply_id else None
        message = CommunityMessage.objects.create(
            room=room, sender=request.user, text=text, reply_to=reply)
        create_message_notifications(room, request.user, text, reply, message)
        payload = message_payload(message)
        transaction.on_commit(lambda: broadcast(room, "chat_message", {"message": payload}))
        return Response(payload, status=201)


class CommunityInviteView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request, code):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        if not room or not active_membership(room, request.user):
            return Response({"error": "not_a_member"}, status=403)
        username = str(request.data.get("username", "")).strip().lower()
        target = request.user.__class__.objects.filter(username=username, is_active=True).first()
        if not target:
            return Response({"error": "user_not_found"}, status=404)
        if target == request.user:
            return Response({"error": "cannot_invite_self"}, status=400)
        friends = Friendship.objects.filter(status=Friendship.STATUS_ACCEPTED).filter(
            Q(requester=request.user, recipient=target) |
            Q(requester=target, recipient=request.user)).exists()
        if not friends:
            return Response({"error": "not_friends"}, status=403)
        if active_membership(room, target):
            return Response({"error": "already_a_member"}, status=409)
        AccountNotification.objects.create(
            account=target, kind="room_invite", title="Room invitation",
            body=f"{request.user.display_name} invited you to {room.title}",
            data={"room_code": room.code, "invited_by": request.user.username})
        return Response({"detail": "invited", "account": account_payload(target)}, status=201)


class CommunityEventsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, code):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        if not room or not active_membership(room, request.user):
            return Response({"error": "not_a_member"}, status=403)
        events = room.events.select_related("actor").order_by("-id")[:100]
        result = [{"id": item.id, "kind": item.kind,
                   "actor": account_payload(item.actor) if item.actor else None,
                   "payload": item.payload, "created_at": item.created_at.isoformat()}
                  for item in reversed(list(events))]
        return Response({"results": result})


class CommunityMessageView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def patch(self, request, code, message_id):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        message = CommunityMessage.objects.select_related("sender").filter(
            id=message_id, room=room, deleted_at__isnull=True).first()
        if not member or not message or message.sender_id != request.user.id:
            return Response({"error": "not_allowed"}, status=403)
        text = str(request.data.get("text", "")).strip()
        if not text or len(text) > 2000:
            return Response({"error": "invalid_message"}, status=400)
        message.text = text
        message.edited_at = timezone.now()
        message.save(update_fields=("text", "edited_at"))
        transaction.on_commit(lambda: broadcast(room, "chat_edited", {
            "message": message_payload(message)}))
        return Response(message_payload(message))

    @transaction.atomic
    def delete(self, request, code, message_id):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        message = CommunityMessage.objects.filter(id=message_id, room=room).first()
        if not member or not message or not (message.sender_id == request.user.id or member.can_moderate):
            return Response({"error": "not_allowed"}, status=403)
        message.deleted_at = timezone.now()
        message.text = ""
        message.save(update_fields=("text", "deleted_at"))
        transaction.on_commit(lambda: broadcast(room, "chat_deleted", {"message_id": message.id}))
        return Response(status=204)


class CommunityJoinRequestView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code, request_id):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        actor = active_membership(room, request.user) if room else None
        join_request = CommunityJoinRequest.objects.select_related("account").filter(
            id=request_id, room=room, status="pending").first()
        if not actor or not actor.can_moderate or not join_request:
            return Response({"error": "not_allowed"}, status=403)
        action = request.data.get("action")
        if action not in ("approve", "deny"):
            return Response({"error": "invalid_action"}, status=400)
        join_request.status = "approved" if action == "approve" else "denied"
        join_request.resolved_at = timezone.now()
        join_request.save(update_fields=("status", "resolved_at"))
        if action == "approve":
            membership, _ = CommunityMembership.objects.get_or_create(
                room=room, account=join_request.account)
            membership.status = "active"
            membership.save()
        transaction.on_commit(lambda: broadcast(room, "join_request_resolved", {
            "request_id": join_request.id, "status": join_request.status,
            "account": account_payload(join_request.account)}))
        return Response({"status": join_request.status})


class CommunityModerationView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        actor = active_membership(room, request.user) if room else None
        target = room.memberships.select_related("account").filter(
            account__username=str(request.data.get("username", "")).lower()).first() if room else None
        if not actor or not actor.can_moderate or not target or target.role == "owner":
            return Response({"error": "not_allowed"}, status=403)
        action = request.data.get("action")
        if action == "ban":
            target.status = CommunityMembership.Status.BANNED
        elif action == "unban":
            target.status = CommunityMembership.Status.LEFT
        elif action == "mute_chat":
            target.chat_muted_until = timezone.now() + timedelta(
                minutes=max(1, min(1440, int(request.data.get("minutes", 10)))))
        elif action == "unmute_chat":
            target.chat_muted_until = None
        elif action == "ban_call":
            target.call_banned = True
        elif action == "unban_call":
            target.call_banned = False
        elif action == "promote" and actor.role == "owner":
            target.role = CommunityMembership.Role.ADMIN
        elif action == "demote" and actor.role == "owner":
            target.role = CommunityMembership.Role.MEMBER
        else:
            return Response({"error": "invalid_action"}, status=400)
        target.save()
        kicked_call_id = None
        if action in ("ban", "ban_call"):
            participant = CommunityCallParticipant.objects.filter(
                call__room=room, call__status="active", account=target.account,
                status="joined").first()
            if participant:
                participant.status = "kicked"
                participant.mic_enabled = False
                participant.left_at = timezone.now()
                participant.save(update_fields=("status", "mic_enabled", "left_at"))
                kicked_call_id = str(participant.call_id)
        CommunityEvent.objects.create(room=room, actor=request.user, kind=f"moderation_{action}",
                                      payload={"target": target.account.username})
        transaction.on_commit(lambda: broadcast(room, "moderation", {
            "action": action, "target": member_payload(target)}))
        if kicked_call_id:
            transaction.on_commit(lambda: broadcast(room, "call_kicked", {
                "account_id": str(target.account_id), "call_id": kicked_call_id}))
        return Response(member_payload(target))


class CommunityCallView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        if not member or not member.can_moderate:
            return Response({"error": "not_allowed"}, status=403)
        call = room.calls.filter(status="active").first()
        if call:
            return Response(call_payload(call), status=409)
        mic_policy = str(request.data.get("mic_policy", "open"))
        call = CommunityCall.objects.create(
            room=room, creator=request.user, title=str(request.data.get("title", ""))[:100],
            mic_policy=mic_policy if mic_policy in CommunityCall.MicPolicy.values else "open",
            max_participants=max(2, min(8, int(request.data.get("max_participants", 4)))))
        CommunityCallParticipant.objects.create(call=call, account=request.user, can_speak=True)
        CommunityEvent.objects.create(room=room, actor=request.user, kind="call_started",
                                      payload={"call_id": str(call.id)})
        notify_room_members(room, request.user, "room_call", "Voice call started",
                            f"{request.user.display_name} started a voice call in {room.title}",
                            {"room_code": room.code, "call_id": str(call.id)})
        transaction.on_commit(lambda: broadcast(room, "call_started", {"call": call_payload(call)}))
        return Response(call_payload(call), status=201)

    @transaction.atomic
    def delete(self, request, code):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        call = room.calls.filter(status="active").first() if room else None
        if not member or not call or not (member.can_moderate or call.creator_id == request.user.id):
            return Response({"error": "not_allowed"}, status=403)
        call.status = CommunityCall.Status.ENDED
        call.ended_at = timezone.now()
        call.save(update_fields=("status", "ended_at"))
        call.participants.filter(status="joined").update(status="left", left_at=timezone.now())
        transaction.on_commit(lambda: broadcast(room, "call_ended", {"call_id": str(call.id)}))
        return Response(status=204)


class CommunityCallModerationView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        actor = active_membership(room, request.user) if room else None
        call = room.calls.select_for_update().filter(status="active").first() if room else None
        target = call.participants.select_related("account").filter(
            account__username=str(request.data.get("username", "")).lower(),
            status="joined").first() if call else None
        if not actor or not actor.can_moderate or not call or not target:
            return Response({"error": "not_allowed"}, status=403)
        action = request.data.get("action")
        if action == "force_mute":
            target.mic_enabled = False
            target.can_speak = False
            target.save(update_fields=("mic_enabled", "can_speak"))
            event = "call_force_muted"
        elif action == "allow_speak":
            target.can_speak = True
            target.save(update_fields=("can_speak",))
            CommunitySpeakRequest.objects.filter(call=call, account=target.account,
                                                  status="pending").update(
                status="approved", resolved_at=timezone.now())
            event = "call_speak_allowed"
        elif action == "kick":
            target.status = "kicked"
            target.mic_enabled = False
            target.left_at = timezone.now()
            target.save(update_fields=("status", "mic_enabled", "left_at"))
            event = "call_kicked"
        else:
            return Response({"error": "invalid_action"}, status=400)
        transaction.on_commit(lambda: broadcast(room, event, {
            "account_id": str(target.account_id), "call_id": str(call.id)}))
        return Response(call_payload(call))


class CommunityGameView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        if not member or not member.can_moderate:
            return Response({"error": "not_allowed"}, status=403)
        room.game_sessions.filter(state__in=("waiting", "active")).update(
            state="ended", ended_at=timezone.now())
        game_key = str(request.data.get("game_key", ""))
        if game_key == "three_piece_tic_tac_toe":
            legacy = Room.create_unique()
            player, token = Player.create_with_token(legacy, "X", request.user)
            session = CommunityGameSession.objects.create(room=room, game_key=game_key,
                                                           created_by=request.user,
                                                           tic_tac_toe_room=legacy)
            payload = player_payload(legacy, player, token)
        elif game_key == "ludo":
            legacy = LudoRoom.create_unique(request.user)
            legacy.game_state = {"third_six_penalty": bool(request.data.get("third_six_penalty", False))}
            legacy.save(update_fields=("game_state", "updated_at"))
            seat, token = LudoSeat.create_human(legacy, 0, request.user)
            session = CommunityGameSession.objects.create(room=room, game_key=game_key,
                                                           created_by=request.user,
                                                           ludo_room=legacy)
            payload = ludo_payload(legacy, seat, token)
        else:
            return Response({"error": "unsupported_game"}, status=400)
        CommunityEvent.objects.create(room=room, actor=request.user, kind="game_selected",
                                      payload={"game_key": game_key, "session_id": session.id})
        notify_room_members(room, request.user, "room_game", "A game is ready",
                            f"{request.user.display_name} selected a game in {room.title}",
                            {"room_code": room.code, "game_key": game_key})
        transaction.on_commit(lambda: broadcast(room, "game_selected", {
            "game_key": game_key, "session_id": session.id}))
        return Response({"session_id": session.id, "game_key": game_key, "player": payload}, status=201)


class CommunityGameJoinView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        session = room.game_sessions.select_for_update().filter(
            state__in=("waiting", "active")).order_by("-created_at").first() if room else None
        if not member:
            return Response({"error": "not_a_member"}, status=403)
        if not session:
            return Response({"error": "no_active_game"}, status=404)
        if session.game_key == "three_piece_tic_tac_toe":
            legacy = session.tic_tac_toe_room
            existing = legacy.players.filter(account=request.user).first()
            if existing:
                token = secrets.token_urlsafe(32)
                existing.reconnect_token_hash = token_hash(token)
                existing.is_active = True
                existing.save(update_fields=("reconnect_token_hash", "is_active", "last_seen_at"))
                player = existing
            else:
                if legacy.players.filter(is_active=True).count() >= 2:
                    return Response({"error": "game_full"}, status=409)
                symbol = "O" if legacy.players.filter(symbol="X", is_active=True).exists() else "X"
                player, token = Player.create_with_token(legacy, symbol, request.user)
            if legacy.players.filter(is_active=True).count() == 2 and legacy.state == Room.State.WAITING:
                legacy.state = Room.State.ACTIVE
                legacy.save(update_fields=("state", "updated_at"))
                Match.start_for_room(legacy)
                session.state = "active"
                session.save(update_fields=("state",))
            payload = player_payload(legacy, player, token)
        elif session.game_key == "ludo":
            legacy = session.ludo_room
            existing = legacy.seats.filter(account=request.user, is_bot=False).first()
            if existing:
                token = secrets.token_urlsafe(32)
                existing.reconnect_token_hash = token_hash(token)
                existing.active = True
                existing.save(update_fields=("reconnect_token_hash", "active", "last_seen_at"))
                seat = existing
            else:
                used = set(legacy.seats.filter(active=True).values_list("color", flat=True))
                color = next((value for value in range(4) if value not in used), None)
                if color is None:
                    return Response({"error": "game_full"}, status=409)
                seat, token = LudoSeat.create_human(legacy, color, request.user)
            payload = ludo_payload(legacy, seat, token)
        else:
            return Response({"error": "unsupported_game"}, status=400)
        CommunityEvent.objects.create(room=room, actor=request.user, kind="game_joined",
                                      payload={"game_key": session.game_key,
                                               "session_id": session.id})
        transaction.on_commit(lambda: broadcast(room, "game_participant_joined", {
            "game_key": session.game_key, "session_id": session.id,
            "account": account_payload(request.user)}))
        return Response({"session_id": session.id, "game_key": session.game_key,
                         "player": payload})


class IceServersView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        return Response({"ice_servers": ice_server_payload(request.user)})


class MatchmakingView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        ticket = MatchmakingTicket.objects.select_related("matched_room").filter(
            account=request.user).first()
        if not ticket:
            return Response({"status": "idle"})
        return Response({"status": ticket.status, "game_key": ticket.game_key,
                         "player": ticket.game_credentials or None,
                         "room": room_payload(ticket.matched_room, request.user)
                         if ticket.matched_room else None})

    @transaction.atomic
    def post(self, request):
        game_key = str(request.data.get("game_key", "three_piece_tic_tac_toe"))
        if game_key not in ("three_piece_tic_tac_toe", "ludo"):
            return Response({"error": "unsupported_game"}, status=400)
        existing = MatchmakingTicket.objects.select_for_update().filter(account=request.user).first()
        if existing and existing.status == "matched":
            return Response({"status": "matched", "room": room_payload(existing.matched_room, request.user)})
        if existing:
            existing.delete()
        other = MatchmakingTicket.objects.select_for_update(skip_locked=True).filter(
            game_key=game_key, status="waiting").exclude(account=request.user).order_by("created_at").first()
        if not other:
            MatchmakingTicket.objects.create(account=request.user, game_key=game_key)
            return Response({"status": "waiting"}, status=202)
        room = CommunityRoom.create_unique(other.account, "Quick match")
        room.privacy = CommunityRoom.Privacy.PRIVATE
        room.max_members = 4 if game_key == "ludo" else 2
        room.save()
        CommunityMembership.objects.create(room=room, account=request.user, role="member")
        first_credentials = {}
        second_credentials = {}
        if game_key == "three_piece_tic_tac_toe":
            legacy = Room.create_unique()
            first_player, first_token = Player.create_with_token(legacy, "X", other.account)
            second_player, second_token = Player.create_with_token(legacy, "O", request.user)
            legacy.state = Room.State.ACTIVE
            legacy.save(update_fields=("state", "updated_at"))
            Match.start_for_room(legacy)
            CommunityGameSession.objects.create(room=room, game_key=game_key,
                                                created_by=other.account,
                                                tic_tac_toe_room=legacy,
                                                state="active")
            first_credentials = player_payload(legacy, first_player, first_token)
            second_credentials = player_payload(legacy, second_player, second_token)
            CommunityEvent.objects.create(room=room, actor=other.account,
                                          kind="game_selected",
                                          payload={"game_key": game_key})
        else:
            legacy = LudoRoom.create_unique(other.account)
            first_seat, first_token = LudoSeat.create_human(legacy, 0, other.account)
            second_seat, second_token = LudoSeat.create_human(legacy, 1, request.user)
            CommunityGameSession.objects.create(room=room, game_key=game_key,
                                                created_by=other.account,
                                                ludo_room=legacy)
            first_credentials = ludo_payload(legacy, first_seat, first_token)
            second_credentials = ludo_payload(legacy, second_seat, second_token)
            CommunityEvent.objects.create(room=room, actor=other.account,
                                          kind="game_selected",
                                          payload={"game_key": game_key})
        other.status = "matched"
        other.matched_room = room
        other.game_credentials = first_credentials
        other.save(update_fields=("status", "matched_room", "game_credentials", "updated_at"))
        MatchmakingTicket.objects.create(account=request.user, game_key=game_key,
                                          status="matched", matched_room=room,
                                          game_credentials=second_credentials)
        return Response({"status": "matched", "room": room_payload(room, request.user),
                         "player": second_credentials or None})

    def delete(self, request):
        MatchmakingTicket.objects.filter(account=request.user, status="waiting").delete()
        return Response(status=204)


class PlayerStatsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        account = request.user
        tic = Match.objects.filter(Q(x_account=account) | Q(o_account=account),
                                   finished_at__isnull=False)
        tic_wins = tic.filter(winner=account).count()
        ludo = LudoMatch.objects.filter(participants=account, finished_at__isnull=False)
        ludo_wins = ludo.filter(winner=account).count()
        return Response({"tic_tac_toe": {"played": tic.count(), "wins": tic_wins,
                                          "losses": tic.exclude(winner=account).count()},
                         "ludo": {"played": ludo.count(), "wins": ludo_wins,
                                  "losses": ludo.exclude(winner=account).count()},
                         "score": tic_wins * 3 + ludo_wins * 5})


class LeaderboardView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        from accounts.models import Account
        accounts = Account.objects.filter(is_active=True).annotate(
            tic_wins=Count("matches_won", filter=Q(matches_won__finished_at__isnull=False), distinct=True),
            ludo_wins=Count("ludo_matches_won", filter=Q(ludo_matches_won__finished_at__isnull=False), distinct=True),
        ).order_by("-tic_wins", "-ludo_wins", "created_at")[:100]
        return Response({"results": [{**account_payload(item),
            "tic_tac_toe_wins": item.tic_wins, "ludo_wins": item.ludo_wins,
            "score": item.tic_wins * 3 + item.ludo_wins * 5} for item in accounts]})


class CommunityConsumer(AsyncJsonWebsocketConsumer):
    async def connect(self):
        self.code = self.scope["url_route"]["kwargs"]["code"].upper()
        self.token = bearer_token(self.scope)
        self.joined_call = False
        if not self.token or not await connection_allowed(self.scope, self.token):
            await self.close(code=4429 if self.token else 4401)
            return
        self.membership = await self._authenticate()
        if not self.membership:
            await self.close(code=4401)
            return
        self.account_id = str(self.membership.account_id)
        self.group_name = f"community_{self.code}"
        await self.channel_layer.group_add(self.group_name, self.channel_name)
        await self.accept()
        connection_count = await update_presence(
            f"community:{self.code}", self.membership.account_id, self.channel_name)
        await self.send_json({"event": "room_state", "room": await self._state()})
        if connection_count == 1:
            await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                "message": {"event": "presence", "account_id": self.account_id, "connected": True}})

    async def disconnect(self, code):
        if hasattr(self, "group_name"):
            remaining = await update_presence(
                f"community:{self.code}", self.membership.account_id, self.channel_name,
                connected=False)
            call_id = await self._leave_call_silently() if self.joined_call and remaining == 0 else None
            if call_id:
                await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                    "message": {"event": "call_participant_left", "call_id": call_id,
                                "account_id": self.account_id}})
            await self._mark_seen()
            if remaining == 0:
                await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                    "message": {"event": "presence", "account_id": self.account_id,
                                "connected": False}})
            await self.channel_layer.group_discard(self.group_name, self.channel_name)

    async def receive_json(self, content, **kwargs):
        if not await message_allowed(self.token, 40):
            await self.send_json({"event": "error", "error": "rate_limited"})
            return
        kind = content.get("type")
        try:
            if kind == "chat.send":
                message = await self._send_message(str(content.get("text", "")), content.get("reply_to"))
                await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                    "message": {"event": "chat_message", "message": message}})
            elif kind == "call.join":
                call = await self._join_call()
                self.joined_call = True
                await self.send_json({"event": "call_state", "call": call,
                                      "ice_servers": ice_server_payload(self.membership.account)})
                await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                    "message": {"event": "call_participant_joined", "call": call,
                                "account_id": self.account_id}})
            elif kind == "call.leave":
                call_id = await self._leave_call()
                self.joined_call = False
                await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                    "message": {"event": "call_participant_left", "call_id": call_id,
                                "account_id": self.account_id}})
            elif kind == "call.signal":
                if not await self._in_active_call():
                    raise ValueError("not_in_call")
                signal_type = content.get("signal_type")
                payload = content.get("payload")
                if signal_type not in ("offer", "answer", "ice_candidate") or not isinstance(payload, dict):
                    raise ValueError("invalid_signal")
                if len(str(payload)) > 100000:
                    raise ValueError("signal_too_large")
                await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                    "message": {"event": "call_signal", "from": self.account_id,
                                "to": str(content.get("to", "")),
                                "signal_type": signal_type, "payload": payload}})
            elif kind == "call.media_state":
                state = await self._media_state(bool(content.get("mic_enabled")))
                await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                    "message": {"event": "call_media_state", "account_id": self.account_id,
                                **state}})
            elif kind == "call.raise_hand":
                speak_request = await self._raise_hand()
                await self.channel_layer.group_send(self.group_name, {"type": "community.event",
                    "message": {"event": "call_speak_request", **speak_request}})
            elif kind == "ping":
                await update_presence(
                    f"community:{self.code}", self.membership.account_id, self.channel_name)
                await self._mark_seen()
                await self.send_json({"event": "pong", "server_time": timezone.now().isoformat()})
            else:
                raise ValueError("invalid_message")
        except ValueError as error:
            await self.send_json({"event": "error", "error": str(error)})

    async def community_event(self, event):
        message = event["message"]
        target = message.get("to")
        if target and target != self.account_id:
            return
        await self.send_json(message)

    def _token(self):
        return self.token

    @database_sync_to_async
    def _authenticate(self):
        token = AccountToken.objects.select_related("account").filter(
            token_hash=hash_token(self._token()), expires_at__gt=timezone.now(),
            account__is_active=True).first()
        if not token:
            return None
        return CommunityMembership.objects.select_related("account", "room").filter(
            room__code=self.code, account=token.account, status="active").first()

    @database_sync_to_async
    def _state(self):
        return room_payload(CommunityRoom.objects.get(code=self.code), self.membership.account)

    @database_sync_to_async
    def _mark_seen(self):
        CommunityMembership.objects.filter(id=self.membership.id).update(last_seen_at=timezone.now())

    @database_sync_to_async
    def _send_message(self, text, reply_to):
        text = text.strip()
        if not text or len(text) > 2000:
            raise ValueError("invalid_message")
        member = CommunityMembership.objects.get(id=self.membership.id)
        if member.status != CommunityMembership.Status.ACTIVE:
            raise ValueError("not_a_member")
        if member.chat_muted_until and member.chat_muted_until > timezone.now():
            raise ValueError("chat_muted")
        reply = CommunityMessage.objects.select_related("sender").filter(
            id=reply_to, room=member.room).first() if reply_to else None
        message = CommunityMessage.objects.create(
            room=member.room, sender=member.account, text=text, reply_to=reply)
        create_message_notifications(member.room, member.account, text, reply, message)
        return message_payload(message)

    @database_sync_to_async
    def _join_call(self):
        with transaction.atomic():
            member = CommunityMembership.objects.select_for_update().get(id=self.membership.id)
            if member.status != CommunityMembership.Status.ACTIVE:
                raise ValueError("not_a_member")
            if member.call_banned:
                raise ValueError("call_banned")
            call = member.room.calls.select_for_update().filter(status="active").first()
            if not call:
                raise ValueError("no_active_call")
            if call.participants.filter(status="joined").count() >= call.max_participants:
                raise ValueError("call_full")
            participant, _ = CommunityCallParticipant.objects.get_or_create(
                call=call, account=member.account)
            participant.status = "joined"
            participant.left_at = None
            participant.can_speak = call.mic_policy == "open" or call.creator_id == member.account_id
            participant.mic_enabled = participant.can_speak
            participant.save()
            return call_payload(call)

    @database_sync_to_async
    def _leave_call(self):
        participant = CommunityCallParticipant.objects.filter(
            call__room=self.membership.room, call__status="active",
            account_id=self.membership.account_id, status="joined").first()
        if not participant:
            raise ValueError("not_in_call")
        participant.status = "left"
        participant.left_at = timezone.now()
        participant.mic_enabled = False
        participant.save()
        return str(participant.call_id)

    @database_sync_to_async
    def _leave_call_silently(self):
        participant = CommunityCallParticipant.objects.filter(
            call__room=self.membership.room, call__status="active",
            account_id=self.membership.account_id, status="joined").first()
        if not participant:
            return None
        participant.status = "left"
        participant.left_at = timezone.now()
        participant.mic_enabled = False
        participant.save(update_fields=("status", "left_at", "mic_enabled"))
        return str(participant.call_id)

    @database_sync_to_async
    def _in_active_call(self):
        return CommunityCallParticipant.objects.filter(
            call__room=self.membership.room, call__status="active",
            account_id=self.membership.account_id, status="joined").exists()

    @database_sync_to_async
    def _media_state(self, mic_enabled):
        participant = CommunityCallParticipant.objects.filter(
            call__room=self.membership.room, call__status="active",
            account_id=self.membership.account_id, status="joined").first()
        if not participant:
            raise ValueError("not_in_call")
        participant.mic_enabled = mic_enabled and participant.can_speak
        participant.save(update_fields=("mic_enabled",))
        return {"mic_enabled": participant.mic_enabled, "can_speak": participant.can_speak}

    @database_sync_to_async
    def _raise_hand(self):
        participant = CommunityCallParticipant.objects.select_related("account").filter(
            call__room=self.membership.room, call__status="active",
            account_id=self.membership.account_id, status="joined").first()
        if not participant:
            raise ValueError("not_in_call")
        speak_request, _ = CommunitySpeakRequest.objects.update_or_create(
            call=participant.call, account=participant.account,
            defaults={"status": "pending", "resolved_at": None})
        return {"request_id": speak_request.id, "call_id": str(participant.call_id),
                "account": account_payload(participant.account)}
