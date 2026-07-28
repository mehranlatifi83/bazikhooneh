from datetime import timedelta
from django.conf import settings
from django.contrib.auth import authenticate
from django.core.mail import send_mail
from django.db import IntegrityError, models, transaction
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import (Account, AccountNotification, AccountSession, AccountToken, Friendship, GameInvite,
                     OneTimeToken, SecurityEvent, UserBlock, UserReport, UsernameReservation, hash_token)
from .serializers import (LoginSerializer, PasswordChangeSerializer, ProfileUpdateSerializer,
                          RegisterSerializer, UsernameChangeSerializer)


def profile_payload(account):
    from django.db.models import Q
    from games.models import Match, LudoMatch

    completed = Match.objects.filter(
        Q(x_account=account) | Q(o_account=account), finished_at__isnull=False
    )
    wins = completed.filter(winner=account).count()
    ludo_completed = LudoMatch.objects.filter(participants=account, finished_at__isnull=False)
    ludo_wins = ludo_completed.filter(winner=account).count()
    by_game = [{"game_key": item["game_key"], "played": item["played"]}
               for item in completed.values("game_key").annotate(played=models.Count("id"))]
    if ludo_completed.exists():
        by_game.append({"game_key": "ludo", "played": ludo_completed.count()})
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
            "played": completed.count() + ludo_completed.count(),
            "wins": wins + ludo_wins,
            "losses": completed.exclude(winner=account).count() + ludo_completed.exclude(winner=account).count(),
            "by_game": by_game,
        },
    }


def client_ip(request):
    remote = request.META.get("REMOTE_ADDR", "")
    trusted = set(getattr(settings, "TRUSTED_PROXY_IPS", ()))
    forwarded = request.META.get("HTTP_X_FORWARDED_FOR", "").split(",")[0].strip()
    return forwarded if remote in trusted and forwarded else remote or None


def issue_session(request, account):
    # Compatibility window for already-installed clients that predate refresh
    # tokens. New clients always send app_version and receive the secure pair.
    if not str(request.data.get("app_version", "")).strip():
        return {
            "access_token": AccountToken.issue(
                account, lifetime=timedelta(days=30)),
            "token_type": "Bearer",
            "account": profile_payload(account),
            "legacy_session": True,
        }
    session, access, refresh = AccountSession.issue(
        account,
        device_name=request.data.get("device_name", ""),
        app_version=request.data.get("app_version", ""),
        ip_address=client_ip(request),
    )
    return {
        "access_token": access,
        "refresh_token": refresh,
        "token_type": "Bearer",
        "access_expires_in": 3600,
        "refresh_expires_in": 2592000,
        "session_id": str(session.id),
        "account": profile_payload(account),
    }


def log_security(request, event, account=None, **metadata):
    SecurityEvent.objects.create(
        account=account, event=event, ip_address=client_ip(request), metadata=metadata)


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
            return Response({"error": "username_taken"}, status=status.HTTP_409_CONFLICT)
        return Response(issue_session(request, account), status=status.HTTP_201_CREATED)


class LoginView(APIView):
    throttle_scope = "login"
    authentication_classes = []
    permission_classes = [AllowAny]

    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        try:
            account = Account.objects.get(username=serializer.validated_data["username"], is_active=True)
        except Account.DoesNotExist:
            django_user = authenticate(
                username=serializer.validated_data["username"],
                password=serializer.validated_data["password"],
            )
            if not django_user or not django_user.is_active:
                log_security(request,"login_failed",username=serializer.validated_data["username"])
                return Response({"error": "invalid_credentials"}, status=status.HTTP_401_UNAUTHORIZED)
            account = Account.objects.create(
                username=django_user.username.lower(),
                display_name=django_user.get_full_name() or django_user.username,
                email=django_user.email.lower(),
                email_verified=bool(django_user.email),
                password_hash=django_user.password,
            )
        if not account.check_password(serializer.validated_data["password"]):
            django_user = authenticate(
                username=serializer.validated_data["username"],
                password=serializer.validated_data["password"],
            )
            if not django_user or not django_user.is_active:
                log_security(request,"login_failed",account=account)
                return Response({"error": "invalid_credentials"}, status=status.HTTP_401_UNAUTHORIZED)
            account.password_hash = django_user.password
            account.save(update_fields=("password_hash", "updated_at"))
        log_security(request,"login_succeeded",account=account)
        return Response(issue_session(request, account))


class RefreshSessionView(APIView):
    throttle_scope = "login"
    authentication_classes = []
    permission_classes = [AllowAny]

    @transaction.atomic
    def post(self, request):
        raw = str(request.data.get("refresh_token", ""))
        session = AccountSession.objects.select_for_update().select_related("account").filter(
            refresh_token_hash=hash_token(raw)
        ).first()
        if not session:
            return Response({"error": "invalid_refresh_token"}, status=401)
        now = timezone.now()
        if session.revoked_at or session.replaced_by_id:
            session.revoke_family()
            log_security(request, "refresh_token_reuse", account=session.account,
                         session_id=str(session.id))
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
        return Response({
            "access_token": access,
            "refresh_token": refresh,
            "token_type": "Bearer",
            "access_expires_in": 3600,
            "refresh_expires_in": 2592000,
            "session_id": str(new_session.id),
            "account": profile_payload(session.account),
        })


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
        if not request.user.check_password(str(request.data.get("current_password", ""))):
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
        log_security(request,"account_deleted",account=request.user)
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
    throttle_scope = "sensitive"
    permission_classes = [IsAuthenticated]
    def post(self, request):
        serializer = PasswordChangeSerializer(data=request.data); serializer.is_valid(raise_exception=True)
        if not request.user.check_password(serializer.validated_data["current_password"]):
            return Response({"error": "invalid_password"}, status=400)
        request.user.set_password(serializer.validated_data["new_password"]); request.user.save()
        if request.auth.session_id:
            AccountSession.objects.filter(account=request.user).exclude(
                id=request.auth.session_id).update(revoked_at=timezone.now())
            AccountToken.objects.filter(account=request.user).exclude(
                session_id=request.auth.session_id).delete()
        else:
            AccountToken.objects.filter(account=request.user).exclude(id=request.auth.id).delete()
        log_security(request,"password_changed",account=request.user)
        return Response(status=204)


class EmailVerificationRequestView(APIView):
    permission_classes = [IsAuthenticated]
    def post(self, request):
        if not settings.EMAIL_DELIVERY_ENABLED:
            return Response({"error": "email_not_configured"}, status=503)
        email = str(request.data.get("email", "")).strip().lower()
        from django.core.validators import validate_email
        try: validate_email(email)
        except Exception: return Response({"error": "invalid_email"}, status=400)
        if Account.objects.filter(email__iexact=email, email_verified=True).exclude(id=request.user.id).exists():
            return Response({"error": "email_taken"}, status=409)
        raw = OneTimeToken.issue(request.user, OneTimeToken.PURPOSE_EMAIL, email)
        send_mail("BaziKhooneh email verification",
                  f"Verification code: {raw}\n\nIf this message is not in your inbox, check Spam or Junk.",
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
    throttle_scope = "sensitive"
    authentication_classes=[]; permission_classes=[AllowAny]
    def post(self, request):
        if not settings.EMAIL_DELIVERY_ENABLED:
            return Response({"error": "email_not_configured"}, status=503)
        account=Account.objects.filter(email__iexact=str(request.data.get("email", "")).strip(), email_verified=True).first()
        data={"detail":"reset_sent_if_account_exists"}
        if account:
            raw=OneTimeToken.issue(account, OneTimeToken.PURPOSE_PASSWORD)
            send_mail("BaziKhooneh password reset",
                      f"Reset code: {raw}\n\nIf this message is not in your inbox, check Spam or Junk.",
                      settings.DEFAULT_FROM_EMAIL, [account.email])
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
        token.account.set_password(password); token.account.save(); AccountToken.objects.filter(account=token.account).delete(); AccountSession.objects.filter(account=token.account).update(revoked_at=timezone.now())
        token.used_at=timezone.now(); token.save(update_fields=("used_at",)); return Response(status=204)


def account_summary(account):
    online=AccountToken.objects.filter(account=account,last_used_at__gte=timezone.now()-timedelta(minutes=5),expires_at__gt=timezone.now()).exists()
    return {"username":account.username,"display_name":account.display_name,"avatar_color":account.avatar_color,"online":online}


class SessionsView(APIView):
    permission_classes=[IsAuthenticated]
    def get(self,request):
        current_session_id = request.auth.session_id
        sessions = AccountSession.objects.filter(
            account=request.user, revoked_at__isnull=True, expires_at__gt=timezone.now())
        return Response({"results":[{"id":str(item.id),"current":item.id==current_session_id,
            "device_name":item.device_name,"app_version":item.app_version,
            "created_at":item.created_at.isoformat(),"last_used_at":item.last_used_at.isoformat()}
            for item in sessions]})
    def delete(self,request):
        current_session_id = request.auth.session_id
        AccountSession.objects.filter(account=request.user).exclude(
            id=current_session_id).update(revoked_at=timezone.now())
        AccountToken.objects.filter(account=request.user).exclude(
            session_id=current_session_id).delete()
        return Response(status=204)


class SessionDetailView(APIView):
    permission_classes = [IsAuthenticated]
    def delete(self, request, session_id):
        session = AccountSession.objects.filter(
            id=session_id, account=request.user, revoked_at__isnull=True).first()
        if not session:
            return Response({"error": "session_not_found"}, status=404)
        session.revoked_at = timezone.now()
        session.save(update_fields=("revoked_at",))
        AccountToken.objects.filter(session=session).delete()
        return Response(status=204)


class FriendsView(APIView):
    throttle_scope = "social"
    permission_classes=[IsAuthenticated]
    def get(self,request):
        accepted=Friendship.objects.filter(status=Friendship.STATUS_ACCEPTED).filter(
            models.Q(requester=request.user)|models.Q(recipient=request.user)).select_related("requester","recipient")
        pending=Friendship.objects.filter(recipient=request.user,status=Friendship.STATUS_PENDING).select_related("requester")
        outgoing=Friendship.objects.filter(requester=request.user,status=Friendship.STATUS_PENDING).select_related("recipient")
        return Response({"friends":[account_summary(f.recipient if f.requester_id==request.user.id else f.requester) for f in accepted],
            "requests":[account_summary(f.requester)|{"request_id":f.id} for f in pending],
            "outgoing":[account_summary(f.recipient)|{"request_id":f.id} for f in outgoing]})
    def post(self,request):
        username=str(request.data.get("username","")).strip().lower(); other=Account.objects.filter(username=username).first()
        if not other or other==request.user:return Response({"error":"user_not_found"},status=404)
        if UserBlock.objects.filter(models.Q(blocker=request.user, blocked=other)|models.Q(blocker=other, blocked=request.user)).exists():
            return Response({"error":"user_unavailable"},status=403)
        reverse=Friendship.objects.filter(requester=other,recipient=request.user).first()
        if reverse: reverse.status=Friendship.STATUS_ACCEPTED; reverse.save(); return Response(status=200)
        relation, created = Friendship.objects.get_or_create(requester=request.user,recipient=other)
        if created: AccountNotification.objects.create(account=other,kind="friend_request",title="Friend request",body=f"@{request.user.username} sent you a friend request",data={"request_id":relation.id})
        return Response(status=201)

    def delete(self,request):
        other=Account.objects.filter(username=str(request.data.get("username","")).strip().lower()).first()
        if not other:return Response({"error":"user_not_found"},status=404)
        Friendship.objects.filter(models.Q(requester=request.user,recipient=other)|models.Q(requester=other,recipient=request.user)).delete()
        return Response(status=204)


class FriendRequestView(APIView):
    permission_classes=[IsAuthenticated]
    def post(self,request,request_id):
        relation=Friendship.objects.filter(id=request_id,recipient=request.user,status=Friendship.STATUS_PENDING).first()
        if not relation:return Response({"error":"request_not_found"},status=404)
        relation.status=Friendship.STATUS_ACCEPTED; relation.save(); return Response(status=200)
    def delete(self,request,request_id):
        relation=Friendship.objects.filter(id=request_id).filter(models.Q(recipient=request.user)|models.Q(requester=request.user)).first()
        if not relation:return Response({"error":"request_not_found"},status=404)
        relation.delete();return Response(status=204)


class UserSearchView(APIView):
    permission_classes=[IsAuthenticated]
    def get(self,request):
        query=str(request.query_params.get("q","")).strip().lower()
        if len(query)<3:return Response({"results":[]})
        blocked=UserBlock.objects.filter(blocker=request.user).values_list("blocked_id",flat=True)
        users=Account.objects.filter(is_active=True,username__icontains=query).exclude(id=request.user.id).exclude(id__in=blocked)[:10]
        return Response({"results":[account_summary(user) for user in users]})


class InvitesView(APIView):
    throttle_scope = "social"
    permission_classes=[IsAuthenticated]
    def get(self,request):
        invites=GameInvite.objects.filter(recipient=request.user,accepted_at__isnull=True,expires_at__gt=timezone.now()).select_related("sender").order_by("-created_at")[:20]
        return Response({"results":[{"id":i.id,"game_key":i.game_key,"room_code":i.room_code,"expires_at":i.expires_at.isoformat(),"sender":account_summary(i.sender)} for i in invites]})
    def post(self,request):
        from games.models import CommunityRoom
        from games.community import room_payload
        recipient=Account.objects.filter(username=str(request.data.get("username","")).strip().lower()).first()
        if not recipient:return Response({"error":"user_not_found"},status=404)
        friends=Friendship.objects.filter(status=Friendship.STATUS_ACCEPTED).filter(models.Q(requester=request.user,recipient=recipient)|models.Q(requester=recipient,recipient=request.user)).exists()
        if not friends:return Response({"error":"not_friends"},status=403)
        game_key=str(request.data.get("game_key","three_piece_tic_tac_toe"))
        if game_key not in ("three_piece_tic_tac_toe","ludo"):
            return Response({"error":"unsupported_game"},status=400)
        room=CommunityRoom.create_unique(request.user,f"{request.user.display_name}'s game room")
        invite=GameInvite.objects.create(sender=request.user,recipient=recipient,game_key=game_key,room_code=room.code)
        AccountNotification.objects.create(account=recipient,kind="game_invite",title="Game room invitation",body=f"@{request.user.username} invited you to a shared room",data={"invite_id":invite.id,"game_key":invite.game_key,"room_code":room.code})
        return Response({"invite_id":invite.id,"room":room_payload(room,request.user)},status=201)


class NotificationsView(APIView):
    permission_classes=[IsAuthenticated]
    def get(self,request):
        values=request.user.notifications.all()[:50]
        return Response({"unread":request.user.notifications.filter(read_at__isnull=True).count(),"results":[{"id":n.id,"kind":n.kind,"title":n.title,"body":n.body,"data":n.data,"read":n.read_at is not None,"created_at":n.created_at.isoformat()} for n in values]})
    def post(self,request):
        request.user.notifications.filter(read_at__isnull=True).update(read_at=timezone.now());return Response(status=204)


class BlocksView(APIView):
    permission_classes=[IsAuthenticated]
    def get(self,request):
        return Response({"results":[account_summary(item.blocked) for item in UserBlock.objects.filter(blocker=request.user).select_related("blocked")]})
    def post(self,request):
        other=Account.objects.filter(username=str(request.data.get("username","")).strip().lower()).first()
        if not other or other==request.user:return Response({"error":"user_not_found"},status=404)
        UserBlock.objects.get_or_create(blocker=request.user,blocked=other)
        Friendship.objects.filter(models.Q(requester=request.user,recipient=other)|models.Q(requester=other,recipient=request.user)).delete()
        return Response(status=201)
    def delete(self,request):
        UserBlock.objects.filter(blocker=request.user,blocked__username=str(request.data.get("username","")).strip().lower()).delete();return Response(status=204)


class ReportsView(APIView):
    throttle_scope = "social"
    permission_classes=[IsAuthenticated]
    def post(self,request):
        other=Account.objects.filter(username=str(request.data.get("username","")).strip().lower()).first()
        reason=str(request.data.get("reason","")).strip()[:40];details=str(request.data.get("details","")).strip()[:500]
        if not other or other==request.user:return Response({"error":"user_not_found"},status=404)
        if not reason:return Response({"error":"reason_required"},status=400)
        UserReport.objects.create(reporter=request.user,reported=other,reason=reason,details=details);return Response(status=201)
