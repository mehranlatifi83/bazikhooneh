#!/usr/bin/env bash
set -euo pipefail

fail() {
  logger -p daemon.err -t bazikhooneh-monitor "$1"
  exit 1
}

systemctl is-active --quiet bazikhooneh || fail "application service is not active"
systemctl is-active --quiet postgresql || fail "postgresql service is not active"
systemctl is-active --quiet redis-server || fail "redis service is not active"

readiness="$(curl --fail --silent --max-time 5 \
  -H 'Host: bazikhooneh.codelighthouse.ir' \
  http://127.0.0.1:8100/health/ready/)" ||
  fail "readiness endpoint failed"

available_kb="$(df --output=avail / | tail -1 | tr -d ' ')"
(( available_kb >= 1048576 )) || fail "less than 1 GiB disk space remains"

memory_percent="$(free | awk '/Mem:/ {printf "%.0f", ($3/$2)*100}')"
(( memory_percent < 90 )) || fail "memory usage is at least 90 percent"

postgres_connections="$(
  runuser -u postgres -- psql -d postgres -Atqc \
    "SELECT count(*) FROM pg_stat_activity"
)"
postgres_max="$(
  runuser -u postgres -- psql -d postgres -Atqc \
    "SHOW max_connections"
)"
(( postgres_connections * 100 < postgres_max * 80 )) ||
  fail "postgresql connection usage is at least 80 percent"

recent_restarts="$(
  journalctl -u bazikhooneh --since '10 minutes ago' --no-pager |
    grep -c 'Scheduled restart job' || true
)"
(( recent_restarts < 3 )) || fail "application restarted at least 3 times in 10 minutes"

recent_server_errors="$(
  journalctl -u bazikhooneh --since '2 minutes ago' --no-pager |
    grep -c '"status":500' || true
)"
(( recent_server_errors == 0 )) ||
  fail "application returned a server error in the last 2 minutes"

logger -p daemon.info -t bazikhooneh-monitor "healthy $readiness"
