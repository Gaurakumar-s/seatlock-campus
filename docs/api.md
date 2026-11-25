# SeatLock Campus API

Base path `/api`. JSON requests/responses; errors include `code` and `message`. Timestamps are Unix milliseconds. Protected endpoints use `Authorization: Bearer <token>`. Missing/expired sessions return 401; wrong roles return 403.

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| GET | `/health` | Public | Process health, not database readiness |
| POST | `/auth/register` | Public | Create student and session |
| POST | `/auth/login` | Public | Student login |
| GET | `/auth/me` | Student | Own profile |
| POST | `/auth/logout` | Either role | Revoke current session |
| POST | `/organizer/register` | Public | Individual organizer signup |
| POST | `/organizer/login` | Public | Individual organizer login |
| GET | `/organizer/me` | Organizer | Own profile and verification status |
| GET | `/auth/config` | Public | Email mode; not delivery confirmation |
| POST | `/verification/send` | Either role | Send code to own account email |
| POST | `/verification/confirm` | Either role | Consume own six-digit code |
| GET | `/verification/test-inbox` | Either role | Own test message; development mode only |
| GET | `/organizations` | Public | College directory |
| GET/POST | `/organizer/organizations` | Organizer | List own workspaces / create workspace |
| GET/POST | `/organizer/organizations/{id}/team` | Member / Owner | List team / add verified organizer |
| DELETE | `/organizer/organizations/{id}/team/{member}` | Owner | Remove non-owner organizer access |
| GET | `/events` | Public | Future events and capacity counts |
| GET | `/events/{id}` | Public | Event details |
| GET | `/events/{id}/seats` | Public | Assigned-seat inventory |
| POST | `/events/{id}/registrations` | Student | Confirm a place or join waitlist |
| GET | `/registrations` | Student | Own active and cancelled history |
| DELETE | `/registrations/{id}` | Student owner | Cancel and atomically promote next waiter |
| GET | `/admin/overview` | Organizer | Event inventory and aggregate counts |
| POST | `/admin/events` | Organizer | Create free event |
| GET | `/admin/events/{id}/registrations` | Organizer | Event roster, without QR tokens |
| POST | `/admin/events/{id}/check-in` | Organizer | Validate pass and record attendance |

The shared-password `/admin/sessions` endpoint has been removed. The older `/holds` and `/bookings` endpoints have been replaced by registrations.

## Authentication

```json
{"name":"Sample Student","email":"student@example.test","studentNumber":"CS-2026-001","password":"sample-password-2026"}
```

Email is lowercase and unique within each account type. Student ID is optional, self-reported, uppercase and 3–30 letters, digits or hyphens; roll numbers can repeat across colleges. If absent, an account reference is generated. Passwords require ten characters and at most 72 UTF-8 bytes. Sessions expire after seven days. Organizer signup accepts `name`, `email` and `password`; both login routes accept email/password. Signup creates an unverified account.

`POST /verification/send` uses the authenticated account's stored email. `POST /verification/confirm` accepts `{"code":"123456"}`. Codes expire in ten minutes, allow five incorrect attempts and have a 60-second resend cooldown. The test inbox is scoped to the authenticated account and returns 404 in SMTP mode. Development verification is not accepted after switching to SMTP. See [email setup](email-setup.md).

Create workspace: `{"name":"Your College","emailDomain":"students.example.edu"}`. The organizer must have verified an email at that exact domain. Public email provider domains such as gmail.com are rejected for workspace creation. This does not establish official college endorsement.

Add a team member: `{"email":"colleague@students.example.edu"}`. Only the owner can add a separately registered, verified organizer at the same college domain. Only owners can remove non-owner team members. Access checks use current database membership, so removal also blocks an existing session.

`GET /admin/overview` and `POST /admin/events` require `X-Organization-ID: <workspace id>`. The server validates the organizer's membership; the header alone grants nothing. Roster and check-in routes validate membership against the event's stored organization. Public event responses include `organizationId`, `organizationName` and `audience`.

## Registration

GENERAL: `{"requestKey":"<client-generated UUID>"}`.

ASSIGNED when places remain: `{"requestKey":"<client-generated UUID>","seatCode":"A1"}`.

Use the same request key on retries. Responses are entries with status `CONFIRMED`, `WAITLISTED` or `CANCELLED`. Confirmed entries include a private `qrPayload`; waitlisted entries include a live `waitlistPosition`. The same student cannot have two active entries for an event. Registration closes when the event starts. Unverified accounts are rejected for all events. `COLLEGE` events require the workspace's exact email domain; `OPEN` events accept any verified email.

For full events, omit the seat code and join the queue. A promoted assigned-seat registration gets the released seat. Cancellation before the start invalidates the pass; checked-in students cannot cancel. Repeated cancellation is safe. Rejoining uses a new request key and joins the back of the queue. Retrying a cancelled entry's original key returns the cancelled entry.

## Event creation

```json
{
  "title":"Guest speaker session",
  "category":"Guest Talk",
  "venue":"Main Auditorium",
  "description":"A guest talk followed by student questions.",
  "startsAt":1893456000000,
  "capacity":200,
  "seatsPerRow":10,
  "speaker":"Guest speaker",
  "hostClub":"Career & Innovation Cell",
  "bookingMode":"GENERAL",
  "audience":"COLLEGE"
}
```

Use a future timestamp. Categories: `Guest Talk`, `Workshop`, `Cultural`, `Club Meetup`. Capacity: 1–500. Audience is required: `COLLEGE` or `OPEN`. Mode: `GENERAL` or `ASSIGNED`; seats per row: 8–20 (ignored for GENERAL). Assigned codes extend A…Z, AA… as required; partial rows contain only actual seats. Unknown JSON fields are rejected.

## QR check-in

Request: `{"qrPayload":"seatlock:<64-character hex token>"}`. The pass must belong to a confirmed entry at the requested event. Check-in is open from two hours before to twelve hours after the start. Response: `{ "registration": <entry without token>, "alreadyCheckedIn": false }`. Repeating a successful scan returns `alreadyCheckedIn: true` and the original timestamp. Malformed tokens return 400 `INVALID_PASS`; unknown, cancelled and wrong-event passes return 404 `INVALID_PASS`.

Image decoding happens in the browser. Only the decoded token goes to the check-in API. Organizer rosters contain student names and IDs but never the private admission token.

Common 409 codes include `SEAT_TAKEN`, `REGISTRATION_CLOSED`, `KEY_REUSED`, `ACCOUNT_EXISTS`, `CHECKIN_CLOSED`, `ALREADY_CHECKED_IN` and `RETRY`. Ownership violations return 404. Roster pagination is not implemented.

Verification errors include `EMAIL_NOT_VERIFIED`, `INVALID_CODE`, `CODE_EXPIRED`, `CODE_LOCKED`, `SEND_COOLDOWN` and `EMAIL_UNAVAILABLE`. Audience/workspace errors include `COLLEGE_ONLY`, `DOMAIN_MISMATCH`, `ORGANIZATION_REQUIRED` and `ORGANIZATION_FORBIDDEN`.
