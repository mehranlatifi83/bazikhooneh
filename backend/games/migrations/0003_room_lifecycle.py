from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("games", "0002_room_rematch_votes")]

    operations = [
        migrations.AddField(
            model_name="player",
            name="is_active",
            field=models.BooleanField(default=True),
        ),
        migrations.AddField(
            model_name="room",
            name="next_starter",
            field=models.CharField(default="O", max_length=1),
        ),
        migrations.AddField(
            model_name="room",
            name="outcome_reason",
            field=models.CharField(blank=True, default="", max_length=24),
        ),
        migrations.AlterField(
            model_name="room",
            name="state",
            field=models.CharField(
                choices=[
                    ("waiting", "Waiting"),
                    ("active", "Active"),
                    ("finished", "Finished"),
                    ("closed", "Closed"),
                ],
                default="waiting",
                max_length=16,
            ),
        ),
    ]
