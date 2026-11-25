import { useEffect, useRef, useState, type FormEvent } from "react";
import { X, GraduationCap } from "lucide-react";
import { VerifyEmail } from "./VerifyEmail";
import { api, type Session, type Student } from "./api";

export function StudentAuth({
  onClose,
  onSuccess,
}: {
  onClose: () => void;
  onSuccess: (s: Student) => void;
}) {
  const [register, setRegister] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [pending, setPending] = useState<Student | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
    return () => dialog.current?.close();
  }, []);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const f = new FormData(e.currentTarget);
    const body = {
      email: String(f.get("email")).trim(),
      password: f.get("password"),
      ...(register
        ? {
            name: String(f.get("name")).trim(),
            studentNumber: String(f.get("studentNumber") || "").trim() || null,
          }
        : {}),
    };
    try {
      const s = await api<Session>(
        register ? "/auth/register" : "/auth/login",
        "POST",
        body,
      );
      localStorage.setItem("seatlock-session", s.token);
      if (s.student.emailVerified) onSuccess(s.student);
      else setPending(s.student);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <dialog
      ref={dialog}
      className="auth-dialog"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
      aria-labelledby={pending ? undefined : "auth-heading"}
      aria-label={pending ? "Verify student email" : undefined}
    >
      <button
        className="icon-button auth-close"
        onClick={onClose}
        disabled={busy}
        aria-label="Close student login"
      >
        <X size={21} />
      </button>
      {pending ? (
        <VerifyEmail
          email={pending.email}
          onVerified={() =>
            void api<Student>("/auth/me")
              .then(onSuccess)
              .catch((e) => setError(e.message))
          }
        />
      ) : (
        <>
          <div className="login-symbol">
            <GraduationCap size={30} />
          </div>
          <p className="eyebrow dark">YOUR CAMPUS STARTS HERE</p>
          <h2 id="auth-heading">
            {register
              ? "Create your student account"
              : "Welcome back, student."}
          </h2>
          <p className="muted">
            {register
              ? "One account for your campus events."
              : "Sign in to register and find your QR passes."}
          </p>
          <form onSubmit={submit}>
            {register && (
              <>
                <label>
                  Full name
                  <input
                    name="name"
                    required
                    maxLength={80}
                    autoComplete="name"
                  />
                </label>
                <label>
                  Student ID / roll number (optional)
                  <input
                    name="studentNumber"
                    minLength={3}
                    maxLength={30}
                    pattern="[A-Za-z0-9-]{3,30}"
                    title="3–30 letters, digits or hyphens"
                    autoComplete="off"
                    placeholder="e.g. CSE-2026-042"
                  />
                </label>
              </>
            )}
            <label>
              Email
              <input
                name="email"
                type="email"
                required
                maxLength={160}
                autoComplete="email"
              />
            </label>
            <label>
              Password
              <input
                name="password"
                type="password"
                required
                minLength={register ? 10 : undefined}
                maxLength={72}
                autoComplete={register ? "new-password" : "current-password"}
              />
            </label>
            {register && (
              <p className="muted">
                Use at least 10 characters. Verify your email after signup.
                College-only events require the organizer’s exact college email
                domain; open events accept any verified email.
              </p>
            )}
            {error && (
              <div className="error" role="alert">
                {error}
              </div>
            )}
            <button className="button primary full" disabled={busy}>
              {busy ? "One moment…" : register ? "Create account" : "Sign in"}
            </button>
          </form>
          <button
            className="text-button full"
            disabled={busy}
            onClick={() => {
              setRegister(!register);
              setError("");
            }}
          >
            {register
              ? "Already registered? Sign in"
              : "New here? Create a student account"}
          </button>
        </>
      )}
    </dialog>
  );
}
