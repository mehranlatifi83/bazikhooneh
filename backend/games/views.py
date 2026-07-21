from django.db import transaction
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView
from rest_framework.permissions import IsAuthenticated
from django.db.models import Q

from .models import Match, Player, Room, LudoRoom, LudoSeat, LudoMatch, token_hash
from .ludo_engine import initial_state, run_bots
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
                "game_key": match.game_key,
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
        ludo_matches = LudoMatch.objects.select_related("room", "winner").prefetch_related("participants").filter(
            participants=request.user, finished_at__isnull=False,
        )[:50]
        for match in ludo_matches:
            opponents = [participant for participant in match.participants.all() if participant.id != request.user.id]
            results.append({
                "id": str(match.id), "room_code": match.room.code, "game_key": "ludo", "round": 1,
                "symbol": "", "opponents": [{"username": item.username, "display_name": item.display_name,
                                                "avatar_color": item.avatar_color} for item in opponents],
                "result": "win" if match.winner_id == request.user.id else "loss", "outcome": "finished",
                "started_at": match.started_at.isoformat(), "finished_at": match.finished_at.isoformat(),
            })
        results.sort(key=lambda item: item["finished_at"], reverse=True)
        results = results[:50]
        return Response({"results": results})


def ludo_payload(room,seat=None,token=None):
    data={"room_code":room.code,"room_state":room.state,"version":room.version,"game":room.game_state,"host":room.host.username,"seats":[{"color":s.color,"is_bot":s.is_bot,"username":s.account.username if s.account else "","display_name":s.account.display_name if s.account else f"Bot {s.color+1}"} for s in room.seats.select_related("account").order_by("color")]}
    if seat is not None:data.update(color=seat.color,reconnect_token=token,websocket_path=f"/ws/v1/ludo/{room.code}/")
    return data


class CreateLudoRoomView(APIView):
    permission_classes=[IsAuthenticated]
    @transaction.atomic
    def post(self,request):
        room=LudoRoom.create_unique(request.user);room.game_state={"third_six_penalty":bool(request.data.get("third_six_penalty",False))};room.save(update_fields=("game_state","updated_at"));seat,token=LudoSeat.create_human(room,0,request.user);return Response(ludo_payload(room,seat,token),status=201)


class JoinLudoRoomView(APIView):
    permission_classes=[IsAuthenticated]
    @transaction.atomic
    def post(self,request):
        code=str(request.data.get("code","")).strip().upper();room=LudoRoom.objects.select_for_update().filter(code=code).first()
        if not room:return Response({"error":"room_not_found"},status=404)
        if room.state!="waiting" or room.seats.count()>=4:return Response({"error":"room_unavailable"},status=409)
        if room.seats.filter(account=request.user).exists():return Response({"error":"already_in_room"},status=409)
        used=set(room.seats.values_list("color",flat=True));color=next(value for value in range(4) if value not in used);seat,token=LudoSeat.create_human(room,color,request.user);return Response(ludo_payload(room,seat,token))


class StartLudoRoomView(APIView):
    permission_classes=[IsAuthenticated]
    @transaction.atomic
    def post(self,request,code):
        room=LudoRoom.objects.select_for_update().filter(code=code.upper(),host=request.user,state="waiting").first()
        if not room:return Response({"error":"room_unavailable"},status=409)
        used=set(room.seats.values_list("color",flat=True))
        for color in range(4):
            if color not in used:LudoSeat.objects.create(room=room,color=color,is_bot=True)
        bots=[False]*4
        for seat in room.seats.all():bots[seat.color]=seat.is_bot
        penalty=bool(room.game_state.get("third_six_penalty",False));room.game_state=initial_state([True]*4,bots,penalty);room.state="active";room.version+=1;room.save()
        match=LudoMatch.objects.create(room=room);match.participants.set(room.seats.filter(account__isnull=False).values_list("account_id",flat=True))
        transaction.on_commit(lambda:async_to_sync(get_channel_layer().group_send)(f"ludo_{room.code}",{"type":"ludo.state","payload":ludo_payload(room)}))
        return Response(ludo_payload(room))
