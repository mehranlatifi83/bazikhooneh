from datetime import timedelta
from io import StringIO

from django.core.management import call_command
from django.test import TestCase
from django.utils import timezone

from games.models import Room


class CleanupRoomsTests(TestCase):
    def test_expired_waiting_and_closed_rooms_are_deleted(self):
        waiting = Room.create_unique()
        closed = Room.create_unique()
        closed.state = Room.State.CLOSED
        closed.save()
        active = Room.create_unique()
        active.state = Room.State.ACTIVE
        active.save()
        old = timezone.now() - timedelta(hours=48)
        Room.objects.filter(id__in=(waiting.id, closed.id, active.id)).update(
            updated_at=old
        )

        call_command("cleanup_rooms", stdout=StringIO())

        self.assertFalse(Room.objects.filter(id=waiting.id).exists())
        self.assertFalse(Room.objects.filter(id=closed.id).exists())
        self.assertTrue(Room.objects.filter(id=active.id).exists())
