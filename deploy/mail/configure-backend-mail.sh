#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/root/bazikhooneh"
ENV_FILE="${APP_DIR}/.env"
CREDS_FILE="/root/mail-credentials.txt"
MAIL_DOMAIN="bazikhooneh.codelighthouse.ir"

test -s "${ENV_FILE}"
test -s "${CREDS_FILE}"

cp -a "${ENV_FILE}" "${ENV_FILE}.before-mail-$(date +%Y%m%d-%H%M%S)"
MAIL_PASSWORD="$(
  awk '/^Username: no-reply@/{getline; sub(/^Password: /, ""); print; exit}' \
    "${CREDS_FILE}"
)"
test -n "${MAIL_PASSWORD}"

sed -i \
  -e '/^[[:space:]]*EMAIL_DELIVERY_ENABLED=/d' \
  -e '/^[[:space:]]*EMAIL_BACKEND=/d' \
  -e '/^[[:space:]]*EMAIL_HOST=/d' \
  -e '/^[[:space:]]*EMAIL_PORT=/d' \
  -e '/^[[:space:]]*EMAIL_HOST_USER=/d' \
  -e '/^[[:space:]]*EMAIL_HOST_PASSWORD=/d' \
  -e '/^[[:space:]]*EMAIL_USE_TLS=/d' \
  -e '/^[[:space:]]*EMAIL_USE_SSL=/d' \
  -e '/^[[:space:]]*DEFAULT_FROM_EMAIL=/d' \
  "${ENV_FILE}"

{
  printf '\nEMAIL_DELIVERY_ENABLED=true\n'
  printf 'EMAIL_BACKEND=django.core.mail.backends.smtp.EmailBackend\n'
  printf 'EMAIL_HOST=mail.%s\n' "${MAIL_DOMAIN}"
  printf 'EMAIL_PORT=587\n'
  printf 'EMAIL_HOST_USER=no-reply@%s\n' "${MAIL_DOMAIN}"
  printf 'EMAIL_HOST_PASSWORD=%s\n' "${MAIL_PASSWORD}"
  printf 'EMAIL_USE_TLS=true\n'
  printf 'EMAIL_USE_SSL=false\n'
  printf 'DEFAULT_FROM_EMAIL=BaziKhooneh <no-reply@%s>\n' "${MAIL_DOMAIN}"
} >>"${ENV_FILE}"
chmod 0600 "${ENV_FILE}"

systemctl restart bazikhooneh
sleep 4
systemctl is-active --quiet bazikhooneh

systemd-run \
  --wait \
  --pipe \
  --collect \
  --property="User=bazikhooneh" \
  --property="WorkingDirectory=${APP_DIR}/backend" \
  --property="EnvironmentFile=${ENV_FILE}" \
  "${APP_DIR}/venv/bin/python" manage.py shell -c \
  'from django.core.mail import send_mail; print(send_mail("Bazikhooneh SMTP production test", "Password-reset email delivery is operational.", None, ["support@bazikhooneh.codelighthouse.ir"], fail_silently=False))'

sleep 2
doveadm search -u "support@${MAIL_DOMAIN}" mailbox INBOX \
  SUBJECT "Bazikhooneh SMTP production test"
