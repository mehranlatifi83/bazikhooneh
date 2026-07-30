import logging
from datetime import timedelta

from django.conf import settings
from django.db import transaction
from django.utils import timezone

from .models import PushDelivery, PushDevice

logger = logging.getLogger("bazikhooneh.push")


def enqueue_notification(notification):
    devices = PushDevice.objects.filter(account=notification.account, active=True)
    PushDelivery.objects.bulk_create(
        [PushDelivery(notification=notification, device=device) for device in devices],
        ignore_conflicts=True,
    )


def enqueue_notifications(notifications):
    for notification in notifications:
        enqueue_notification(notification)


def _firebase_app():
    import firebase_admin
    from firebase_admin import credentials

    try:
        return firebase_admin.get_app()
    except ValueError:
        if not settings.FIREBASE_CREDENTIALS_PATH:
            raise RuntimeError("firebase_credentials_not_configured")
        return firebase_admin.initialize_app(
            credentials.Certificate(settings.FIREBASE_CREDENTIALS_PATH),
            {"projectId": settings.FIREBASE_PROJECT_ID},
        )


def deliver_batch(limit=100):
    from firebase_admin import exceptions, messaging

    _firebase_app()
    now = timezone.now()
    with transaction.atomic():
        rows = list(
            PushDelivery.objects.select_for_update(skip_locked=True)
            .select_related("notification", "device")
            .filter(status="pending", available_at__lte=now)
            .order_by("id")[:limit]
        )
        PushDelivery.objects.filter(id__in=[row.id for row in rows]).update(
            status="sending"
        )
    sent = 0
    for row in rows:
        notification, device = row.notification, row.device
        data = {
            str(key): str(value)
            for key, value in notification.data.items()
            if value is not None
        }
        data.update(
            {
                "notification_id": str(notification.id),
                "kind": notification.kind,
                "title": notification.title,
                "body": notification.body,
            }
        )
        try:
            messaging.send(
                messaging.Message(
                    token=device.token,
                    data=data,
                    android=messaging.AndroidConfig(priority="high"),
                )
            )
            row.status, row.sent_at, row.last_error = "sent", timezone.now(), ""
            sent += 1
        except exceptions.FirebaseError as error:
            row.attempts += 1
            row.last_error = str(error)[:300]
            error_code = getattr(error, "code", "")
            if error_code in ("NOT_FOUND", "INVALID_ARGUMENT", "UNREGISTERED"):
                row.status = "failed"
                PushDevice.objects.filter(id=device.id).update(active=False)
            elif row.attempts >= 5:
                row.status = "failed"
            else:
                row.status = "pending"
                row.available_at = timezone.now() + timedelta(
                    seconds=min(300, 2**row.attempts * 5)
                )
            logger.warning(
                "push_delivery_failed",
                extra={"delivery_id": row.id, "error_code": error_code},
            )
        except Exception as error:
            row.attempts += 1
            row.last_error = str(error)[:300]
            row.status = "failed" if row.attempts >= 5 else "pending"
            row.available_at = timezone.now() + timedelta(
                seconds=min(300, 2**row.attempts * 5)
            )
            logger.exception("push_delivery_failed", extra={"delivery_id": row.id})
        row.save(
            update_fields=(
                "status",
                "attempts",
                "available_at",
                "sent_at",
                "last_error",
            )
        )
    return sent, len(rows)
