from datetime import timedelta

from django.db import models, transaction
from django.db.models import Q
from django.utils import timezone
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from accounts.models import AccountNotification, Friendship
from .models import (
    CommunityCallParticipant,
    CommunityEvent,
    CommunityJoinRequest,
    CommunityMembership,
    CommunityMessage,
    CommunityRoom,
)


from .community_presenters import (
    account_payload,
    active_membership,
    broadcast,
    create_message_notifications,
    member_payload,
    message_payload,
    room_payload,
)


class CommunityRoomsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        rooms = (
            CommunityRoom.objects.filter(
                Q(memberships__account=request.user, memberships__status="active")
                | Q(privacy=CommunityRoom.Privacy.PUBLIC),
                is_closed=False,
            )
            .distinct()
            .order_by("-updated_at")
        )
        return Response(
            {"results": [room_payload(room, request.user) for room in rooms]}
        )

    @transaction.atomic
    def post(self, request):
        title = str(request.data.get("title", "")).strip()
        room = CommunityRoom.create_unique(request.user, title)
        privacy = str(request.data.get("privacy", CommunityRoom.Privacy.PRIVATE))
        join_policy = str(
            request.data.get("join_policy", CommunityRoom.JoinPolicy.OPEN)
        )
        if privacy in CommunityRoom.Privacy.values:
            room.privacy = privacy
        if join_policy in CommunityRoom.JoinPolicy.values:
            room.join_policy = join_policy
        room.max_members = max(2, min(32, int(request.data.get("max_members", 16))))
        room.save()
        CommunityEvent.objects.create(
            room=room, actor=request.user, kind="room_created"
        )
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
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
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
        transaction.on_commit(
            lambda: broadcast(room, "room_updated", {"room": room_payload(room)})
        )
        return Response(room_payload(room, request.user))


class CommunityJoinView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request):
        code = str(request.data.get("code", "")).strip().upper()
        room = (
            CommunityRoom.objects.select_for_update()
            .filter(code=code, is_closed=False)
            .first()
        )
        if not room:
            return Response({"error": "room_not_found"}, status=404)
        membership = room.memberships.filter(account=request.user).first()
        if membership and membership.status == CommunityMembership.Status.BANNED:
            return Response({"error": "banned"}, status=403)
        if room.memberships.filter(status="active").count() >= room.max_members:
            return Response({"error": "room_full"}, status=409)
        if room.join_policy == CommunityRoom.JoinPolicy.REQUEST:
            join_request, _ = CommunityJoinRequest.objects.update_or_create(
                room=room,
                account=request.user,
                defaults={"status": "pending", "resolved_at": None},
            )
            transaction.on_commit(
                lambda: broadcast(
                    room,
                    "join_request",
                    {
                        "request_id": join_request.id,
                        "account": account_payload(request.user),
                    },
                )
            )
            return Response({"detail": "join_requested"}, status=202)
        membership, _ = CommunityMembership.objects.get_or_create(
            room=room, account=request.user
        )
        membership.status = CommunityMembership.Status.ACTIVE
        membership.save()
        CommunityEvent.objects.create(
            room=room, actor=request.user, kind="member_joined"
        )
        from accounts.models import GameInvite

        GameInvite.objects.filter(
            recipient=request.user, room_code=room.code, accepted_at__isnull=True
        ).update(accepted_at=timezone.now())
        transaction.on_commit(
            lambda: broadcast(
                room, "member_joined", {"member": member_payload(membership)}
            )
        )
        return Response(room_payload(room, request.user))


class CommunityLeaveView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
        membership = active_membership(room, request.user) if room else None
        if not membership:
            return Response({"error": "not_a_member"}, status=404)
        if membership.role == CommunityMembership.Role.OWNER:
            replacement = (
                room.memberships.filter(status="active")
                .exclude(id=membership.id)
                .order_by(
                    models.Case(models.When(role="admin", then=0), default=1),
                    "joined_at",
                )
                .first()
            )
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
        transaction.on_commit(
            lambda: broadcast(room, "member_left", {"account_id": str(request.user.id)})
        )
        return Response(status=204)


class CommunityMessagesView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, code):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        if not room or not active_membership(room, request.user):
            return Response({"error": "not_a_member"}, status=403)
        before = request.query_params.get("before")
        messages = (
            room.messages.select_related("sender")
            .filter(deleted_at__isnull=True)
            .order_by("-id")
        )
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
        reply = (
            CommunityMessage.objects.select_related("sender")
            .filter(id=reply_id, room=room)
            .first()
            if reply_id
            else None
        )
        message = CommunityMessage.objects.create(
            room=room, sender=request.user, text=text, reply_to=reply
        )
        create_message_notifications(room, request.user, text, reply, message)
        payload = message_payload(message)
        transaction.on_commit(
            lambda: broadcast(room, "chat_message", {"message": payload})
        )
        return Response(payload, status=201)


class CommunityInviteView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request, code):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        if not room or not active_membership(room, request.user):
            return Response({"error": "not_a_member"}, status=403)
        username = str(request.data.get("username", "")).strip().lower()
        target = request.user.__class__.objects.filter(
            username=username, is_active=True
        ).first()
        if not target:
            return Response({"error": "user_not_found"}, status=404)
        if target == request.user:
            return Response({"error": "cannot_invite_self"}, status=400)
        friends = (
            Friendship.objects.filter(status=Friendship.STATUS_ACCEPTED)
            .filter(
                Q(requester=request.user, recipient=target)
                | Q(requester=target, recipient=request.user)
            )
            .exists()
        )
        if not friends:
            return Response({"error": "not_friends"}, status=403)
        if active_membership(room, target):
            return Response({"error": "already_a_member"}, status=409)
        AccountNotification.objects.create(
            account=target,
            kind="room_invite",
            title="Room invitation",
            body=f"{request.user.display_name} invited you to {room.title}",
            data={"room_code": room.code, "invited_by": request.user.username},
        )
        return Response(
            {"detail": "invited", "account": account_payload(target)}, status=201
        )


class CommunityEventsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, code):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        if not room or not active_membership(room, request.user):
            return Response({"error": "not_a_member"}, status=403)
        events = room.events.select_related("actor").order_by("-id")[:100]
        result = [
            {
                "id": item.id,
                "kind": item.kind,
                "actor": account_payload(item.actor) if item.actor else None,
                "payload": item.payload,
                "created_at": item.created_at.isoformat(),
            }
            for item in reversed(list(events))
        ]
        return Response({"results": result})


class CommunityMessageView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def patch(self, request, code, message_id):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        message = (
            CommunityMessage.objects.select_related("sender")
            .filter(id=message_id, room=room, deleted_at__isnull=True)
            .first()
        )
        if not member or not message or message.sender_id != request.user.id:
            return Response({"error": "not_allowed"}, status=403)
        text = str(request.data.get("text", "")).strip()
        if not text or len(text) > 2000:
            return Response({"error": "invalid_message"}, status=400)
        message.text = text
        message.edited_at = timezone.now()
        message.save(update_fields=("text", "edited_at"))
        transaction.on_commit(
            lambda: broadcast(
                room, "chat_edited", {"message": message_payload(message)}
            )
        )
        return Response(message_payload(message))

    @transaction.atomic
    def delete(self, request, code, message_id):
        room = CommunityRoom.objects.filter(code=code.upper()).first()
        member = active_membership(room, request.user) if room else None
        message = CommunityMessage.objects.filter(id=message_id, room=room).first()
        if (
            not member
            or not message
            or not (message.sender_id == request.user.id or member.can_moderate)
        ):
            return Response({"error": "not_allowed"}, status=403)
        message.deleted_at = timezone.now()
        message.text = ""
        message.save(update_fields=("text", "deleted_at"))
        transaction.on_commit(
            lambda: broadcast(room, "chat_deleted", {"message_id": message.id})
        )
        return Response(status=204)


class CommunityJoinRequestView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code, request_id):
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
        actor = active_membership(room, request.user) if room else None
        join_request = (
            CommunityJoinRequest.objects.select_related("account")
            .filter(id=request_id, room=room, status="pending")
            .first()
        )
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
                room=room, account=join_request.account
            )
            membership.status = "active"
            membership.save()
        transaction.on_commit(
            lambda: broadcast(
                room,
                "join_request_resolved",
                {
                    "request_id": join_request.id,
                    "status": join_request.status,
                    "account": account_payload(join_request.account),
                },
            )
        )
        return Response({"status": join_request.status})


class CommunityModerationView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
        actor = active_membership(room, request.user) if room else None
        target = (
            room.memberships.select_related("account")
            .filter(account__username=str(request.data.get("username", "")).lower())
            .first()
            if room
            else None
        )
        if not actor or not actor.can_moderate or not target or target.role == "owner":
            return Response({"error": "not_allowed"}, status=403)
        action = request.data.get("action")
        if action == "ban":
            target.status = CommunityMembership.Status.BANNED
        elif action == "unban":
            target.status = CommunityMembership.Status.LEFT
        elif action == "mute_chat":
            target.chat_muted_until = timezone.now() + timedelta(
                minutes=max(1, min(1440, int(request.data.get("minutes", 10))))
            )
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
                call__room=room,
                call__status="active",
                account=target.account,
                status="joined",
            ).first()
            if participant:
                participant.status = "kicked"
                participant.mic_enabled = False
                participant.left_at = timezone.now()
                participant.save(update_fields=("status", "mic_enabled", "left_at"))
                kicked_call_id = str(participant.call_id)
        CommunityEvent.objects.create(
            room=room,
            actor=request.user,
            kind=f"moderation_{action}",
            payload={"target": target.account.username},
        )
        transaction.on_commit(
            lambda: broadcast(
                room, "moderation", {"action": action, "target": member_payload(target)}
            )
        )
        if kicked_call_id:
            transaction.on_commit(
                lambda: broadcast(
                    room,
                    "call_kicked",
                    {"account_id": str(target.account_id), "call_id": kicked_call_id},
                )
            )
        return Response(member_payload(target))
