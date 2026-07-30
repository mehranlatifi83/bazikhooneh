import json
from unittest.mock import patch

from django.test import TestCase, override_settings


class HealthAndObservabilityTests(TestCase):
    def test_liveness_is_lightweight_and_returns_request_id(self):
        response = self.client.get(
            "/health/live/", HTTP_X_REQUEST_ID="client-request-123")
        self.assertEqual(200, response.status_code)
        self.assertEqual({"status": "ok"}, response.json())
        self.assertEqual("client-request-123", response["X-Request-ID"])

    def test_invalid_request_id_is_replaced(self):
        response = self.client.get(
            "/health/", HTTP_X_REQUEST_ID="invalid request id")
        self.assertEqual(200, response.status_code)
        self.assertNotEqual("invalid request id", response["X-Request-ID"])
        self.assertEqual(32, len(response["X-Request-ID"]))

    @patch("config.health.turn_ready", return_value=True)
    @patch("config.health.channel_layer_ready", return_value=True)
    @patch("config.health.cache_ready", return_value=True)
    @patch("config.health.database_ready", return_value=True)
    def test_readiness_reports_dependency_state(self, *_checks):
        response = self.client.get("/health/ready/")
        self.assertEqual(200, response.status_code)
        self.assertEqual("ok", response.json()["status"])
        self.assertTrue(all(response.json()["checks"].values()))

    @patch("config.health.turn_ready", return_value=True)
    @patch("config.health.channel_layer_ready", return_value=True)
    @patch("config.health.cache_ready", return_value=False)
    @patch("config.health.database_ready", return_value=True)
    def test_readiness_fails_when_dependency_is_unavailable(self, *_checks):
        response = self.client.get("/health/ready/")
        self.assertEqual(503, response.status_code)
        self.assertEqual("degraded", response.json()["status"])
        self.assertFalse(response.json()["checks"]["cache"])

    @patch("config.health.turn_ready", return_value=False)
    @patch("config.health.channel_layer_ready", return_value=True)
    @patch("config.health.cache_ready", return_value=True)
    @patch("config.health.database_ready", return_value=True)
    def test_readiness_reports_optional_turn_degradation(self, *_checks):
        response = self.client.get("/health/ready/")
        self.assertEqual(200, response.status_code)
        self.assertEqual("degraded", response.json()["status"])
        self.assertFalse(response.json()["checks"]["turn"])

    @override_settings(
        REST_FRAMEWORK={
            "DEFAULT_THROTTLE_RATES": {"ip": "1/min"},
        }
    )
    def test_ip_throttle_rejects_repeated_requests(self):
        from django.core.cache import cache
        from rest_framework.test import APIRequestFactory
        from rest_framework.settings import api_settings
        from accounts.throttling import IPRateThrottle

        cache.clear()
        api_settings.reload()
        try:
            request = APIRequestFactory().get(
                "/api/test", REMOTE_ADDR="203.0.113.9")
            throttle = IPRateThrottle()
            throttle.rate = "1/min"
            throttle.num_requests, throttle.duration = throttle.parse_rate(throttle.rate)
            self.assertTrue(throttle.allow_request(request, None))
            self.assertFalse(throttle.allow_request(request, None))
        finally:
            api_settings.reload()


class JsonLoggingTests(TestCase):
    def test_json_formatter_includes_request_id(self):
        import logging

        from config.logging import JsonFormatter
        from config.request_context import request_id_context

        token = request_id_context.set("request-42")
        try:
            record = logging.LogRecord(
                "test", logging.INFO, __file__, 1, "hello", (), None)
            payload = json.loads(JsonFormatter().format(record))
        finally:
            request_id_context.reset(token)
        self.assertEqual("request-42", payload["request_id"])
        self.assertEqual("hello", payload["message"])
class PublicLinkTests(TestCase):
    def test_room_link_is_accessible_and_opens_the_app(self):
        response = self.client.get("/rooms/ABC123")
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, "bazikhooneh://room/ABC123")

    def test_invalid_room_link_is_not_found(self):
        self.assertEqual(self.client.get("/rooms/not-valid").status_code, 404)

    @override_settings(ANDROID_APP_CERT_SHA256="AA:BB,CC:DD")
    def test_asset_links_uses_configured_signing_certificates(self):
        response = self.client.get("/.well-known/assetlinks.json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json()[0]["target"]["sha256_cert_fingerprints"],
            ["AA:BB", "CC:DD"],
        )
