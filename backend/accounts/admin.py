from django.contrib import admin

from .models import Account, AccountToken


@admin.register(Account)
class AccountAdmin(admin.ModelAdmin):
    list_display = ("username", "display_name", "is_active", "created_at")
    search_fields = ("username", "display_name")
    exclude = ("password_hash",)


@admin.register(AccountToken)
class AccountTokenAdmin(admin.ModelAdmin):
    list_display = ("account", "created_at", "expires_at", "last_used_at")
    readonly_fields = ("token_hash",)
