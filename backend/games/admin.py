from django.contrib import admin

from .models import Player, Room


class PlayerInline(admin.TabularInline):
    model = Player
    extra = 0
    readonly_fields = ("id", "symbol", "joined_at", "last_seen_at")
    exclude = ("reconnect_token_hash",)


@admin.register(Room)
class RoomAdmin(admin.ModelAdmin):
    list_display = ("code", "state", "current_player", "phase", "game_status", "updated_at")
    search_fields = ("code",)
    list_filter = ("state", "phase", "game_status")
    inlines = (PlayerInline,)
