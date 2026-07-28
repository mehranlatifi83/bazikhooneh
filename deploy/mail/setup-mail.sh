#!/usr/bin/env bash
set -euo pipefail

MAIL_DOMAIN="bazikhooneh.codelighthouse.ir"
MAIL_HOST="mail.${MAIL_DOMAIN}"
SELECTOR="mail"
VMAIL_UID="5000"
VMAIL_GID="5000"
CERT_DIR="/etc/letsencrypt/live/${MAIL_HOST}"
CREDS_FILE="/root/mail-credentials.txt"
DKIM_DIR="/var/lib/rspamd/dkim"
RENEW_HOOK="/etc/letsencrypt/renewal-hooks/deploy/reload-mail-services.sh"

test -s "${CERT_DIR}/fullchain.pem"
test -s "${CERT_DIR}/privkey.pem"

if ! getent group vmail >/dev/null; then
  groupadd -g "${VMAIL_GID}" vmail
fi
if ! id vmail >/dev/null 2>&1; then
  useradd -g vmail -u "${VMAIL_UID}" -d /var/vmail -m -s /usr/sbin/nologin vmail
fi
install -d -o vmail -g vmail -m 0750 "/var/vmail/${MAIL_DOMAIN}"

if [[ ! -s "${CREDS_FILE}" ]]; then
  SUPPORT_PASSWORD="$(openssl rand -base64 30 | tr -d '\n')"
  NOREPLY_PASSWORD="$(openssl rand -base64 30 | tr -d '\n')"
  install -m 0600 /dev/null "${CREDS_FILE}"
  {
    printf 'IMAP/SMTP host: %s\n' "${MAIL_HOST}"
    printf 'IMAP: 993 (SSL/TLS)\n'
    printf 'SMTP: 587 (STARTTLS) or 465 (SSL/TLS)\n'
    printf 'Username: support@%s\nPassword: %s\n\n' "${MAIL_DOMAIN}" "${SUPPORT_PASSWORD}"
    printf 'Username: no-reply@%s\nPassword: %s\n' "${MAIL_DOMAIN}" "${NOREPLY_PASSWORD}"
  } >>"${CREDS_FILE}"
else
  SUPPORT_PASSWORD="$(awk '/^Username: support@/{getline; sub(/^Password: /, ""); print; exit}' "${CREDS_FILE}")"
  NOREPLY_PASSWORD="$(awk '/^Username: no-reply@/{getline; sub(/^Password: /, ""); print; exit}' "${CREDS_FILE}")"
fi

SUPPORT_HASH="$(doveadm pw -s SHA512-CRYPT -p "${SUPPORT_PASSWORD}")"
NOREPLY_HASH="$(doveadm pw -s SHA512-CRYPT -p "${NOREPLY_PASSWORD}")"
install -m 0640 -o root -g dovecot /dev/null /etc/dovecot/users
printf 'support@%s:%s\nno-reply@%s:%s\n' \
  "${MAIL_DOMAIN}" "${SUPPORT_HASH}" \
  "${MAIL_DOMAIN}" "${NOREPLY_HASH}" >/etc/dovecot/users

# This installation serves only virtual mailboxes. Avoid unnecessary PAM
# lookups (and misleading authentication-failure entries) for mail users.
sed -i 's/^!include auth-system\.conf\.ext$/#!include auth-system.conf.ext/' \
  /etc/dovecot/conf.d/10-auth.conf

cat >/etc/dovecot/conf.d/99-bazikhooneh.conf <<EOF
protocols {
  imap = yes
  lmtp = yes
}

listen = *, ::
auth_mechanisms = plain login
auth_allow_cleartext = no

mail_driver = maildir
mail_home = /var/vmail/%{user | domain}/%{user | username}
mail_path = %{home}/Maildir
mail_inbox_path = %{home}/Maildir
mail_uid = vmail
mail_gid = vmail
first_valid_uid = ${VMAIL_UID}

passdb passwd-file {
  default_password_scheme = SHA512-CRYPT
  auth_username_format = %{user | lower}
  passwd_file_path = /etc/dovecot/users
}

userdb static {
  fields {
    uid = vmail
    gid = vmail
    home = /var/vmail/%{user | domain}/%{user | username}
  }
}

ssl = required
ssl_min_protocol = TLSv1.2
ssl_server_cert_file = ${CERT_DIR}/fullchain.pem
ssl_server_key_file = ${CERT_DIR}/privkey.pem

service imap-login {
  inet_listener imap {
    port = 143
  }
  inet_listener imaps {
    port = 993
    ssl = yes
  }
}

service lmtp {
  unix_listener /var/spool/postfix/private/dovecot-lmtp {
    mode = 0600
    user = postfix
    group = postfix
  }
}

service auth {
  unix_listener /var/spool/postfix/private/auth {
    mode = 0660
    user = postfix
    group = postfix
  }
}

protocol lmtp {
  auth_username_format = %{user | lower}
  postmaster_address = postmaster@${MAIL_DOMAIN}
}
EOF

cat >/etc/postfix/vmailbox <<EOF
support@${MAIL_DOMAIN} ${MAIL_DOMAIN}/support/
no-reply@${MAIL_DOMAIN} ${MAIL_DOMAIN}/no-reply/
EOF
cat >/etc/postfix/virtual <<EOF
postmaster@${MAIL_DOMAIN} support@${MAIL_DOMAIN}
abuse@${MAIL_DOMAIN} support@${MAIL_DOMAIN}
dmarc@${MAIL_DOMAIN} support@${MAIL_DOMAIN}
EOF
postmap /etc/postfix/vmailbox
postmap /etc/postfix/virtual

postconf -e "myhostname = ${MAIL_HOST}"
postconf -e "mydomain = ${MAIL_DOMAIN}"
postconf -e "myorigin = \$mydomain"
postconf -e "mydestination = localhost"
postconf -e "inet_interfaces = all"
postconf -e "inet_protocols = all"
postconf -e "smtpd_banner = \$myhostname ESMTP"
postconf -e "disable_vrfy_command = yes"
postconf -e "smtpd_helo_required = yes"
postconf -e "message_size_limit = 26214400"
postconf -e "mailbox_size_limit = 0"
postconf -e "recipient_delimiter = +"
postconf -e "virtual_mailbox_domains = ${MAIL_DOMAIN}"
postconf -e "virtual_mailbox_maps = hash:/etc/postfix/vmailbox"
postconf -e "virtual_alias_maps = hash:/etc/postfix/virtual"
postconf -e "virtual_transport = lmtp:unix:private/dovecot-lmtp"
postconf -e "smtpd_sasl_type = dovecot"
postconf -e "smtpd_sasl_path = private/auth"
postconf -e "smtpd_sasl_auth_enable = yes"
postconf -e "smtpd_sasl_security_options = noanonymous"
postconf -e "smtpd_tls_cert_file = ${CERT_DIR}/fullchain.pem"
postconf -e "smtpd_tls_key_file = ${CERT_DIR}/privkey.pem"
postconf -e "smtpd_tls_security_level = may"
postconf -e "smtpd_tls_auth_only = yes"
postconf -e "smtp_tls_security_level = may"
postconf -e "smtp_tls_loglevel = 1"
postconf -e "smtpd_relay_restrictions = permit_mynetworks, permit_sasl_authenticated, reject_unauth_destination"
postconf -e "smtpd_recipient_restrictions = reject_unknown_recipient_domain, reject_unlisted_recipient, permit_mynetworks, permit_sasl_authenticated, reject_unauth_destination"
postconf -e "smtpd_milters = inet:127.0.0.1:11332"
postconf -e "non_smtpd_milters = inet:127.0.0.1:11332"
postconf -e "milter_default_action = accept"
postconf -e "milter_protocol = 6"

postconf -M "submission/inet=submission inet n - n - - smtpd"
postconf -P "submission/inet/syslog_name=postfix/submission"
postconf -P "submission/inet/smtpd_tls_security_level=encrypt"
postconf -P "submission/inet/smtpd_sasl_auth_enable=yes"
postconf -P "submission/inet/smtpd_tls_auth_only=yes"
postconf -P "submission/inet/smtpd_relay_restrictions=permit_sasl_authenticated,reject"
postconf -P "submission/inet/smtpd_recipient_restrictions=permit_sasl_authenticated,reject"
postconf -P "submission/inet/milter_macro_daemon_name=ORIGINATING"

postconf -M "submissions/inet=submissions inet n - n - - smtpd"
postconf -P "submissions/inet/syslog_name=postfix/submissions"
postconf -P "submissions/inet/smtpd_tls_wrappermode=yes"
postconf -P "submissions/inet/smtpd_sasl_auth_enable=yes"
postconf -P "submissions/inet/smtpd_relay_restrictions=permit_sasl_authenticated,reject"
postconf -P "submissions/inet/smtpd_recipient_restrictions=permit_sasl_authenticated,reject"
postconf -P "submissions/inet/milter_macro_daemon_name=ORIGINATING"

install -d -o _rspamd -g _rspamd -m 0750 "${DKIM_DIR}"
if [[ ! -s "${DKIM_DIR}/${MAIL_DOMAIN}.${SELECTOR}.key" ]]; then
  rspamadm dkim_keygen \
    -b 2048 \
    -s "${SELECTOR}" \
    -d "${MAIL_DOMAIN}" \
    -k "${DKIM_DIR}/${MAIL_DOMAIN}.${SELECTOR}.key" \
    >"/root/${MAIL_DOMAIN}.${SELECTOR}.dkim.txt"
fi
chown _rspamd:_rspamd "${DKIM_DIR}/${MAIL_DOMAIN}.${SELECTOR}.key"
chmod 0600 "${DKIM_DIR}/${MAIL_DOMAIN}.${SELECTOR}.key"
chmod 0600 "/root/${MAIL_DOMAIN}.${SELECTOR}.dkim.txt"

install -d -o root -g root -m 0755 /etc/rspamd/local.d
cat >/etc/rspamd/local.d/dkim_signing.conf <<EOF
enabled = true;
selector = "${SELECTOR}";
path = "${DKIM_DIR}/\$domain.\$selector.key";
sign_authenticated = true;
sign_local = true;
allow_username_mismatch = false;
use_domain = "header";
try_fallback = false;
EOF

cat >/etc/rspamd/local.d/redis.conf <<EOF
servers = "127.0.0.1:6379";
EOF

doveconf -n >/dev/null
postfix check
rspamadm configtest

systemctl restart rspamd
systemctl restart dovecot
systemctl restart postfix
systemctl enable postfix dovecot rspamd >/dev/null

cat >"${RENEW_HOOK}" <<'EOF'
#!/usr/bin/env bash
set -e
systemctl reload postfix
systemctl reload dovecot
EOF
chmod 0755 "${RENEW_HOOK}"

doveadm auth test "support@${MAIL_DOMAIN}" "${SUPPORT_PASSWORD}"
printf 'Mail server configured successfully.\n'
