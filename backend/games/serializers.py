from rest_framework import serializers


class JoinRoomSerializer(serializers.Serializer):
    code = serializers.CharField(min_length=6, max_length=6)

    def validate_code(self, value):
        return value.strip().upper()
