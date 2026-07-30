from django.db import transaction
from django.db.models import Count, Q
from django.utils import timezone
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import (
    CommunityEvent,
    CommunityMembership,
    CommunityRoom,
    LudoMatch,
    Match,
    MatchmakingTicket,
)
from .game_services import (
    GameServiceError,
    create_game_session,
    create_quick_match,
    game_capacity,
    is_supported,
    join_game_session,
)


from .community_presenters import (
    account_payload,
    active_membership,
    broadcast,
    ice_server_payload,
    notify_room_members,
    room_payload,
)


class CommunityGameView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
        member = active_membership(room, request.user) if room else None
        if not member or not member.can_moderate:
            return Response({"error": "not_allowed"}, status=403)
        room.game_sessions.filter(state__in=("waiting", "active")).update(
            state="ended", ended_at=timezone.now()
        )
        game_key = str(request.data.get("game_key", ""))
        try:
            session, payload = create_game_session(
                room, request.user, game_key, request.data
            )
        except GameServiceError as error:
            return Response({"error": error.code}, status=error.status)
        CommunityEvent.objects.create(
            room=room,
            actor=request.user,
            kind="game_selected",
            payload={"game_key": game_key, "session_id": session.id},
        )
        notify_room_members(
            room,
            request.user,
            "room_game",
            "A game is ready",
            f"{request.user.display_name} selected a game in {room.title}",
            {"room_code": room.code, "game_key": game_key},
        )
        transaction.on_commit(
            lambda: broadcast(
                room, "game_selected", {"game_key": game_key, "session_id": session.id}
            )
        )
        return Response(
            {"session_id": session.id, "game_key": game_key, "player": payload},
            status=201,
        )


class CommunityGameJoinView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request, code):
        room = (
            CommunityRoom.objects.select_for_update().filter(code=code.upper()).first()
        )
        member = active_membership(room, request.user) if room else None
        session = (
            room.game_sessions.select_for_update()
            .filter(state__in=("waiting", "active"))
            .order_by("-created_at")
            .first()
            if room
            else None
        )
        if not member:
            return Response({"error": "not_a_member"}, status=403)
        if not session:
            return Response({"error": "no_active_game"}, status=404)
        try:
            payload = join_game_session(session, request.user)
        except GameServiceError as error:
            return Response({"error": error.code}, status=error.status)
        CommunityEvent.objects.create(
            room=room,
            actor=request.user,
            kind="game_joined",
            payload={"game_key": session.game_key, "session_id": session.id},
        )
        transaction.on_commit(
            lambda: broadcast(
                room,
                "game_participant_joined",
                {
                    "game_key": session.game_key,
                    "session_id": session.id,
                    "account": account_payload(request.user),
                },
            )
        )
        return Response(
            {"session_id": session.id, "game_key": session.game_key, "player": payload}
        )


class IceServersView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        return Response({"ice_servers": ice_server_payload(request.user)})


class MatchmakingView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        ticket = (
            MatchmakingTicket.objects.select_related("matched_room")
            .filter(account=request.user)
            .first()
        )
        if not ticket:
            return Response({"status": "idle"})
        return Response(
            {
                "status": ticket.status,
                "game_key": ticket.game_key,
                "player": ticket.game_credentials or None,
                "room": room_payload(ticket.matched_room, request.user)
                if ticket.matched_room
                else None,
            }
        )

    @transaction.atomic
    def post(self, request):
        game_key = str(request.data.get("game_key", "three_piece_tic_tac_toe"))
        if not is_supported(game_key):
            return Response({"error": "unsupported_game"}, status=400)
        existing = (
            MatchmakingTicket.objects.select_for_update()
            .filter(account=request.user)
            .first()
        )
        if existing and existing.status == "matched":
            return Response(
                {
                    "status": "matched",
                    "room": room_payload(existing.matched_room, request.user),
                }
            )
        if existing:
            existing.delete()
        other = (
            MatchmakingTicket.objects.select_for_update(skip_locked=True)
            .filter(game_key=game_key, status="waiting")
            .exclude(account=request.user)
            .order_by("created_at")
            .first()
        )
        if not other:
            MatchmakingTicket.objects.create(account=request.user, game_key=game_key)
            return Response({"status": "waiting"}, status=202)
        room = CommunityRoom.create_unique(other.account, "Quick match")
        room.privacy = CommunityRoom.Privacy.PRIVATE
        room.max_members = game_capacity(game_key)
        room.save()
        CommunityMembership.objects.create(
            room=room, account=request.user, role="member"
        )
        first_credentials, second_credentials = create_quick_match(
            room, game_key, other.account, request.user
        )
        other.status = "matched"
        other.matched_room = room
        other.game_credentials = first_credentials
        other.save(
            update_fields=("status", "matched_room", "game_credentials", "updated_at")
        )
        MatchmakingTicket.objects.create(
            account=request.user,
            game_key=game_key,
            status="matched",
            matched_room=room,
            game_credentials=second_credentials,
        )
        return Response(
            {
                "status": "matched",
                "room": room_payload(room, request.user),
                "player": second_credentials or None,
            }
        )

    def delete(self, request):
        MatchmakingTicket.objects.filter(
            account=request.user, status="waiting"
        ).delete()
        return Response(status=204)


class PlayerStatsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        account = request.user
        tic = Match.objects.filter(
            Q(x_account=account) | Q(o_account=account), finished_at__isnull=False
        )
        tic_wins = tic.filter(winner=account).count()
        ludo = LudoMatch.objects.filter(participants=account, finished_at__isnull=False)
        ludo_wins = ludo.filter(winner=account).count()
        return Response(
            {
                "tic_tac_toe": {
                    "played": tic.count(),
                    "wins": tic_wins,
                    "losses": tic.exclude(winner=account).count(),
                },
                "ludo": {
                    "played": ludo.count(),
                    "wins": ludo_wins,
                    "losses": ludo.exclude(winner=account).count(),
                },
                "score": tic_wins * 3 + ludo_wins * 5,
            }
        )


class LeaderboardView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        from accounts.models import Account

        accounts = (
            Account.objects.filter(is_active=True)
            .annotate(
                tic_wins=Count(
                    "matches_won",
                    filter=Q(matches_won__finished_at__isnull=False),
                    distinct=True,
                ),
                ludo_wins=Count(
                    "ludo_matches_won",
                    filter=Q(ludo_matches_won__finished_at__isnull=False),
                    distinct=True,
                ),
            )
            .order_by("-tic_wins", "-ludo_wins", "created_at")[:100]
        )
        return Response(
            {
                "results": [
                    {
                        **account_payload(item),
                        "tic_tac_toe_wins": item.tic_wins,
                        "ludo_wins": item.ludo_wins,
                        "score": item.tic_wins * 3 + item.ludo_wins * 5,
                    }
                    for item in accounts
                ]
            }
        )
