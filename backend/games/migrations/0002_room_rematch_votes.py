from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("games", "0001_initial")]

    operations = [
        migrations.AddField(
            model_name="room",
            name="rematch_o",
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name="room",
            name="rematch_x",
            field=models.BooleanField(default=False),
        ),
    ]
