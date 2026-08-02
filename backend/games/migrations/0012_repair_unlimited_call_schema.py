from django.db import migrations


def drop_stale_not_null_constraint(apps, schema_editor):
    if schema_editor.connection.vendor == "postgresql":
        schema_editor.execute(
            "ALTER TABLE games_communitycall "
            "ALTER COLUMN max_participants DROP NOT NULL"
        )


def restore_participant_limit_constraint(apps, schema_editor):
    if schema_editor.connection.vendor == "postgresql":
        schema_editor.execute(
            "UPDATE games_communitycall SET max_participants = 8 "
            "WHERE max_participants IS NULL"
        )
        schema_editor.execute(
            "ALTER TABLE games_communitycall "
            "ALTER COLUMN max_participants SET NOT NULL"
        )


class Migration(migrations.Migration):
    dependencies = [("games", "0011_unlimited_community_calls")]

    operations = [
        migrations.RunPython(
            drop_stale_not_null_constraint,
            restore_participant_limit_constraint,
        )
    ]
