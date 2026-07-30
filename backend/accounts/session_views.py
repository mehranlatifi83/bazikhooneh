from django.utils import timezone
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import (
    AccountSession,
    AccountToken,
)


class SessionsView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        current_session_id = request.auth.session_id
        sessions = AccountSession.objects.filter(
            account=request.user, revoked_at__isnull=True, expires_at__gt=timezone.now()
        )
        return Response(
            {
                "results": [
                    {
                        "id": str(item.id),
                        "current": item.id == current_session_id,
                        "device_name": item.device_name,
                        "app_version": item.app_version,
                        "created_at": item.created_at.isoformat(),
                        "last_used_at": item.last_used_at.isoformat(),
                    }
                    for item in sessions
                ]
            }
        )

    def delete(self, request):
        current_session_id = request.auth.session_id
        AccountSession.objects.filter(account=request.user).exclude(
            id=current_session_id
        ).update(revoked_at=timezone.now())
        AccountToken.objects.filter(account=request.user).exclude(
            session_id=current_session_id
        ).delete()
        return Response(status=204)


class SessionDetailView(APIView):
    permission_classes = [IsAuthenticated]

    def delete(self, request, session_id):
        session = AccountSession.objects.filter(
            id=session_id, account=request.user, revoked_at__isnull=True
        ).first()
        if not session:
            return Response({"error": "session_not_found"}, status=404)
        session.revoked_at = timezone.now()
        session.save(update_fields=("revoked_at",))
        AccountToken.objects.filter(session=session).delete()
        return Response(status=204)
