from datetime import timedelta
from django.conf import settings
from django.core.mail import send_mail
from django.db import IntegrityError, transaction
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import (
    Account,
    AccountSession,
    AccountToken,
    OneTimeToken,
    UsernameReservation,
    hash_token,
)
from .serializers import (
    LoginSerializer,
    PasswordChangeSerializer,
    ProfileUpdateSerializer,
    RegisterSerializer,
    UsernameChangeSerializer,
)


from .view_helpers import (
    client_ip,
    issue_session,
    log_security,
    profile_payload,
)


class RegisterView(APIView):
    throttle_scope = "login"
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
            return Response(
                {"error": "username_taken"}, status=status.HTTP_409_CONFLICT
            )
        return Response(issue_session(request, account), status=status.HTTP_201_CREATED)


class LoginView(APIView):
    throttle_scope = "login"
    authentication_classes = []
    permission_classes = [AllowAny]

    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        try:
            account = Account.objects.get(
                username=serializer.validated_data["username"], is_active=True
            )
        except Account.DoesNotExist:
            log_security(
                request, "login_failed", username=serializer.validated_data["username"]
            )
            return Response(
                {"error": "invalid_credentials"}, status=status.HTTP_401_UNAUTHORIZED
            )
        if not account.check_password(serializer.validated_data["password"]):
            log_security(request, "login_failed", account=account)
            return Response(
                {"error": "invalid_credentials"}, status=status.HTTP_401_UNAUTHORIZED
            )
        log_security(request, "login_succeeded", account=account)
        return Response(issue_session(request, account))


class RefreshSessionView(APIView):
    throttle_scope = "login"
    authentication_classes = []
    permission_classes = [AllowAny]

    @transaction.atomic
    def post(self, request):
        raw = str(request.data.get("refresh_token", ""))
        session = (
            AccountSession.objects.select_for_update()
            .select_related("account")
            .filter(refresh_token_hash=hash_token(raw))
            .first()
        )
        if not session:
            return Response({"error": "invalid_refresh_token"}, status=401)
        now = timezone.now()
        if session.revoked_at or session.replaced_by_id:
            session.revoke_family()
            log_security(
                request,
                "refresh_token_reuse",
                account=session.account,
                session_id=str(session.id),
            )
            return Response({"error": "refresh_token_reused"}, status=401)
        if session.expires_at <= now or not session.account.is_active:
            session.revoke_family()
            return Response({"error": "expired_refresh_token"}, status=401)
        new_session, access, refresh = AccountSession.issue(
            session.account,
            device_name=session.device_name,
            app_version=request.data.get("app_version", session.app_version),
            ip_address=client_ip(request),
            family_id=session.family_id,
        )
        session.revoked_at = now
        session.replaced_by = new_session
        session.last_used_at = now
        session.save(update_fields=("revoked_at", "replaced_by", "last_used_at"))
        AccountToken.objects.filter(session=session).delete()
        return Response(
            {
                "access_token": access,
                "refresh_token": refresh,
                "token_type": "Bearer",
                "access_expires_in": 3600,
                "refresh_expires_in": 2592000,
                "session_id": str(new_session.id),
                "account": profile_payload(session.account),
            }
        )


class UpgradeSessionView(APIView):
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request):
        payload = issue_session(request, request.user)
        old_token_id = request.auth.id
        AccountToken.objects.filter(id=old_token_id).delete()
        return Response(payload)


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

    def delete(self, request):
        if not request.user.check_password(
            str(request.data.get("current_password", ""))
        ):
            return Response({"error": "invalid_password"}, status=400)
        confirmation = str(request.data.get("confirmation", "")).strip().lower()
        if confirmation != request.user.username:
            return Response({"error": "confirmation_mismatch"}, status=400)
        request.user.is_active = False
        request.user.username = f"deleted_{str(request.user.id).replace('-', '')[:20]}"
        request.user.display_name = "Deleted user"
        request.user.email = ""
        request.user.email_verified = False
        request.user.save()
        AccountToken.objects.filter(account=request.user).delete()
        AccountSession.objects.filter(account=request.user).delete()
        log_security(request, "account_deleted", account=request.user)
        return Response(status=204)


class LogoutView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        if request.auth.session_id:
            session = request.auth.session
            session.revoked_at = timezone.now()
            session.save(update_fields=("revoked_at",))
            AccountToken.objects.filter(session=session).delete()
        else:
            request.auth.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


class UsernameChangeView(APIView):
    throttle_scope = "sensitive"
    permission_classes = [IsAuthenticated]

    @transaction.atomic
    def post(self, request):
        serializer = UsernameChangeSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        account = request.user
        if not account.check_password(serializer.validated_data["current_password"]):
            return Response({"error": "invalid_password"}, status=400)
        if (
            account.username_changed_at
            and account.username_changed_at + timedelta(days=30) > timezone.now()
        ):
            return Response(
                {
                    "error": "username_cooldown",
                    "next_change_at": (
                        account.username_changed_at + timedelta(days=30)
                    ).isoformat(),
                },
                status=429,
            )
        new_username = serializer.validated_data["username"]
        if new_username == account.username:
            return Response({"error": "username_unchanged"}, status=400)
        if (
            Account.objects.filter(username=new_username).exists()
            or UsernameReservation.objects.filter(
                username=new_username, expires_at__gt=timezone.now()
            )
            .exclude(account=account)
            .exists()
        ):
            return Response({"error": "username_taken"}, status=409)
        UsernameReservation.objects.update_or_create(
            username=account.username,
            defaults={
                "account": account,
                "expires_at": timezone.now() + timedelta(days=90),
            },
        )
        account.username = new_username
        account.username_changed_at = timezone.now()
        account.save(update_fields=("username", "username_changed_at", "updated_at"))
        return Response(profile_payload(account))


class PasswordChangeView(APIView):
    throttle_scope = "sensitive"
    permission_classes = [IsAuthenticated]

    def post(self, request):
        serializer = PasswordChangeSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        if not request.user.check_password(
            serializer.validated_data["current_password"]
        ):
            return Response({"error": "invalid_password"}, status=400)
        request.user.set_password(serializer.validated_data["new_password"])
        request.user.save()
        if request.auth.session_id:
            AccountSession.objects.filter(account=request.user).exclude(
                id=request.auth.session_id
            ).update(revoked_at=timezone.now())
            AccountToken.objects.filter(account=request.user).exclude(
                session_id=request.auth.session_id
            ).delete()
        else:
            AccountToken.objects.filter(account=request.user).exclude(
                id=request.auth.id
            ).delete()
        log_security(request, "password_changed", account=request.user)
        return Response(status=204)


class EmailVerificationRequestView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        if not settings.EMAIL_DELIVERY_ENABLED:
            return Response({"error": "email_not_configured"}, status=503)
        email = str(request.data.get("email", "")).strip().lower()
        from django.core.validators import validate_email

        try:
            validate_email(email)
        except Exception:
            return Response({"error": "invalid_email"}, status=400)
        if (
            Account.objects.filter(email__iexact=email, email_verified=True)
            .exclude(id=request.user.id)
            .exists()
        ):
            return Response({"error": "email_taken"}, status=409)
        raw = OneTimeToken.issue(request.user, OneTimeToken.PURPOSE_EMAIL, email)
        send_mail(
            "BaziKhooneh email verification",
            f"Verification code: {raw}\n\nIf this message is not in your inbox, check Spam or Junk.",
            settings.DEFAULT_FROM_EMAIL,
            [email],
        )
        data = {"detail": "verification_sent"}
        if settings.DEBUG:
            data["development_code"] = raw
        return Response(data)


class EmailVerificationConfirmView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        token = OneTimeToken.objects.filter(
            token_hash=hash_token(str(request.data.get("code", ""))),
            purpose=OneTimeToken.PURPOSE_EMAIL,
            account=request.user,
            used_at__isnull=True,
            expires_at__gt=timezone.now(),
        ).first()
        if not token:
            return Response({"error": "invalid_or_expired_code"}, status=400)
        request.user.email = token.pending_email
        request.user.email_verified = True
        request.user.save()
        token.used_at = timezone.now()
        token.save(update_fields=("used_at",))
        return Response(profile_payload(request.user))


class PasswordResetRequestView(APIView):
    throttle_scope = "sensitive"
    authentication_classes = []
    permission_classes = [AllowAny]

    def post(self, request):
        if not settings.EMAIL_DELIVERY_ENABLED:
            return Response({"error": "email_not_configured"}, status=503)
        account = Account.objects.filter(
            email__iexact=str(request.data.get("email", "")).strip(),
            email_verified=True,
        ).first()
        data = {"detail": "reset_sent_if_account_exists"}
        if account:
            raw = OneTimeToken.issue(account, OneTimeToken.PURPOSE_PASSWORD)
            send_mail(
                "BaziKhooneh password reset",
                f"Reset code: {raw}\n\nIf this message is not in your inbox, check Spam or Junk.",
                settings.DEFAULT_FROM_EMAIL,
                [account.email],
            )
            if settings.DEBUG:
                data["development_code"] = raw
        return Response(data)


class PasswordResetConfirmView(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]

    def post(self, request):
        password = str(request.data.get("new_password", ""))
        if len(password) < 8:
            return Response({"error": "invalid_password"}, status=400)
        token = OneTimeToken.objects.filter(
            token_hash=hash_token(str(request.data.get("code", ""))),
            purpose=OneTimeToken.PURPOSE_PASSWORD,
            used_at__isnull=True,
            expires_at__gt=timezone.now(),
        ).first()
        if not token:
            return Response({"error": "invalid_or_expired_code"}, status=400)
        token.account.set_password(password)
        token.account.save()
        AccountToken.objects.filter(account=token.account).delete()
        AccountSession.objects.filter(account=token.account).update(
            revoked_at=timezone.now()
        )
        token.used_at = timezone.now()
        token.save(update_fields=("used_at",))
        return Response(status=204)
