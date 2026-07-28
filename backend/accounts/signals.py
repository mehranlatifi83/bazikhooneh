from django.db import transaction
from django.db.models.signals import post_save
from django.dispatch import receiver

from .models import AccountNotification
from .push import enqueue_notification


@receiver(post_save, sender=AccountNotification)
def queue_push(sender, instance, created, **kwargs):
    if created:
        transaction.on_commit(lambda: enqueue_notification(instance))
