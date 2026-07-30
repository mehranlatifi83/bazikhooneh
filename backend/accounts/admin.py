from django.contrib import admin
from django.contrib.auth.admin import UserAdmin

from .models import (
    Account,
    AccountNotification,
    AccountToken,
    Friendship,
    GameInvite,
    OneTimeToken,
    PushDelivery,
    PushDevice,
    SecurityEvent,
    UserBlock,
    UserReport,
    UsernameReservation,
)


@admin.register(Account)
class AccountAdmin(UserAdmin):
    ordering = ("username",)
    list_display = (
        "username",
        "display_name",
        "email",
        "email_verified",
        "is_active",
        "is_staff",
        "created_at",
    )
    search_fields = ("username", "display_name", "email")
    readonly_fields = ("created_at", "updated_at", "last_login")
    fieldsets = (
        (None, {"fields": ("username", "password")}),
        (
            "Profile",
            {
                "fields": (
                    "display_name",
                    "email",
                    "email_verified",
                    "avatar_color",
                    "username_changed_at",
                )
            },
        ),
        (
            "Permissions",
            {
                "fields": (
                    "is_active",
                    "is_staff",
                    "is_superuser",
                    "groups",
                    "user_permissions",
                )
            },
        ),
        ("Dates", {"fields": ("last_login", "created_at", "updated_at")}),
    )
    add_fieldsets = (
        (
            None,
            {
                "classes": ("wide",),
                "fields": (
                    "username",
                    "email",
                    "display_name",
                    "password1",
                    "password2",
                    "is_active",
                    "is_staff",
                    "is_superuser",
                ),
            },
        ),
    )


@admin.register(AccountToken)
class AccountTokenAdmin(admin.ModelAdmin):
    list_display = ("account", "created_at", "expires_at", "last_used_at")
    readonly_fields = ("token_hash",)


admin.site.register(UsernameReservation)
admin.site.register(OneTimeToken)
admin.site.register(Friendship)
admin.site.register(GameInvite)
admin.site.register(AccountNotification)
admin.site.register(PushDevice)
admin.site.register(PushDelivery)
admin.site.register(UserBlock)
admin.site.register(UserReport)
admin.site.register(SecurityEvent)
