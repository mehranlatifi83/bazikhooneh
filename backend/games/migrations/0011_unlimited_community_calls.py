from django.db import migrations, models


def remove_existing_limits(apps, schema_editor):
    apps.get_model("games", "CommunityCall").objects.update(
        max_participants=None
    )


class Migration(migrations.Migration):
    dependencies = [("games", "0010_matchmakingticket_game_credentials")]

    operations = [
        migrations.AlterField(
            model_name="communitycall",
            name="max_participants",
            field=models.PositiveSmallIntegerField(
                blank=True, default=None, null=True
            ),
        ),
        migrations.RunPython(remove_existing_limits, migrations.RunPython.noop),
    ]
