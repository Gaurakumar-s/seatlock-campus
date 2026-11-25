# Live deployment

Run the existing Docker image on Render and keep application data in an external PostgreSQL database. The Java server serves both the React frontend and the API, so the app needs one public URL.

## Accounts and private settings

1. Create or sign in to a Render account and choose the **Free** web-service plan.
2. Create a **Free** Neon PostgreSQL project. Keep its connection details private.
3. Create a **Free** Brevo account, activate transactional sending, and verify the sender email. Create an API key for the app. A verified custom sender domain gives better deliverability; follow Brevo's sender verification requirements.

The app uses Brevo's HTTPS API because Render's Free web service blocks the usual SMTP ports. `MAIL_MODE=smtp` selects externally delivered verification; `MAIL_TRANSPORT=brevo` chooses HTTPS rather than SMTP. Local development still defaults to the loopback-only test inbox.

## Deploy

Use the repository's `render.yaml` as a Render Blueprint, or create a Docker Web Service from the repository and copy the same settings. Set these values in Render's environment editor:

| Variable | Value |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://YOUR_NEON_HOST/YOUR_DATABASE?sslmode=require` |
| `DB_USER` | Neon database user |
| `DB_PASSWORD` | Neon database password |
| `MAIL_MODE` | `smtp` |
| `MAIL_TRANSPORT` | `brevo` |
| `MAIL_FROM` | Exact verified sender email in Brevo |
| `BREVO_API_KEY` | Brevo API key, not an SMTP key |
| `BIND_ADDRESS` | `0.0.0.0` |
| `DB_POOL_SIZE` | `5` |
| `DB_MIN_IDLE` | `0` |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=10.0 -XX:+UseSerialGC` |

Use the Neon **direct** connection hostname for this long-running server and its schema initialization. Copy host, database, username and password into the separate fields above; do not paste a `postgresql://user:password@...` URL into `DB_URL`.

Render supplies `PORT`. Leave the repository root as the Docker build context, use `./Dockerfile`, and set the health-check path to `/api/health`. The Dockerfile bundles the frontend into the executable JAR.

A saved environment variable is not proof of email delivery. After deployment, create an account with an address you own, request a code, confirm receipt in that real inbox, and verify it in the app. Test student registration and organizer workspace creation. The test-inbox endpoint must return 404 in this deployment.

## Updates keep the same URL

Push tested changes to the existing repository and deploy the latest commit on the **same Render service**. Its assigned `onrender.com` URL stays the same. Keep the service, database and domain assignment; do not create a new service for every update. Review database migrations before deployments and back up important data.

Blueprints can deploy after CI checks pass. A service created from an unauthenticated public repository can use **Manual Deploy → Deploy latest commit**. The URL is the same for manual and automatic updates.

## Free-tier limits

- Render Free sleeps after 15 idle minutes; the next visit can take about a minute to wake it. Free plans have resource and usage limits.
- Render's own Free PostgreSQL expires after 30 days. This setup uses an external database instead of that temporary database.
- Neon Free has storage, compute and network quotas. Check its dashboard and current plan limits.
- Brevo Free allows 300 sends per day. Sender/account approval and deliverability checks still apply.
- Real email verification checks mailbox access, not official college authorization or current enrolment. Workspaces remain organizer-managed.

References: [Render Free](https://render.com/docs/free), [Render web services](https://render.com/docs/web-services), [Neon Java](https://neon.com/docs/guides/java), [Brevo transactional API](https://developers.brevo.com/reference/send-transac-email), [Brevo Free limits](https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan).
