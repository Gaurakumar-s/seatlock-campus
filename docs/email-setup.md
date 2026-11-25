# Email verification and college access

## What the app verifies

Every student and organizer verifies ownership of their email before registering or managing events. For each event an organizer chooses:

| Audience | Who can register |
| --- | --- |
| College only | A verified email at the workspace's exact configured domain |
| Open to everyone | Any verified email, including another college or a personal address |

An example domain `students.example.edu` accepts `person@students.example.edu`, but not `person@example.edu` or `person@students.example.edu.evil.test`. No real college's domain is guessed or hardcoded. One workspace currently supports one exact domain. Staff/student domains that differ require a future verified domain-management flow.

Email ownership is not proof of enrolment, current student status or official authority to represent a college. The interface calls these organizer-managed workspaces. College SSO, an authoritative roster and formal workspace approval remain future improvements. One registration is enforced per account, not per verified real-world human.

## Try it locally without an email provider

The default `MAIL_MODE=development` uses an in-memory test inbox. The application must bind to loopback. The interface clearly labels this mode; no external email is sent, and anyone using the local test account can read its generated test code.

1. Create a student account with a fictional email at `campus.example.test`, or use another domain to test open events.
2. Click **Send verification code → Open my test inbox**, then enter the six-digit code.
3. In Organizer space, sign in with `organizer@campus.example.test` and local demo password `Campus-demo-2026!`. Verify using its own test inbox.
4. Use Sample Campus, or create a new workspace with your organizer account's exact domain.
5. Create college-only and open events. Try students from matching and different domains.

The sample organizer is created only on the first migration in development mode. New real installations in SMTP mode create organizer accounts through signup; they have no default organizer password.

Development verification is stored separately from SMTP verification. Switching to SMTP requires accounts verified only in development to verify again through real email. Never expose development mode through a reverse proxy or tunnel.

## Connect a real email provider

Use an SMTP account approved for your sender address. Configure these environment variables in the terminal or hosting service that starts Java:

```sh
export MAIL_MODE=smtp
export MAIL_FROM='events@your-approved-sender-domain.example'
export SMTP_HOST='smtp.your-provider.example'
export SMTP_PORT=587
export SMTP_USERNAME='your-smtp-username'
# Set SMTP_PASSWORD privately in your environment or secret manager.
# Do not paste credentials in chat or commit them to Git.
```

Set `SMTP_PASSWORD` to the provider's SMTP credential/app password, then restart the app with `bash scripts/run.sh` or the packaged JAR. Use the provider's actual host, port and sender requirements. SMTP authentication and required STARTTLS are enabled by default. This configuration targets a STARTTLS endpoint; implicit TLS on port 465 is not the documented setup.

For Docker Compose, copy `.env.example` to `.env`, enter the database and SMTP settings, then run `docker compose up --build`. `.env` is ignored by Git. Compose reads it; direct Java and shell launchers read environment variables, not `.env` automatically. The packaged Docker image binds internally to all interfaces, so it requires SMTP mode.

After startup, `/api/auth/config` should return `{"mode":"smtp","realEmail":true}`. This confirms configuration, not successful delivery. Create an account with a mailbox you control, send a code, receive it and verify. The test inbox endpoint is unavailable in SMTP mode. If sending fails, the API returns `EMAIL_UNAVAILABLE` and rolls back the challenge; it does not report success or reveal the code.

The implementation uses Spring Boot's [JavaMailSender support and finite SMTP timeouts](https://docs.spring.io/spring-boot/reference/io/email.html). Delivery to an external provider has not been configured or verified in this project yet. Sender verification, DNS policies and spam placement depend on that provider.

## Implemented code protections

- Codes use a cryptographically secure random generator, expire after ten minutes and are stored as BCrypt hashes.
- A successful code is consumed once; five incorrect attempts lock it. Failed-attempt counts persist in the database.
- Requests have a per-account 60-second resend cooldown. Resending replaces the prior challenge.
- Sending and confirming require the account's authenticated session; recipients cannot be chosen in the request.
- Development inbox messages are scoped to that account and are never exposed in SMTP mode.
- Add broader login/signup/IP rate limiting, delivery quotas and abuse monitoring before public hosting. The per-account code controls alone do not prevent mass-account abuse.

## SMTP transport test

```sh
bash scripts/package.sh
python3 scripts/smtp_smoke_test.py
```

This script runs a temporary app and an actual loopback SMTP capture server, verifies an organizer and two students from captured messages, and checks both audience rules. It stops its processes and removes the temporary database. It sends no external mail and tests neither provider TLS/authentication nor external inbox delivery. Results are saved in `docs/smtp-test-result.json`.
