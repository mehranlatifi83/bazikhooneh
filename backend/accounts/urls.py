from django.urls import path

from .views import LoginView, LogoutView, ProfileView, RegisterView


urlpatterns = [
    path("register/", RegisterView.as_view(), name="account-register"),
    path("login/", LoginView.as_view(), name="account-login"),
    path("me/", ProfileView.as_view(), name="account-profile"),
    path("logout/", LogoutView.as_view(), name="account-logout"),
]
