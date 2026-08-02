from django.db import migrations


def enforce_unlimited_call_schema(apps, schema_editor):
    if schema_editor.connection.vendor == "postgresql":
        schema_editor.execute(
            "ALTER TABLE games_communitycall "
            "ALTER COLUMN max_participants DROP NOT NULL"
        )


def restore_call_limit_schema(apps, schema_editor):
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
    dependencies = [("games", "0012_repair_unlimited_call_schema")]

    operations = [
        migrations.RunPython(
            enforce_unlimited_call_schema,
            restore_call_limit_schema,
        )
    ]
