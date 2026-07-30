import django.db.models.deletion
import django.utils.timezone
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("accounts", "0007_account_as_auth_user")]
    operations = [
        migrations.CreateModel(
            name="PushDevice",
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
                ("token", models.CharField(max_length=512, unique=True)),
                ("platform", models.CharField(default="android", max_length=16)),
                ("app_version", models.CharField(blank=True, max_length=32)),
                ("locale", models.CharField(blank=True, max_length=16)),
                ("active", models.BooleanField(default=True)),
                ("last_seen_at", models.DateTimeField(auto_now=True)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                (
                    "account",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="push_devices",
                        to="accounts.account",
                    ),
                ),
            ],
        ),
        migrations.CreateModel(
            name="PushDelivery",
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
                ("status", models.CharField(default="pending", max_length=16)),
                ("attempts", models.PositiveSmallIntegerField(default=0)),
                (
                    "available_at",
                    models.DateTimeField(default=django.utils.timezone.now),
                ),
                ("sent_at", models.DateTimeField(blank=True, null=True)),
                ("last_error", models.CharField(blank=True, max_length=300)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                (
                    "device",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="deliveries",
                        to="accounts.pushdevice",
                    ),
                ),
                (
                    "notification",
                    models.ForeignKey(
                        on_delete=django.db.models.deletion.CASCADE,
                        related_name="push_deliveries",
                        to="accounts.accountnotification",
                    ),
                ),
            ],
        ),
        migrations.AddIndex(
            model_name="pushdevice",
            index=models.Index(
                fields=["account", "active"], name="accounts_pu_account_0265f6_idx"
            ),
        ),
        migrations.AddIndex(
            model_name="pushdelivery",
            index=models.Index(
                fields=["status", "available_at"], name="accounts_pu_status_bc54fb_idx"
            ),
        ),
        migrations.AddConstraint(
            model_name="pushdelivery",
            constraint=models.UniqueConstraint(
                fields=("notification", "device"), name="unique_push_delivery"
            ),
        ),
    ]
