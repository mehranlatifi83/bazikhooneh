import time

from django.core.management.base import BaseCommand

from accounts.push import deliver_batch


class Command(BaseCommand):
    help = "Deliver queued Firebase push notifications"

    def add_arguments(self, parser):
        parser.add_argument("--once", action="store_true")
        parser.add_argument("--interval", type=float, default=1.0)

    def handle(self, *args, **options):
        while True:
            _, claimed = deliver_batch()
            if options["once"]:
                return
            if not claimed:
                time.sleep(max(0.2, options["interval"]))
