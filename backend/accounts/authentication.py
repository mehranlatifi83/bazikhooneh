from datetime import timedelta

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
            token = AccountToken.objects.select_related("account").get(
                token_hash=hash_token(raw)
            )
        except AccountToken.DoesNotExist as error:
            raise AuthenticationFailed("invalid_token") from error
        if token.expires_at <= timezone.now() or not token.account.is_active:
            token.delete()
            raise AuthenticationFailed("expired_token")
        # Avoid turning every authenticated GET into a database write.
        if token.last_used_at <= timezone.now() - timedelta(minutes=15):
            now = timezone.now()
            AccountToken.objects.filter(pk=token.pk).update(last_used_at=now)
            if token.session_id:
                token.session.__class__.objects.filter(pk=token.session_id).update(
                    last_used_at=now
                )
        request.account_token = token
        return token.account, token
