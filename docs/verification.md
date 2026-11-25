# Verification record

Verified locally on 25 November 2026. Synthetic accounts and fictional events only; no real college rollout.

| Check | Result |
| --- | --- |
| TypeScript and Vite build | Passed |
| H2 integration suite | 57 tests, zero failures/errors |
| PostgreSQL 16 integration suite | 57 tests, zero failures/errors |
| Executable JAR and existing H2 upgrade | Built and run; previous registrations retained |
| Actual SMTP transport | Three verification emails captured on loopback; codes accepted |
| External email provider | Not configured; real mailbox delivery not verified |
| Docker Compose | Configuration checked; containers not run |
| Remote GitHub Actions | Not run |

## Authentication and organization tests

The 21 new scenarios cover exact college domains, lookalike domains, open-event eligibility, unverified-account rejection, hashed single-use verification codes, cross-account isolation, expiry, persistent failed attempts, resend cooldown, personal organizer passwords, workspace domain ownership, cross-workspace API denial, scoped overviews, owner-only team management, immediate removal of organizer access, unverified admission rejection, SMTP recipient selection/failure, disabled test inbox in SMTP mode, loopback-only development mode, removal of the shared-password endpoint and separation of development from SMTP verification.

The existing 36 scenarios continue to cover concurrent allocation, FIFO promotion, cancellation races, retries, optional seats, QR validation and duplicate check-ins, roles, sessions and earlier booking migration. No percentage coverage metric or security-audit claim is made.

## SMTP smoke test

`scripts/smtp_smoke_test.py` starts a temporary app with SMTP mode and an actual local capture server. It captures and consumes verification codes for one organizer and two students, creates college-only/open events, verifies domain restrictions and confirms that the development inbox returns 404. It stops the server/app and removes the temporary database afterward.

Results: `smtp-test-result.json`. Three messages received; organizer and both students verified; unverified requests blocked; matching-domain college access and outside-domain open access passed. No message was delivered externally. Authentication/TLS negotiation with an external provider, sender reputation and spam placement remain untested.

## PostgreSQL contention experiment

`scripts/contention_test.py` uses a disposable app on localhost:8081. It creates and verifies a synthetic organizer and 100 student accounts using the local test inbox, then races registrations for a one-place college-only event.

- 1 confirmation, 99 waitlisted entries and zero unexpected errors.
- 20 registration retries returned one registration.
- Cancellation promoted the earliest waiter; the next student became position 1.
- 20 QR check-in requests produced 1 new check-in and 19 duplicate responses.
- Cancelled QR rejected.
- Registration latency: p50 101.17 ms, p95 143.16 ms, maximum 148.92 ms.

Evidence: `contention-result.json`. Timing excludes signup and email verification. One app process and a local PostgreSQL database do not establish public latency, sustained throughput or real adoption.

## Browser verification

- Verified an existing sample student through the labelled test inbox.
- Confirmed an outside-domain account was rejected from a college-only event.
- Logged in with the individual Sample Campus organizer account and verified its email.
- Created a second workspace and an open event with configurable capacity.
- Filtered discovery to open events and registered the outside-domain student successfully.
- Created a new student without a roll number, verified its matching college email and successfully registered for a college-only event.
- Inspected the organizer team and owner controls.
- Checked the mobile organizer layout at 390 × 844; page width stayed at 390 pixels. Restored the viewport afterward.
- Saved refreshed discovery and organizer screenshots.

The previous version's browser check exercised QR image decoding, waitlist promotion and duplicate admission; current API tests preserve those guarantees. Physical camera hardware and the browser's QR download event remain unverified. The prior QR image test used the displayed PNG bytes and the image chooser.
