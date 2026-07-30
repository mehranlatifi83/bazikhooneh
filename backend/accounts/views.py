from .auth_views import (
    EmailVerificationConfirmView as EmailVerificationConfirmView,
    EmailVerificationRequestView as EmailVerificationRequestView,
    LoginView as LoginView,
    LogoutView as LogoutView,
    PasswordChangeView as PasswordChangeView,
    PasswordResetConfirmView as PasswordResetConfirmView,
    PasswordResetRequestView as PasswordResetRequestView,
    ProfileView as ProfileView,
    RefreshSessionView as RefreshSessionView,
    RegisterView as RegisterView,
    UpgradeSessionView as UpgradeSessionView,
    UsernameChangeView as UsernameChangeView,
)
from .session_views import (
    SessionDetailView as SessionDetailView,
    SessionsView as SessionsView,
)
from .social_views import (
    BlocksView as BlocksView,
    FriendRequestView as FriendRequestView,
    FriendsView as FriendsView,
    InvitesView as InvitesView,
    NotificationsView as NotificationsView,
    PushDevicesView as PushDevicesView,
    ReportsView as ReportsView,
    UserSearchView as UserSearchView,
)
