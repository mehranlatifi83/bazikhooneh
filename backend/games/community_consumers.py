from channels.db import database_sync_to_async
from channels.generic.websocket import AsyncJsonWebsocketConsumer
from django.db import transaction
from django.utils import timezone

from accounts.models import AccountToken, hash_token
from .models import (
    CommunityCallParticipant,
    CommunityMembership,
    CommunityMessage,
    CommunityRoom,
    CommunitySpeakRequest,
)
from .websocket_security import (
    bearer_token,
    connection_allowed,
    message_allowed,
    update_presence,
)


from .community_presenters import (
    account_payload,
    call_payload,
    create_message_notifications,
    ice_server_payload,
    message_payload,
    room_payload,
)


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
            f"community:{self.code}", self.membership.account_id, self.channel_name
        )
        await self.send_json({"event": "room_state", "room": await self._state()})
        if connection_count == 1:
            await self.channel_layer.group_send(
                self.group_name,
                {
                    "type": "community.event",
                    "message": {
                        "event": "presence",
                        "account_id": self.account_id,
                        "connected": True,
                    },
                },
            )

    async def disconnect(self, code):
        if hasattr(self, "group_name"):
            remaining = await update_presence(
                f"community:{self.code}",
                self.membership.account_id,
                self.channel_name,
                connected=False,
            )
            call_id = (
                await self._leave_call_silently()
                if self.joined_call and remaining == 0
                else None
            )
            if call_id:
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "community.event",
                        "message": {
                            "event": "call_participant_left",
                            "call_id": call_id,
                            "account_id": self.account_id,
                        },
                    },
                )
            await self._mark_seen()
            if remaining == 0:
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "community.event",
                        "message": {
                            "event": "presence",
                            "account_id": self.account_id,
                            "connected": False,
                        },
                    },
                )
            await self.channel_layer.group_discard(self.group_name, self.channel_name)

    async def receive_json(self, content, **kwargs):
        if not await message_allowed(self.token, 40):
            await self.send_json({"event": "error", "error": "rate_limited"})
            return
        kind = content.get("type")
        try:
            if kind == "chat.send":
                message = await self._send_message(
                    str(content.get("text", "")), content.get("reply_to")
                )
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "community.event",
                        "message": {"event": "chat_message", "message": message},
                    },
                )
            elif kind == "call.join":
                call = await self._join_call()
                self.joined_call = True
                await self.send_json(
                    {
                        "event": "call_state",
                        "call": call,
                        "ice_servers": ice_server_payload(self.membership.account),
                    }
                )
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "community.event",
                        "message": {
                            "event": "call_participant_joined",
                            "call": call,
                            "account_id": self.account_id,
                        },
                    },
                )
            elif kind == "call.leave":
                call_id = await self._leave_call()
                self.joined_call = False
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "community.event",
                        "message": {
                            "event": "call_participant_left",
                            "call_id": call_id,
                            "account_id": self.account_id,
                        },
                    },
                )
            elif kind == "call.signal":
                if not await self._in_active_call():
                    raise ValueError("not_in_call")
                signal_type = content.get("signal_type")
                payload = content.get("payload")
                if signal_type not in (
                    "offer",
                    "answer",
                    "ice_candidate",
                ) or not isinstance(payload, dict):
                    raise ValueError("invalid_signal")
                if len(str(payload)) > 100000:
                    raise ValueError("signal_too_large")
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "community.event",
                        "message": {
                            "event": "call_signal",
                            "from": self.account_id,
                            "to": str(content.get("to", "")),
                            "signal_type": signal_type,
                            "payload": payload,
                        },
                    },
                )
            elif kind == "call.media_state":
                state = await self._media_state(bool(content.get("mic_enabled")))
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "community.event",
                        "message": {
                            "event": "call_media_state",
                            "account_id": self.account_id,
                            **state,
                        },
                    },
                )
            elif kind == "call.raise_hand":
                speak_request = await self._raise_hand()
                await self.channel_layer.group_send(
                    self.group_name,
                    {
                        "type": "community.event",
                        "message": {"event": "call_speak_request", **speak_request},
                    },
                )
            elif kind == "ping":
                await update_presence(
                    f"community:{self.code}",
                    self.membership.account_id,
                    self.channel_name,
                )
                await self._mark_seen()
                await self.send_json(
                    {"event": "pong", "server_time": timezone.now().isoformat()}
                )
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
        token = (
            AccountToken.objects.select_related("account")
            .filter(
                token_hash=hash_token(self._token()),
                expires_at__gt=timezone.now(),
                account__is_active=True,
            )
            .first()
        )
        if not token:
            return None
        return (
            CommunityMembership.objects.select_related("account", "room")
            .filter(room__code=self.code, account=token.account, status="active")
            .first()
        )

    @database_sync_to_async
    def _state(self):
        return room_payload(
            CommunityRoom.objects.get(code=self.code), self.membership.account
        )

    @database_sync_to_async
    def _mark_seen(self):
        CommunityMembership.objects.filter(id=self.membership.id).update(
            last_seen_at=timezone.now()
        )

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
        reply = (
            CommunityMessage.objects.select_related("sender")
            .filter(id=reply_to, room=member.room)
            .first()
            if reply_to
            else None
        )
        message = CommunityMessage.objects.create(
            room=member.room, sender=member.account, text=text, reply_to=reply
        )
        create_message_notifications(member.room, member.account, text, reply, message)
        return message_payload(message)

    @database_sync_to_async
    def _join_call(self):
        with transaction.atomic():
            member = CommunityMembership.objects.select_for_update().get(
                id=self.membership.id
            )
            if member.status != CommunityMembership.Status.ACTIVE:
                raise ValueError("not_a_member")
            if member.call_banned:
                raise ValueError("call_banned")
            call = member.room.calls.select_for_update().filter(status="active").first()
            if not call:
                raise ValueError("no_active_call")
            if (
                call.max_participants is not None
                and call.participants.filter(status="joined").count()
                >= call.max_participants
            ):
                raise ValueError("call_full")
            participant, _ = CommunityCallParticipant.objects.get_or_create(
                call=call, account=member.account
            )
            participant.status = "joined"
            participant.left_at = None
            participant.can_speak = (
                call.mic_policy == "open" or call.creator_id == member.account_id
            )
            participant.mic_enabled = participant.can_speak
            participant.save()
            return call_payload(call)

    @database_sync_to_async
    def _leave_call(self):
        participant = CommunityCallParticipant.objects.filter(
            call__room=self.membership.room,
            call__status="active",
            account_id=self.membership.account_id,
            status="joined",
        ).first()
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
            call__room=self.membership.room,
            call__status="active",
            account_id=self.membership.account_id,
            status="joined",
        ).first()
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
            call__room=self.membership.room,
            call__status="active",
            account_id=self.membership.account_id,
            status="joined",
        ).exists()

    @database_sync_to_async
    def _media_state(self, mic_enabled):
        participant = CommunityCallParticipant.objects.filter(
            call__room=self.membership.room,
            call__status="active",
            account_id=self.membership.account_id,
            status="joined",
        ).first()
        if not participant:
            raise ValueError("not_in_call")
        participant.mic_enabled = mic_enabled and participant.can_speak
        participant.save(update_fields=("mic_enabled",))
        return {
            "mic_enabled": participant.mic_enabled,
            "can_speak": participant.can_speak,
        }

    @database_sync_to_async
    def _raise_hand(self):
        participant = (
            CommunityCallParticipant.objects.select_related("account")
            .filter(
                call__room=self.membership.room,
                call__status="active",
                account_id=self.membership.account_id,
                status="joined",
            )
            .first()
        )
        if not participant:
            raise ValueError("not_in_call")
        speak_request, _ = CommunitySpeakRequest.objects.update_or_create(
            call=participant.call,
            account=participant.account,
            defaults={"status": "pending", "resolved_at": None},
        )
        return {
            "request_id": speak_request.id,
            "call_id": str(participant.call_id),
            "account": account_payload(participant.account),
        }
