import uuid
import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("games", "0006_game_keys"), ("accounts", "0004_security_event")]
    operations = [
        migrations.CreateModel(
            name="LudoRoom",
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
                ("state", models.CharField(default="waiting", max_length=16)),
                ("game_state", models.JSONField(default=dict)),
                ("version", models.PositiveIntegerField(default=0)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("updated_at", models.DateTimeField(auto_now=True)),
                (
                    "host",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="hosted_ludo_rooms",
                        to="accounts.account",
                    ),
                ),
            ],
        ),
        migrations.CreateModel(
            name="LudoSeat",
            fields=[
                (
                    "id",
                    models.BigAutoField(
                        auto_created=True,
                        primary_key=True,
                        serialize=False,
                        verbose_name="ID",
                    ),
                ),
                ("color", models.PositiveSmallIntegerField()),
                ("is_bot", models.BooleanField(default=False)),
                (
                    "reconnect_token_hash",
                    models.CharField(blank=True, db_index=True, max_length=64),
                ),
                ("active", models.BooleanField(default=True)),
                ("joined_at", models.DateTimeField(auto_now_add=True)),
                ("last_seen_at", models.DateTimeField(auto_now=True)),
                (
                    "account",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="ludo_seats",
                        to="accounts.account",
                    ),
                ),
                (
                    "room",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="seats",
                        to="games.ludoroom",
                    ),
                ),
            ],
            options={
                "constraints": [
                    models.UniqueConstraint(
                        fields=("room", "color"), name="unique_ludo_color"
                    ),
                    models.UniqueConstraint(
                        condition=models.Q(("account__isnull", False)),
                        fields=("room", "account"),
                        name="unique_ludo_account",
                    ),
                ]
            },
        ),
        migrations.CreateModel(
            name="LudoMatch",
            fields=[
                (
                    "id",
                    models.BigAutoField(
                        auto_created=True,
                        primary_key=True,
                        serialize=False,
                        verbose_name="ID",
                    ),
                ),
                ("started_at", models.DateTimeField(auto_now_add=True)),
                ("finished_at", models.DateTimeField(blank=True, null=True)),
                (
                    "participants",
                    models.ManyToManyField(
                        related_name="ludo_matches", to="accounts.account"
                    ),
                ),
                (
                    "room",
                    models.OneToOneField(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="match",
                        to="games.ludoroom",
                    ),
                ),
                (
                    "winner",
                    models.ForeignKey(
                        blank=True,
                        null=True,
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="ludo_matches_won",
                        to="accounts.account",
                    ),
                ),
            ],
        ),
    ]
