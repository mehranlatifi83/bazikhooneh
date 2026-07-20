from django.db import IntegrityError, transaction
from rest_framework import status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Account, AccountToken
from .serializers import LoginSerializer, ProfileUpdateSerializer, RegisterSerializer


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
