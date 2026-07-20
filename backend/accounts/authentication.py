from django.utils import timezone
from rest_framework.authentication import BaseAuthentication
from rest_framework.exceptions import AuthenticationFailed

from .models import AccountToken, hash_token


class AccountTokenAuthentication(BaseAuthentication):
    keyword = "Bearer"

    def authenticate(self, request):
        authorization = request.headers.get("Authorization", "")
        prefix, _, raw = authorization.partition(" ")
        if prefix != self.keyword or not raw:
            return None
        try:
            token = AccountToken.objects.select_related("account").get(token_hash=hash_token(raw))
        except AccountToken.DoesNotExist as error:
            raise AuthenticationFailed("invalid_token") from error
        if token.expires_at <= timezone.now() or not token.account.is_active:
            token.delete()
            raise AuthenticationFailed("expired_token")
        token.save(update_fields=("last_used_at",))
        request.account_token = token
        return token.account, token
