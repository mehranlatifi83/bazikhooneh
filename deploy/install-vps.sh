#!/usr/bin/env bash
set -Eeuo pipefail

APP_NAME="bazikhooneh"
APP_DIR="/root/${APP_NAME}"
APP_USER="bazikhooneh"
REPO_URL="https://github.com/mehranlatifi83/bazikhooneh.git"
PUBLIC_IP="91.107.131.14"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this installer as root." >&2
  exit 1
fi

if ! id "${APP_USER}" >/dev/null 2>&1; then
  useradd --system --user-group --home-dir "${APP_DIR}" --shell /usr/sbin/nologin "${APP_USER}"
fi

git config --global --add safe.directory "${APP_DIR}"

if [[ ! -d "${APP_DIR}/.git" ]]; then
  git clone --branch dev --single-branch "${REPO_URL}" "${APP_DIR}"
else
  git -C "${APP_DIR}" fetch origin dev
  git -C "${APP_DIR}" checkout dev
  git -C "${APP_DIR}" pull --ff-only origin dev
fi

setfacl -m "u:${APP_USER}:x" /root
chown -R root:"${APP_USER}" "${APP_DIR}"
chmod -R g-w,o-rwx "${APP_DIR}"

if [[ ! -f "${APP_DIR}/.env" ]]; then
  db_password="$(openssl rand -hex 32)"
  secret_key="$(openssl rand -hex 48)"
  cat >"${APP_DIR}/.env" <<ENV
DJANGO_SECRET_KEY=${secret_key}
DJANGO_DEBUG=false
DJANGO_ALLOWED_HOSTS=${PUBLIC_IP}
DJANGO_CSRF_TRUSTED_ORIGINS=https://${PUBLIC_IP}
DJANGO_SECURE_SSL_REDIRECT=true
DJANGO_HSTS_SECONDS=31536000
DB_ENGINE=django.db.backends.postgresql
DB_NAME=${APP_NAME}
DB_USER=${APP_USER}
DB_PASSWORD=${db_password}
DB_HOST=127.0.0.1
DB_PORT=5432
DB_CONN_MAX_AGE=60
REDIS_URL=redis://127.0.0.1:6379/1
STATIC_ROOT=/var/www/bazikhooneh-static
EMAIL_DELIVERY_ENABLED=false
EMAIL_BACKEND=django.core.mail.backends.dummy.EmailBackend
DEFAULT_FROM_EMAIL=no-reply@${PUBLIC_IP}
ENV
  chown root:"${APP_USER}" "${APP_DIR}/.env"
  chmod 640 "${APP_DIR}/.env"

  if ! runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${APP_USER}'" | grep -q 1; then
    runuser -u postgres -- psql -v ON_ERROR_STOP=1 -c "CREATE ROLE ${APP_USER} LOGIN PASSWORD '${db_password}'"
  else
    runuser -u postgres -- psql -v ON_ERROR_STOP=1 -c "ALTER ROLE ${APP_USER} PASSWORD '${db_password}'"
  fi
else
  db_password="$(sed -n 's/^DB_PASSWORD=//p' "${APP_DIR}/.env")"
fi

if ! runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_database WHERE datname='${APP_NAME}'" | grep -q 1; then
  runuser -u postgres -- createdb --owner="${APP_USER}" "${APP_NAME}"
fi

python3 -m venv "${APP_DIR}/venv"
"${APP_DIR}/venv/bin/pip" install --upgrade pip wheel
"${APP_DIR}/venv/bin/pip" install -r "${APP_DIR}/backend/requirements.txt"

install -d -o "${APP_USER}" -g "${APP_USER}" /var/www/bazikhooneh-static
install -d -o postgres -g postgres -m 750 /var/backups/bazikhooneh

set -a
source "${APP_DIR}/.env"
set +a
"${APP_DIR}/venv/bin/python" "${APP_DIR}/backend/manage.py" migrate --noinput
"${APP_DIR}/venv/bin/python" "${APP_DIR}/backend/manage.py" collectstatic --noinput
"${APP_DIR}/venv/bin/python" "${APP_DIR}/backend/manage.py" check --deploy

install -m 644 "${APP_DIR}/deploy/bazikhooneh.service" /etc/systemd/system/bazikhooneh.service
install -m 644 "${APP_DIR}/deploy/bazikhooneh-cleanup.service" /etc/systemd/system/bazikhooneh-cleanup.service
install -m 644 "${APP_DIR}/deploy/bazikhooneh-cleanup.timer" /etc/systemd/system/bazikhooneh-cleanup.timer
install -m 644 "${APP_DIR}/deploy/bazikhooneh-backup.service" /etc/systemd/system/bazikhooneh-backup.service
install -m 644 "${APP_DIR}/deploy/bazikhooneh-backup.timer" /etc/systemd/system/bazikhooneh-backup.timer

systemctl daemon-reload
systemctl enable --now bazikhooneh.service bazikhooneh-cleanup.timer bazikhooneh-backup.timer

# Create or update the requested administrator without exposing its password in logs.
BAZIKHOONEH_ADMIN_PASSWORD="${BAZIKHOONEH_ADMIN_PASSWORD:-}" \
  "${APP_DIR}/venv/bin/python" "${APP_DIR}/backend/manage.py" shell -c \
  "import os; from django.contrib.auth import get_user_model; U=get_user_model(); u,_=U.objects.get_or_create(username='mehranlatifi83', defaults={'email':'mehran.latifi8383@gmail.com'}); u.email='mehran.latifi8383@gmail.com'; u.is_staff=True; u.is_superuser=True; p=os.environ.get('BAZIKHOONEH_ADMIN_PASSWORD'); p and u.set_password(p); u.save()"

systemctl --no-pager --full status bazikhooneh.service
