import { useEffect, useState, type FormEvent } from "react";
import { Mail, ShieldCheck } from "lucide-react";
import { api } from "./api";

export function VerifyEmail({
  email,
  organizer = false,
  onVerified,
}: {
  email: string;
  organizer?: boolean;
  onVerified: () => void;
}) {
  const [mode, setMode] = useState(""),
    [code, setCode] = useState(""),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(""),
    [error, setError] = useState(""),
    [testCode, setTestCode] = useState(""),
    [retryAt, setRetryAt] = useState(0),
    [now, setNow] = useState(Date.now());
  useEffect(() => {
    void api<{ mode: string }>("/auth/config")
      .then((c) => setMode(c.mode))
      .catch((e) => setError(e.message));
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  async function send() {
    setBusy(true);
    setError("");
    setTestCode("");
    try {
      const r = await api<{ verified?: boolean; mode: string }>(
        "/verification/send",
        "POST",
        undefined,
        organizer,
      );
      if (r.verified) {
        onVerified();
        return;
      }
      setMode(r.mode);
      setRetryAt(Date.now() + 60000);
      setMessage(
        r.mode === "smtp"
          ? `A code was sent to ${email}. Check your inbox and spam folder.`
          : "A test message is ready below. No real email was sent.",
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function confirm(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api("/verification/confirm", "POST", { code }, organizer);
      onVerified();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="verification-panel">
      <div className="panel-heading">
        <Mail size={25} />
        <h2>Verify your email</h2>
      </div>
      <p>
        Verify <strong>{email}</strong> before{" "}
        {organizer
          ? "creating or managing a college workspace"
          : "registering for events"}
        . Codes expire in 10 minutes.
      </p>
      {mode === "development" && (
        <div className="demo-notice">
          <strong>Local email demo</strong>
          <p>
            Real email delivery is not connected. Use the test inbox to try
            verification; this does not prove ownership of a real mailbox.
          </p>
        </div>
      )}
      <button
        type="button"
        className="button secondary"
        disabled={busy || retryAt > now}
        onClick={() => void send()}
      >
        {retryAt > now
          ? `Resend in ${Math.min(60, Math.ceil((retryAt - now) / 1000))}s`
          : "Send verification code"}
      </button>
      {message && <p role="status">{message}</p>}
      {mode === "development" && (
        <div className="test-inbox">
          <button
            type="button"
            className="text-button"
            onClick={() =>
              void api<{ code?: string; notice: string }>(
                "/verification/test-inbox",
                "GET",
                undefined,
                organizer,
              )
                .then((r) => {
                  setTestCode(r.code || "");
                  setMessage(r.notice);
                })
                .catch((e) => setError(e.message))
            }
          >
            Open my test inbox
          </button>
          {testCode && (
            <p>
              Test verification code: <strong>{testCode}</strong>
            </p>
          )}
        </div>
      )}
      <form onSubmit={confirm}>
        <label>
          Six-digit verification code
          <input
            name="code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]{6}"
            maxLength={6}
            required
            placeholder="000000"
          />
        </label>
        <button className="button primary" disabled={busy}>
          <ShieldCheck size={18} />
          {busy ? "Please wait…" : "Verify email"}
        </button>
      </form>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
