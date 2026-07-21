from django.urls import path

from .views import (EmailVerificationConfirmView, EmailVerificationRequestView, FriendRequestView,
    FriendsView, InvitesView, LoginView, LogoutView, PasswordChangeView, PasswordResetConfirmView,
    PasswordResetRequestView, ProfileView, RegisterView, SessionsView, UsernameChangeView,
    UserSearchView, NotificationsView, BlocksView, ReportsView)


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
    path("friends/requests/<int:request_id>/", FriendRequestView.as_view(), name="friend-request"),
    path("users/search/", UserSearchView.as_view(), name="user-search"),
    path("invites/", InvitesView.as_view(), name="invites"),
    path("notifications/", NotificationsView.as_view(), name="notifications"),
    path("blocks/", BlocksView.as_view(), name="blocks"),
    path("reports/", ReportsView.as_view(), name="reports"),
]
