# Domain email setup

Direct mail hosting on this VPS is intentionally not enabled. Outbound TCP port 25 is blocked,
the IP reverse DNS belongs to the hosting provider, and the domain currently has no MX, SPF,
DKIM, or DMARC records. Use a managed mailbox or transactional-email provider so messages are
authenticated and deliver reliably.

## Recommended split

- Create human mailboxes such as `support@codelighthouse.ir` with Zoho Mail, Google Workspace,
  or another managed mailbox provider.
- Create the application sender `no-reply@bazikhooneh.codelighthouse.ir` with a transactional
  provider such as Brevo, Postmark, Mailgun, Amazon SES, or ZeptoMail.
- Add the MX, SPF, and DKIM records exactly as issued by the selected provider.
- Add a DMARC TXT record at `_dmarc.codelighthouse.ir`, initially using
  `v=DMARC1; p=none; rua=mailto:dmarc@codelighthouse.ir; adkim=s; aspf=s`, then move to
  `p=quarantine` or `p=reject` after verifying legitimate traffic.

## Django configuration

Set the `EMAIL_*` variables documented in `backend/.env.example`, restart `bazikhooneh`, and
send a password-reset test. Never commit SMTP credentials to Git.
