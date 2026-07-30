import django.db.models.deletion
import uuid
from django.db import migrations, models


class Migration(migrations.Migration):
    initial = True
    dependencies = []

    operations = [
        migrations.CreateModel(
            name="Room",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4,
                        editable=False,
                        primary_key=True,
                        serialize=False,
                    ),
                ),
                ("code", models.CharField(db_index=True, max_length=6, unique=True)),
                (
                    "state",
                    models.CharField(
                        choices=[
                            ("waiting", "Waiting"),
                            ("active", "Active"),
                            ("finished", "Finished"),
                        ],
                        default="waiting",
                        max_length=16,
                    ),
                ),
                ("board", models.CharField(default=".........", max_length=9)),
                ("current_player", models.CharField(default="X", max_length=1)),
                ("phase", models.CharField(default="placement", max_length=16)),
                ("game_status", models.CharField(default="active", max_length=16)),
                ("version", models.PositiveIntegerField(default=0)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("updated_at", models.DateTimeField(auto_now=True)),
            ],
        ),
        migrations.CreateModel(
            name="Player",
            fields=[
                (
                    "id",
                    models.UUIDField(
                        default=uuid.uuid4,
                        editable=False,
                        primary_key=True,
                        serialize=False,
                    ),
                ),
                ("symbol", models.CharField(max_length=1)),
                (
                    "reconnect_token_hash",
                    models.CharField(db_index=True, max_length=64),
                ),
                ("joined_at", models.DateTimeField(auto_now_add=True)),
                ("last_seen_at", models.DateTimeField(auto_now=True)),
                (
                    "room",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="players",
                        to="games.room",
                    ),
                ),
            ],
        ),
        migrations.AddConstraint(
            model_name="player",
            constraint=models.UniqueConstraint(
                fields=("room", "symbol"), name="unique_room_symbol"
            ),
        ),
    ]
