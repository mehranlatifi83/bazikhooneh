from django.db import transaction
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView
from rest_framework.permissions import IsAuthenticated
from django.db.models import Q

from .models import Match, Player, Room
from .serializers import JoinRoomSerializer


def player_payload(room, player, token):
    return {
        "player_id": str(player.id),
        "symbol": player.symbol,
        "reconnect_token": token,
        "websocket_path": f"/ws/v1/rooms/{room.code}/",
        "game": room.public_state(),
    }


class CreateRoomView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request):
        room = Room.create_unique()
        player, token = Player.create_with_token(room, "X", request.user)
        return Response(player_payload(room, player, token), status=status.HTTP_201_CREATED)


class JoinRoomView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request):
        serializer = JoinRoomSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        try:
            room = Room.objects.select_for_update().get(code=serializer.validated_data["code"])
        except Room.DoesNotExist:
            return Response({"error": "room_not_found"}, status=status.HTTP_404_NOT_FOUND)
        if room.state != Room.State.WAITING or room.players.count() >= 2:
            return Response({"error": "room_unavailable"}, status=status.HTTP_409_CONFLICT)

        if room.players.filter(account=request.user).exists():
            return Response({"error": "already_in_room"}, status=status.HTTP_409_CONFLICT)
        player, token = Player.create_with_token(room, "O", request.user)
        from accounts.models import GameInvite
        GameInvite.objects.filter(recipient=request.user, room_code=room.code,
                                  accepted_at__isnull=True).update(accepted_at=room.updated_at)
        room.state = Room.State.ACTIVE
        room.save(update_fields=("state", "updated_at"))
        Match.start_for_room(room)
        state = room.public_state()
        transaction.on_commit(lambda: async_to_sync(get_channel_layer().group_send)(
            f"room_{room.code}", {"type": "game.state", "game": state}
        ))
        return Response(player_payload(room, player, token), status=status.HTTP_200_OK)


class MatchHistoryView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        matches = Match.objects.select_related("x_account", "o_account", "winner").filter(
            Q(x_account=request.user) | Q(o_account=request.user),
            finished_at__isnull=False,
        )[:50]
        results = []
        for match in matches:
            own_symbol = "X" if match.x_account_id == request.user.id else "O"
            opponent = match.o_account if own_symbol == "X" else match.x_account
            results.append({
                "id": str(match.id),
                "room_code": match.room.code,
                "round": match.round_number,
                "symbol": own_symbol,
                "opponent": {
                    "username": opponent.username,
                    "display_name": opponent.display_name,
                    "avatar_color": opponent.avatar_color,
                },
                "result": "win" if match.winner_id == request.user.id else "loss",
                "outcome": match.outcome,
                "started_at": match.started_at.isoformat(),
                "finished_at": match.finished_at.isoformat(),
            })
        return Response({"results": results})
