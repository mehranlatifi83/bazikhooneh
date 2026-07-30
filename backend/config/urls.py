from django.contrib import admin
from django.urls import include, path

from .health import liveness, readiness
from .public import asset_links, room_link


urlpatterns = [
    path("admin/", admin.site.urls),
    path("health/", liveness),
    path("health/live/", liveness),
    path("health/ready/", readiness),
    path(".well-known/assetlinks.json", asset_links),
    path("rooms/<str:code>", room_link, name="room-link"),
    path("api/v1/", include("games.urls")),
    path("api/v1/accounts/", include("accounts.urls")),
]
