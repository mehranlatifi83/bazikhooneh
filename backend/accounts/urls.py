from django.urls import path

from .views import (EmailVerificationConfirmView, EmailVerificationRequestView, FriendRequestView,
    FriendsView, InvitesView, LoginView, LogoutView, PasswordChangeView, PasswordResetConfirmView,
    PasswordResetRequestView, ProfileView, RegisterView, SessionsView, UsernameChangeView)


urlpatterns = [
    path("register/", RegisterView.as_view(), name="account-register"),
    path("login/", LoginView.as_view(), name="account-login"),
    path("me/", ProfileView.as_view(), name="account-profile"),
    path("logout/", LogoutView.as_view(), name="account-logout"),
    path("username/", UsernameChangeView.as_view(), name="account-username"),
    path("password/", PasswordChangeView.as_view(), name="account-password"),
    path("email/request/", EmailVerificationRequestView.as_view(), name="email-request"),
    path("email/confirm/", EmailVerificationConfirmView.as_view(), name="email-confirm"),
    path("password-reset/request/", PasswordResetRequestView.as_view(), name="password-reset-request"),
    path("password-reset/confirm/", PasswordResetConfirmView.as_view(), name="password-reset-confirm"),
    path("sessions/", SessionsView.as_view(), name="account-sessions"),
    path("friends/", FriendsView.as_view(), name="friends"),
    path("friends/requests/<int:request_id>/accept/", FriendRequestView.as_view(), name="friend-accept"),
    path("invites/", InvitesView.as_view(), name="invites"),
]
