import uuid

import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("accounts", "0001_initial"), ("games", "0004_player_account")]

    operations = [
        migrations.CreateModel(
            name="Match",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("round_number", models.PositiveIntegerField(default=1)),
                ("outcome", models.CharField(blank=True, default="", max_length=24)),
                ("started_at", models.DateTimeField(auto_now_add=True)),
                ("finished_at", models.DateTimeField(blank=True, null=True)),
                ("o_account", models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name="matches_as_o", to="accounts.account")),
                ("room", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="matches", to="games.room")),
                ("winner", models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.PROTECT, related_name="matches_won", to="accounts.account")),
                ("x_account", models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, related_name="matches_as_x", to="accounts.account")),
            ],
            options={"ordering": ("-started_at",)},
        ),
        migrations.AddConstraint(
            model_name="match",
            constraint=models.UniqueConstraint(fields=("room", "round_number"), name="unique_room_round"),
        ),
    ]
