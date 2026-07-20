from django.db import transaction
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Player, Room
from .serializers import JoinRoomSerializer


def player_payload(room, player, token):
    return {
        "player_id": str(player.id),
        "symbol": player.symbol,
        "reconnect_token": token,
        "websocket_path": f"/ws/v1/rooms/{room.code}/?token={token}",
        "game": room.public_state(),
    }


class CreateRoomView(APIView):
    authentication_classes = []
    permission_classes = []

    @transaction.atomic
    def post(self, request):
        room = Room.create_unique()
        player, token = Player.create_with_token(room, "X")
        return Response(player_payload(room, player, token), status=status.HTTP_201_CREATED)


class JoinRoomView(APIView):
    authentication_classes = []
    permission_classes = []

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

        player, token = Player.create_with_token(room, "O")
        room.state = Room.State.ACTIVE
        room.save(update_fields=("state", "updated_at"))
        state = room.public_state()
        transaction.on_commit(lambda: async_to_sync(get_channel_layer().group_send)(
            f"room_{room.code}", {"type": "game.state", "game": state}
        ))
        return Response(player_payload(room, player, token), status=status.HTTP_200_OK)
