from datetime import timedelta

from django.core.management.base import BaseCommand
from django.db.models import Q
from django.utils import timezone

from games.models import Room


class Command(BaseCommand):
    help = "Delete expired waiting, closed, and finished multiplayer rooms."

    def add_arguments(self, parser):
        parser.add_argument("--waiting-hours", type=int, default=2)
        parser.add_argument("--completed-hours", type=int, default=24)

    def handle(self, *args, **options):
        now = timezone.now()
        waiting_before = now - timedelta(hours=options["waiting_hours"])
        completed_before = now - timedelta(hours=options["completed_hours"])
        expired = Room.objects.filter(
            Q(state=Room.State.WAITING, updated_at__lt=waiting_before)
            | Q(
                state__in=(Room.State.CLOSED, Room.State.FINISHED),
                updated_at__lt=completed_before,
            )
        )
        deleted, _ = expired.delete()
        self.stdout.write(self.style.SUCCESS(f"Deleted {deleted} expired records."))
