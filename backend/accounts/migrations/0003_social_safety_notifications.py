import accounts.models
import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("accounts", "0002_account_email_account_email_verified_and_more")]
    operations = [
        migrations.AddField(
            model_name="gameinvite",
            name="expires_at",
            field=models.DateTimeField(default=accounts.models.invite_expiry),
        ),
        migrations.CreateModel(
            name="AccountNotification",
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
                ("kind", models.CharField(max_length=32)),
                ("title", models.CharField(max_length=120)),
                ("body", models.CharField(max_length=300)),
                ("data", models.JSONField(blank=True, default=dict)),
                ("read_at", models.DateTimeField(blank=True, null=True)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                (
                    "account",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="notifications",
                        to="accounts.account",
                    ),
                ),
            ],
            options={"ordering": ("-created_at",)},
        ),
        migrations.CreateModel(
            name="UserBlock",
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
                ("created_at", models.DateTimeField(auto_now_add=True)),
                (
                    "blocked",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="blocks_received",
                        to="accounts.account",
                    ),
                ),
                (
                    "blocker",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="blocks_made",
                        to="accounts.account",
                    ),
                ),
            ],
            options={
                "constraints": [
                    models.UniqueConstraint(
                        fields=("blocker", "blocked"), name="unique_user_block"
                    )
                ]
            },
        ),
        migrations.CreateModel(
            name="UserReport",
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
                ("reason", models.CharField(max_length=40)),
                ("details", models.CharField(blank=True, max_length=500)),
                ("status", models.CharField(default="open", max_length=16)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                (
                    "reported",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="reports_received",
                        to="accounts.account",
                    ),
                ),
                (
                    "reporter",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="reports_made",
                        to="accounts.account",
                    ),
                ),
            ],
        ),
    ]
