import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ("accounts", "0001_initial"),
        ("games", "0003_room_lifecycle"),
    ]

    operations = [
        migrations.AddField(
            model_name="player",
            name="account",
            field=models.ForeignKey(
                null=True,
                on_delete=django.db.models.deletion.PROTECT,
                related_name="room_players",
                to="accounts.account",
            ),
        ),
        migrations.AddConstraint(
            model_name="player",
            constraint=models.UniqueConstraint(
                fields=("room", "account"), name="unique_room_account"
            ),
        ),
    ]
