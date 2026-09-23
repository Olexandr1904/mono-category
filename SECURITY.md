# Security policy

## Reporting a vulnerability

Please report security issues privately through GitHub's
[private vulnerability reporting](https://github.com/Olexandr1904/mono-category/security/advisories/new)
rather than opening a public issue.

Expect a first response within a week. This is a single-maintainer hobby
project, not a product with an on-call rotation — please size your expectations
to that, and do not treat a slow reply as an invitation to disclose publicly
before we have talked.

## Scope

This is software you deploy yourself. There is no service to attack: every
instance is somebody's own Fly machine, holding their own bank token. Reports
about the code are in scope; reports about a specific deployed instance belong
to whoever runs it.

In scope, and the areas most worth looking at:

- Authentication and session handling (`app.web.Auth`) — the admin password is
  the only thing between the internet and a full transaction history.
- CSRF (`app.web.Csrf`) and the security headers (`app.web.SecurityHeaders`).
  `/webhook/` and `/tg/` are CSRF-exempt by necessity; both authenticate by an
  unguessable secret in the URL path instead.
- Token storage — both API tokens are encrypted at rest with AES-GCM under
  `ENCRYPTION_KEY` (`app.db.Crypto`).
- The Telegram pairing flow. While a pairing code is live, sending it from any
  chat moves the bot to that chat, which makes the code a credential.

Out of scope: anything requiring the attacker to already know
`ADMIN_PASSWORD` or `ENCRYPTION_KEY`, and denial of service against a
single-machine deployment, which is an accepted property of the design.

## Supported versions

The tip of `main` is the only supported version. There are no backports.
