import re

from rest_framework import serializers

from .models import Account


USERNAME_PATTERN = re.compile(r"^[a-zA-Z0-9_]{3,30}$")
AVATAR_COLORS = {"#2E7D32", "#C62828", "#1565C0", "#6A1B9A", "#EF6C00", "#455A64"}


class RegisterSerializer(serializers.Serializer):
    username = serializers.CharField(min_length=3, max_length=30)
    display_name = serializers.CharField(min_length=1, max_length=40)
    password = serializers.CharField(min_length=8, max_length=128, write_only=True)

    def validate_username(self, value):
        value = value.strip().lower()
        if not USERNAME_PATTERN.fullmatch(value):
            raise serializers.ValidationError("invalid_username")
        if Account.objects.filter(username=value).exists():
            raise serializers.ValidationError("username_taken")
        return value

    def validate_display_name(self, value):
        return value.strip()


class LoginSerializer(serializers.Serializer):
    username = serializers.CharField(max_length=30)
    password = serializers.CharField(max_length=128, write_only=True)

    def validate_username(self, value):
        return value.strip().lower()


class ProfileUpdateSerializer(serializers.Serializer):
    display_name = serializers.CharField(min_length=1, max_length=40, required=False)
    avatar_color = serializers.CharField(max_length=7, required=False)

    def validate_display_name(self, value):
        return value.strip()

    def validate_avatar_color(self, value):
        value = value.upper()
        if value not in AVATAR_COLORS:
            raise serializers.ValidationError("invalid_avatar_color")
        return value
