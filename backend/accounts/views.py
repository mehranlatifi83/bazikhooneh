from datetime import timedelta
from django.conf import settings
from django.core.mail import send_mail
from django.db import IntegrityError, models, transaction
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import (Account, AccountToken, Friendship, GameInvite, OneTimeToken,
                     UsernameReservation, hash_token)
from .serializers import (LoginSerializer, PasswordChangeSerializer, ProfileUpdateSerializer,
                          RegisterSerializer, UsernameChangeSerializer)


def profile_payload(account):
    from django.db.models import Q
    from games.models import Match

    completed = Match.objects.filter(
        Q(x_account=account) | Q(o_account=account), finished_at__isnull=False
    )
    wins = completed.filter(winner=account).count()
    return {
        "id": str(account.id),
        "username": account.username,
        "display_name": account.display_name,
        "avatar_color": account.avatar_color,
        "email": account.email,
        "email_verified": account.email_verified,
        "username_changed_at": account.username_changed_at.isoformat() if account.username_changed_at else None,
        "next_username_change_at": (account.username_changed_at + timedelta(days=30)).isoformat()
        if account.username_changed_at else None,
        "created_at": account.created_at.isoformat(),
        "stats": {
            "played": completed.count(),
            "wins": wins,
            "losses": completed.exclude(winner=account).count(),
        },
    }


def session_payload(account, token):
    return {"access_token": token, "token_type": "Bearer", "account": profile_payload(account)}


class RegisterView(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]

    @transaction.atomic
    def post(self, request):
        serializer = RegisterSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        account = Account(
            username=serializer.validated_data["username"],
            display_name=serializer.validated_data["display_name"],
            email=serializer.validated_data.get("email", "").lower(),
        )
        account.set_password(serializer.validated_data["password"])
        try:
            account.save()
        except IntegrityError:
            return Response({"error": "username_taken"}, status=status.HTTP_409_CONFLICT)
        return Response(session_payload(account, AccountToken.issue(account)), status=status.HTTP_201_CREATED)


class LoginView(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]

    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        try:
            account = Account.objects.get(username=serializer.validated_data["username"], is_active=True)
        except Account.DoesNotExist:
            return Response({"error": "invalid_credentials"}, status=status.HTTP_401_UNAUTHORIZED)
        if not account.check_password(serializer.validated_data["password"]):
            return Response({"error": "invalid_credentials"}, status=status.HTTP_401_UNAUTHORIZED)
        return Response(session_payload(account, AccountToken.issue(account)))


class ProfileView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        return Response(profile_payload(request.user))

    def patch(self, request):
        serializer = ProfileUpdateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        for field, value in serializer.validated_data.items():
            setattr(request.user, field, value)
        request.user.save()
        return Response(profile_payload(request.user))


class LogoutView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        request.auth.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


class UsernameChangeView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request):
        serializer = UsernameChangeSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        account = request.user
        if not account.check_password(serializer.validated_data["current_password"]):
            return Response({"error": "invalid_password"}, status=400)
        if account.username_changed_at and account.username_changed_at + timedelta(days=30) > timezone.now():
            return Response({"error": "username_cooldown", "next_change_at":
                (account.username_changed_at + timedelta(days=30)).isoformat()}, status=429)
        new_username = serializer.validated_data["username"]
        if new_username == account.username:
            return Response({"error": "username_unchanged"}, status=400)
        if Account.objects.filter(username=new_username).exists() or UsernameReservation.objects.filter(
                username=new_username, expires_at__gt=timezone.now()).exclude(account=account).exists():
            return Response({"error": "username_taken"}, status=409)
        UsernameReservation.objects.update_or_create(username=account.username,
            defaults={"account": account, "expires_at": timezone.now() + timedelta(days=90)})
        account.username = new_username
        account.username_changed_at = timezone.now()
        account.save(update_fields=("username", "username_changed_at", "updated_at"))
        return Response(profile_payload(account))


class PasswordChangeView(APIView):
    permission_classes = [IsAuthenticated]
    def post(self, request):
        serializer = PasswordChangeSerializer(data=request.data); serializer.is_valid(raise_exception=True)
        if not request.user.check_password(serializer.validated_data["current_password"]):
            return Response({"error": "invalid_password"}, status=400)
        request.user.set_password(serializer.validated_data["new_password"]); request.user.save()
        AccountToken.objects.filter(account=request.user).exclude(id=request.auth.id).delete()
        return Response(status=204)


class EmailVerificationRequestView(APIView):
    permission_classes = [IsAuthenticated]
    def post(self, request):
        email = str(request.data.get("email", "")).strip().lower()
        from django.core.validators import validate_email
        try: validate_email(email)
        except Exception: return Response({"error": "invalid_email"}, status=400)
        if Account.objects.filter(email__iexact=email, email_verified=True).exclude(id=request.user.id).exists():
            return Response({"error": "email_taken"}, status=409)
        raw = OneTimeToken.issue(request.user, OneTimeToken.PURPOSE_EMAIL, email)
        send_mail("BaziKhooneh email verification", f"Verification code: {raw}",
                  settings.DEFAULT_FROM_EMAIL, [email])
        data = {"detail": "verification_sent"}
        if settings.DEBUG: data["development_code"] = raw
        return Response(data)


class EmailVerificationConfirmView(APIView):
    permission_classes = [IsAuthenticated]
    def post(self, request):
        token = OneTimeToken.objects.filter(token_hash=hash_token(str(request.data.get("code", ""))),
            purpose=OneTimeToken.PURPOSE_EMAIL, account=request.user, used_at__isnull=True,
            expires_at__gt=timezone.now()).first()
        if not token: return Response({"error": "invalid_or_expired_code"}, status=400)
        request.user.email=token.pending_email; request.user.email_verified=True; request.user.save()
        token.used_at=timezone.now(); token.save(update_fields=("used_at",))
        return Response(profile_payload(request.user))


class PasswordResetRequestView(APIView):
    authentication_classes=[]; permission_classes=[AllowAny]
    def post(self, request):
        account=Account.objects.filter(email__iexact=str(request.data.get("email", "")).strip(), email_verified=True).first()
        data={"detail":"reset_sent_if_account_exists"}
        if account:
            raw=OneTimeToken.issue(account, OneTimeToken.PURPOSE_PASSWORD)
            send_mail("BaziKhooneh password reset", f"Reset code: {raw}", settings.DEFAULT_FROM_EMAIL,[account.email])
            if settings.DEBUG: data["development_code"]=raw
        return Response(data)


class PasswordResetConfirmView(APIView):
    authentication_classes=[]; permission_classes=[AllowAny]
    def post(self, request):
        password=str(request.data.get("new_password", ""))
        if len(password)<8: return Response({"error":"invalid_password"},status=400)
        token=OneTimeToken.objects.filter(token_hash=hash_token(str(request.data.get("code", ""))),
            purpose=OneTimeToken.PURPOSE_PASSWORD,used_at__isnull=True,expires_at__gt=timezone.now()).first()
        if not token: return Response({"error":"invalid_or_expired_code"},status=400)
        token.account.set_password(password); token.account.save(); AccountToken.objects.filter(account=token.account).delete()
        token.used_at=timezone.now(); token.save(update_fields=("used_at",)); return Response(status=204)


def account_summary(account):
    online=AccountToken.objects.filter(account=account,last_used_at__gte=timezone.now()-timedelta(minutes=5),expires_at__gt=timezone.now()).exists()
    return {"username":account.username,"display_name":account.display_name,"avatar_color":account.avatar_color,"online":online}


class SessionsView(APIView):
    permission_classes=[IsAuthenticated]
    def get(self,request):
        return Response({"results":[{"id":str(t.id),"current":t.id==request.auth.id,
            "created_at":t.created_at.isoformat(),"last_used_at":t.last_used_at.isoformat()} for t in AccountToken.objects.filter(account=request.user)]})
    def delete(self,request):
        AccountToken.objects.filter(account=request.user).exclude(id=request.auth.id).delete(); return Response(status=204)


class FriendsView(APIView):
    permission_classes=[IsAuthenticated]
    def get(self,request):
        accepted=Friendship.objects.filter(status=Friendship.STATUS_ACCEPTED).filter(
            models.Q(requester=request.user)|models.Q(recipient=request.user)).select_related("requester","recipient")
        pending=Friendship.objects.filter(recipient=request.user,status=Friendship.STATUS_PENDING).select_related("requester")
        return Response({"friends":[account_summary(f.recipient if f.requester_id==request.user.id else f.requester) for f in accepted],
            "requests":[account_summary(f.requester)|{"request_id":f.id} for f in pending]})
    def post(self,request):
        username=str(request.data.get("username","")).strip().lower(); other=Account.objects.filter(username=username).first()
        if not other or other==request.user:return Response({"error":"user_not_found"},status=404)
        reverse=Friendship.objects.filter(requester=other,recipient=request.user).first()
        if reverse: reverse.status=Friendship.STATUS_ACCEPTED; reverse.save(); return Response(status=200)
        Friendship.objects.get_or_create(requester=request.user,recipient=other); return Response(status=201)


class FriendRequestView(APIView):
    permission_classes=[IsAuthenticated]
    def post(self,request,request_id):
        relation=Friendship.objects.filter(id=request_id,recipient=request.user,status=Friendship.STATUS_PENDING).first()
        if not relation:return Response({"error":"request_not_found"},status=404)
        relation.status=Friendship.STATUS_ACCEPTED; relation.save(); return Response(status=200)


class InvitesView(APIView):
    permission_classes=[IsAuthenticated]
    def get(self,request):
        invites=GameInvite.objects.filter(recipient=request.user,accepted_at__isnull=True).select_related("sender").order_by("-created_at")[:20]
        return Response({"results":[{"id":i.id,"game_key":i.game_key,"room_code":i.room_code,"sender":account_summary(i.sender)} for i in invites]})
    def post(self,request):
        from games.models import Player, Room
        recipient=Account.objects.filter(username=str(request.data.get("username","")).strip().lower()).first()
        if not recipient:return Response({"error":"user_not_found"},status=404)
        friends=Friendship.objects.filter(status=Friendship.STATUS_ACCEPTED).filter(models.Q(requester=request.user,recipient=recipient)|models.Q(requester=recipient,recipient=request.user)).exists()
        if not friends:return Response({"error":"not_friends"},status=403)
        room=Room.create_unique(); player,raw=Player.create_with_token(room,"X",request.user)
        invite=GameInvite.objects.create(sender=request.user,recipient=recipient,game_key="tic_tac_toe",room_code=room.code)
        from games.views import player_payload
        payload=player_payload(room,player,raw); payload["invite_id"]=invite.id
        return Response(payload,status=201)
