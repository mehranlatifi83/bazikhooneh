from django.db import models
from django.utils import timezone
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import (
    Account,
    AccountNotification,
    Friendship,
    GameInvite,
    PushDevice,
    UserBlock,
    UserReport,
)


from .view_helpers import (
    account_summary,
)


class FriendsView(APIView):
    throttle_scope = "social"
    permission_classes = [IsAuthenticated]

    def get(self, request):
        accepted = (
            Friendship.objects.filter(status=Friendship.STATUS_ACCEPTED)
            .filter(models.Q(requester=request.user) | models.Q(recipient=request.user))
            .select_related("requester", "recipient")
        )
        pending = Friendship.objects.filter(
            recipient=request.user, status=Friendship.STATUS_PENDING
        ).select_related("requester")
        outgoing = Friendship.objects.filter(
            requester=request.user, status=Friendship.STATUS_PENDING
        ).select_related("recipient")
        return Response(
            {
                "friends": [
                    account_summary(
                        f.recipient
                        if f.requester_id == request.user.id
                        else f.requester
                    )
                    for f in accepted
                ],
                "requests": [
                    account_summary(f.requester) | {"request_id": f.id} for f in pending
                ],
                "outgoing": [
                    account_summary(f.recipient) | {"request_id": f.id}
                    for f in outgoing
                ],
            }
        )

    def post(self, request):
        username = str(request.data.get("username", "")).strip().lower()
        other = Account.objects.filter(username=username).first()
        if not other or other == request.user:
            return Response({"error": "user_not_found"}, status=404)
        if UserBlock.objects.filter(
            models.Q(blocker=request.user, blocked=other)
            | models.Q(blocker=other, blocked=request.user)
        ).exists():
            return Response({"error": "user_unavailable"}, status=403)
        reverse = Friendship.objects.filter(
            requester=other, recipient=request.user
        ).first()
        if reverse:
            reverse.status = Friendship.STATUS_ACCEPTED
            reverse.save()
            return Response(status=200)
        relation, created = Friendship.objects.get_or_create(
            requester=request.user, recipient=other
        )
        if created:
            AccountNotification.objects.create(
                account=other,
                kind="friend_request",
                title="Friend request",
                body=f"@{request.user.username} sent you a friend request",
                data={"request_id": relation.id},
            )
        return Response(status=201)

    def delete(self, request):
        other = Account.objects.filter(
            username=str(request.data.get("username", "")).strip().lower()
        ).first()
        if not other:
            return Response({"error": "user_not_found"}, status=404)
        Friendship.objects.filter(
            models.Q(requester=request.user, recipient=other)
            | models.Q(requester=other, recipient=request.user)
        ).delete()
        return Response(status=204)


class FriendRequestView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request, request_id):
        relation = Friendship.objects.filter(
            id=request_id, recipient=request.user, status=Friendship.STATUS_PENDING
        ).first()
        if not relation:
            return Response({"error": "request_not_found"}, status=404)
        relation.status = Friendship.STATUS_ACCEPTED
        relation.save()
        return Response(status=200)

    def delete(self, request, request_id):
        relation = (
            Friendship.objects.filter(id=request_id)
            .filter(models.Q(recipient=request.user) | models.Q(requester=request.user))
            .first()
        )
        if not relation:
            return Response({"error": "request_not_found"}, status=404)
        relation.delete()
        return Response(status=204)


class UserSearchView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        query = str(request.query_params.get("q", "")).strip().lower()
        if len(query) < 3:
            return Response({"results": []})
        blocked = UserBlock.objects.filter(blocker=request.user).values_list(
            "blocked_id", flat=True
        )
        users = (
            Account.objects.filter(is_active=True, username__icontains=query)
            .exclude(id=request.user.id)
            .exclude(id__in=blocked)[:10]
        )
        return Response({"results": [account_summary(user) for user in users]})


class InvitesView(APIView):
    throttle_scope = "social"
    permission_classes = [IsAuthenticated]

    def get(self, request):
        invites = (
            GameInvite.objects.filter(
                recipient=request.user,
                accepted_at__isnull=True,
                expires_at__gt=timezone.now(),
            )
            .select_related("sender")
            .order_by("-created_at")[:20]
        )
        return Response(
            {
                "results": [
                    {
                        "id": i.id,
                        "game_key": i.game_key,
                        "room_code": i.room_code,
                        "expires_at": i.expires_at.isoformat(),
                        "sender": account_summary(i.sender),
                    }
                    for i in invites
                ]
            }
        )

    def post(self, request):
        from games.models import CommunityRoom
        from games.community_presenters import room_payload

        recipient = Account.objects.filter(
            username=str(request.data.get("username", "")).strip().lower()
        ).first()
        if not recipient:
            return Response({"error": "user_not_found"}, status=404)
        friends = (
            Friendship.objects.filter(status=Friendship.STATUS_ACCEPTED)
            .filter(
                models.Q(requester=request.user, recipient=recipient)
                | models.Q(requester=recipient, recipient=request.user)
            )
            .exists()
        )
        if not friends:
            return Response({"error": "not_friends"}, status=403)
        game_key = str(request.data.get("game_key", "three_piece_tic_tac_toe"))
        if game_key not in ("three_piece_tic_tac_toe", "ludo"):
            return Response({"error": "unsupported_game"}, status=400)
        room = CommunityRoom.create_unique(
            request.user, f"{request.user.display_name}'s game room"
        )
        invite = GameInvite.objects.create(
            sender=request.user,
            recipient=recipient,
            game_key=game_key,
            room_code=room.code,
        )
        AccountNotification.objects.create(
            account=recipient,
            kind="game_invite",
            title="Game room invitation",
            body=f"@{request.user.username} invited you to a shared room",
            data={
                "invite_id": invite.id,
                "game_key": invite.game_key,
                "room_code": room.code,
            },
        )
        return Response(
            {"invite_id": invite.id, "room": room_payload(room, request.user)},
            status=201,
        )


class NotificationsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        values = request.user.notifications.all()[:50]
        return Response(
            {
                "unread": request.user.notifications.filter(
                    read_at__isnull=True
                ).count(),
                "results": [
                    {
                        "id": n.id,
                        "kind": n.kind,
                        "title": n.title,
                        "body": n.body,
                        "data": n.data,
                        "read": n.read_at is not None,
                        "created_at": n.created_at.isoformat(),
                    }
                    for n in values
                ],
            }
        )

    def post(self, request):
        request.user.notifications.filter(read_at__isnull=True).update(
            read_at=timezone.now()
        )
        return Response(status=204)


class PushDevicesView(APIView):
    permission_classes = [IsAuthenticated]
    throttle_scope = "social"

    def post(self, request):
        token = str(request.data.get("token", "")).strip()
        if not token or len(token) > 512:
            return Response({"error": "invalid_push_token"}, status=400)
        defaults = {
            "account": request.user,
            "platform": "android",
            "app_version": str(request.data.get("app_version", ""))[:32],
            "locale": str(request.data.get("locale", ""))[:16],
            "active": True,
        }
        device, created = PushDevice.objects.update_or_create(
            token=token, defaults=defaults
        )
        return Response({"id": device.id}, status=201 if created else 200)

    def delete(self, request):
        token = str(request.data.get("token", "")).strip()
        if token:
            PushDevice.objects.filter(account=request.user, token=token).update(
                active=False
            )
        else:
            PushDevice.objects.filter(account=request.user).update(active=False)
        return Response(status=204)


class BlocksView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        return Response(
            {
                "results": [
                    account_summary(item.blocked)
                    for item in UserBlock.objects.filter(
                        blocker=request.user
                    ).select_related("blocked")
                ]
            }
        )

    def post(self, request):
        other = Account.objects.filter(
            username=str(request.data.get("username", "")).strip().lower()
        ).first()
        if not other or other == request.user:
            return Response({"error": "user_not_found"}, status=404)
        UserBlock.objects.get_or_create(blocker=request.user, blocked=other)
        Friendship.objects.filter(
            models.Q(requester=request.user, recipient=other)
            | models.Q(requester=other, recipient=request.user)
        ).delete()
        return Response(status=201)

    def delete(self, request):
        UserBlock.objects.filter(
            blocker=request.user,
            blocked__username=str(request.data.get("username", "")).strip().lower(),
        ).delete()
        return Response(status=204)


class ReportsView(APIView):
    throttle_scope = "social"
    permission_classes = [IsAuthenticated]

    def post(self, request):
        other = Account.objects.filter(
            username=str(request.data.get("username", "")).strip().lower()
        ).first()
        reason = str(request.data.get("reason", "")).strip()[:40]
        details = str(request.data.get("details", "")).strip()[:500]
        if not other or other == request.user:
            return Response({"error": "user_not_found"}, status=404)
        if not reason:
            return Response({"error": "reason_required"}, status=400)
        UserReport.objects.create(
            reporter=request.user, reported=other, reason=reason, details=details
        )
        return Response(status=201)
