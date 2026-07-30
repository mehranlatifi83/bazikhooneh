from django.db import transaction
from django.utils import timezone
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import (
    CommunityCall,
    CommunityCallParticipant,
    CommunityEvent,
    CommunityRoom,
    CommunitySpeakRequest,
)


from .community_presenters import (
    active_membership,
    broadcast,
    call_payload,
    notify_room_members,
)


class CommunityCallView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
        member = active_membership(room, request.user) if room else None
        if not member or not member.can_moderate:
            return Response({"error": "not_allowed"}, status=403)
        call = room.calls.filter(status="active").first()
        if call:
            return Response(call_payload(call), status=409)
        mic_policy = str(request.data.get("mic_policy", "open"))
        call = CommunityCall.objects.create(
            room=room,
            creator=request.user,
            title=str(request.data.get("title", ""))[:100],
            mic_policy=mic_policy
            if mic_policy in CommunityCall.MicPolicy.values
            else "open",
        )
        CommunityCallParticipant.objects.create(
            call=call, account=request.user, can_speak=True
        )
        CommunityEvent.objects.create(
            room=room,
            actor=request.user,
            kind="call_started",
            payload={"call_id": str(call.id)},
        )
        notify_room_members(
            room,
            request.user,
            "room_call",
            "Voice call started",
            f"{request.user.display_name} started a voice call in {room.title}",
            {"room_code": room.code, "call_id": str(call.id)},
        )
        transaction.on_commit(
            lambda: broadcast(room, "call_started", {"call": call_payload(call)})
        )
        return Response(call_payload(call), status=201)

    @transaction.atomic
    def delete(self, request, code):
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
        member = active_membership(room, request.user) if room else None
        call = room.calls.filter(status="active").first() if room else None
        if (
            not member
            or not call
            or not (member.can_moderate or call.creator_id == request.user.id)
        ):
            return Response({"error": "not_allowed"}, status=403)
        call.status = CommunityCall.Status.ENDED
        call.ended_at = timezone.now()
        call.save(update_fields=("status", "ended_at"))
        call.participants.filter(status="joined").update(
            status="left", left_at=timezone.now()
        )
        transaction.on_commit(
            lambda: broadcast(room, "call_ended", {"call_id": str(call.id)})
        )
        return Response(status=204)


class CommunityCallModerationView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
        actor = active_membership(room, request.user) if room else None
        call = (
            room.calls.select_for_update().filter(status="active").first()
            if room
            else None
        )
        target = (
            call.participants.select_related("account")
            .filter(
                account__username=str(request.data.get("username", "")).lower(),
                status="joined",
            )
            .first()
            if call
            else None
        )
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
            CommunitySpeakRequest.objects.filter(
                call=call, account=target.account, status="pending"
            ).update(status="approved", resolved_at=timezone.now())
            event = "call_speak_allowed"
        elif action == "kick":
            target.status = "kicked"
            target.mic_enabled = False
            target.left_at = timezone.now()
            target.save(update_fields=("status", "mic_enabled", "left_at"))
            event = "call_kicked"
        else:
            return Response({"error": "invalid_action"}, status=400)
        transaction.on_commit(
            lambda: broadcast(
                room,
                event,
                {"account_id": str(target.account_id), "call_id": str(call.id)},
            )
        )
        return Response(call_payload(call))
