# Production email

The VPS hosts mail for `bazikhooneh.codelighthouse.ir` using Postfix, Dovecot,
Rspamd, Redis, and Let's Encrypt.

## Cloudflare records

All mail-related address records must be **DNS only** (grey cloud).

| Type | Name | Priority | Content |
| --- | --- | ---: | --- |
| A | `mail.bazikhooneh` | — | `<VPS_IPV4>` |
| AAAA | `mail.bazikhooneh` | — | `<VPS_IPV6>` |
| MX | `bazikhooneh` | 10 | `mail.bazikhooneh.codelighthouse.ir` |
| TXT | `bazikhooneh` | — | `v=spf1 mx -all` |
| TXT | `mail._domainkey.bazikhooneh` | — | `v=DKIM1; k=rsa; p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApY4JGoShVYNUUb3+MlRvQs/tKLtyLCnPWIIXouB5007RXlulMwSv/EvUd9puJ8BDJEuWbG5jfxg1+CypRkvjBmfvmsteASwVq5E2Cd6G/vEzP7sSKErgNABGDshnOC9BHhY+AJl4RMcX1bF4pCqQAkR1Hw7lDLBQKlbjEPwtrgnlr5wozZtWUcy/nZ4rOpNXrOMdtHDuHY5JHj20YL7i/B7hrnjIY5xo91I3ytD6cPHUTI9xPXjRO70RRFkR/PPqoW5OFLvDw+xeHwrwfO5M5XS1ZYHLrAqAWtAeIQjDOOW5OOW+MFHMj7bKCFc/BEQPWQhN3ukBpEau1/DaaEy8VwIDAQAB` |
| TXT | `_dmarc.bazikhooneh` | — | `v=DMARC1; p=none; rua=mailto:dmarc@bazikhooneh.codelighthouse.ir; adkim=s; aspf=s; pct=100` |
| TXT | `_smtp._tls.bazikhooneh` | — | `v=TLSRPTv1; rua=mailto:dmarc@bazikhooneh.codelighthouse.ir` |

Replace the existing `mail.bazikhooneh` CNAME with the A and AAAA records
above, using the production VPS addresses from the private deployment
inventory. Do not store those addresses in this repository. Do not proxy SMTP
or IMAP through Cloudflare.

In the VPS provider panel, set reverse DNS for both server addresses to:

```text
mail.bazikhooneh.codelighthouse.ir
```

DMARC starts in monitoring mode. Move it to `p=quarantine`, and later
`p=reject`, only after legitimate production mail consistently passes SPF and
DKIM.

## Client settings

- IMAP: `mail.bazikhooneh.codelighthouse.ir`, port 993, SSL/TLS
- SMTP: `mail.bazikhooneh.codelighthouse.ir`, port 587 with STARTTLS
- Alternative SMTP: port 465 with SSL/TLS
- Authentication: required, using the full email address

Credentials are generated on the VPS and stored only in
`/root/mail-credentials.txt` with mode `0600`. Never commit that file or copy
its passwords into repository files.

## Operations

- Installer: `deploy/mail/setup-mail.sh`
- Django SMTP configurator: `deploy/mail/configure-backend-mail.sh`
- Configuration backup: `/root/mail-backup-20260728-082354.tar.gz`
- Certificate renewal: `snap.certbot.renew.timer`
- Renewal deploy hook:
  `/etc/letsencrypt/renewal-hooks/deploy/reload-mail-services.sh`

The Django production service uses `no-reply@bazikhooneh.codelighthouse.ir`.
Password reset and other application email are enabled through SMTP.
