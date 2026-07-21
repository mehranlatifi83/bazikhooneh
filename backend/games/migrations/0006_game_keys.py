from django.db import migrations, models
class Migration(migrations.Migration):
    dependencies=[("games","0005_match")]
    operations=[
        migrations.AddField(model_name="room",name="game_key",field=models.CharField(db_index=True,default="three_piece_tic_tac_toe",max_length=40)),
        migrations.AddField(model_name="match",name="game_key",field=models.CharField(db_index=True,default="three_piece_tic_tac_toe",max_length=40)),
    ]
