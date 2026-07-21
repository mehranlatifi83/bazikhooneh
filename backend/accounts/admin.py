from django.contrib import admin

from .models import Account, AccountToken, Friendship, GameInvite, OneTimeToken, UsernameReservation


@admin.register(Account)
class AccountAdmin(admin.ModelAdmin):
    list_display = ("username", "display_name", "email", "email_verified", "is_active", "created_at")
    search_fields = ("username", "display_name", "email")
    exclude = ("password_hash",)


@admin.register(AccountToken)
class AccountTokenAdmin(admin.ModelAdmin):
    list_display = ("account", "created_at", "expires_at", "last_used_at")
    readonly_fields = ("token_hash",)

admin.site.register(UsernameReservation)
admin.site.register(OneTimeToken)
admin.site.register(Friendship)
admin.site.register(GameInvite)
