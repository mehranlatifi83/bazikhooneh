from django.contrib import admin
from django.urls import include, path

from .health import liveness, readiness


urlpatterns = [
    path("admin/", admin.site.urls),
    path("health/", liveness),
    path("health/live/", liveness),
    path("health/ready/", readiness),
    path("api/v1/", include("games.urls")),
    path("api/v1/accounts/", include("accounts.urls")),
]
