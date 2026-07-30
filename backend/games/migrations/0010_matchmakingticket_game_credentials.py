from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("games", "0009_matchmakingticket")]
    operations = [
        migrations.AddField(
            model_name="matchmakingticket",
            name="game_credentials",
            field=models.JSONField(blank=True, default=dict),
        )
    ]
