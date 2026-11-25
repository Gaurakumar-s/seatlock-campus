import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import {
  Ticket,
  Download,
  CheckCircle2,
  Clock3,
  X,
  RefreshCw,
} from "lucide-react";
import { api, date, time, type Entry } from "./api";

function Pass({ entry }: { entry: Entry }) {
  const [qr, setQr] = useState(""),
    [error, setError] = useState("");
  useEffect(() => {
    let alive = true;
    setQr("");
    if (entry.qrPayload)
      void QRCode.toDataURL(entry.qrPayload, {
        width: 360,
        margin: 4,
        errorCorrectionLevel: "M",
      })
        .then((url) => {
          if (alive) setQr(url);
        })
        .catch(() => {
          if (alive) setError("Could not render QR. Use your pass code below.");
        });
    return () => {
      alive = false;
    };
  }, [entry.qrPayload]);
  return (
    <div className="pass-panel">
      <div>
        {qr && (
          <img
            className="pass-qr"
            src={qr}
            alt={`Admission QR for ${entry.eventTitle}`}
            width={180}
            height={180}
          />
        )}
      </div>
      <div>
        <h3>{entry.checkedInAt ? "You’re checked in." : "Your entry pass"}</h3>
        <p>
          {entry.checkedInAt
            ? `Recorded ${date(entry.checkedInAt)} at ${time(entry.checkedInAt)}.`
            : "Show this QR and your student ID at the entrance. Your pass works only for this event."}
        </p>
        {error && <p role="alert">{error}</p>}
        {qr && (
          <a
            className="button secondary"
            href={qr}
            download={`seatlock-pass-${entry.id.slice(0, 8)}.png`}
          >
            <Download size={16} />
            Download QR
          </a>
        )}
        <details>
          <summary>Pass code / scanner fallback</summary>
          <code className="pass-code">{entry.qrPayload}</code>
        </details>
      </div>
    </div>
  );
}
export function MyRegistrations({
  onBrowse,
  onChange,
}: {
  onBrowse: () => void;
  onChange: () => void;
}) {
  const [entries, setEntries] = useState<Entry[]>([]),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [loading, setLoading] = useState(true),
    [cancel, setCancel] = useState<Entry | null>(null),
    [busy, setBusy] = useState(false);
  const previous = useRef<Map<string, string>>(new Map());
  const dialog = useRef<HTMLDialogElement>(null);
  async function load() {
    try {
      const rows = await api<Entry[]>("/registrations");
      for (const row of rows) {
        if (
          previous.current.get(row.id) === "WAITLISTED" &&
          row.status === "CONFIRMED"
        )
          setNotice(
            `You’re in! Your place at ${row.eventTitle} has been confirmed from the waitlist.`,
          );
      }
      previous.current = new Map(rows.map((e) => [e.id, e.status]));
      setEntries(rows);
      setError("");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
    const id = setInterval(load, 5000);
    return () => clearInterval(id);
  }, []);
  useEffect(() => {
    if (cancel) dialog.current?.showModal();
    else dialog.current?.close();
  }, [cancel]);
  async function withdraw() {
    if (!cancel) return;
    setBusy(true);
    try {
      await api(`/registrations/${cancel.id}`, "DELETE");
      setCancel(null);
      await load();
      onChange();
    } catch (e) {
      setError((e as Error).message);
      setCancel(null);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="inner-page">
      <div className="section-heading">
        <div>
          <p className="eyebrow dark">YOUR NEXT CAMPUS PLANS</p>
          <h1>My registrations</h1>
        </div>
        <div className="inline-actions">
          <button
            className="button secondary"
            onClick={() => void load()}
            aria-label="Refresh registrations"
          >
            <RefreshCw size={18} />
          </button>
          <button className="button primary" onClick={onBrowse}>
            Explore events
          </button>
        </div>
      </div>
      <p className="muted">
        Saved to your student account. Waitlist status refreshes every 5 seconds
        while this page is open.
      </p>
      {notice && (
        <p className="success-message" role="status">
          {notice}
        </p>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {loading ? (
        <div className="empty">
          <span className="spinner" />
        </div>
      ) : !entries.length ? (
        <div className="empty">
          <Ticket size={42} />
          <h2>Your next experience is waiting.</h2>
          <p>Reserve a spot at an event or join its waitlist.</p>
          <button className="button primary" onClick={onBrowse}>
            Explore events
          </button>
        </div>
      ) : (
        <div className="tickets-list">
          {entries.map((e) => (
            <article
              className={`registration-card ${e.status.toLowerCase()}`}
              key={e.id}
            >
              <div className="registration-heading">
                <div>
                  <span className={`status ${e.status.toLowerCase()}`}>
                    {e.status === "WAITLISTED" ? (
                      <Clock3 size={15} />
                    ) : e.status === "CONFIRMED" ? (
                      <CheckCircle2 size={15} />
                    ) : (
                      <X size={15} />
                    )}{" "}
                    {e.checkedInAt ? "CHECKED IN" : e.status}
                  </span>
                  <h2>{e.eventTitle}</h2>
                  <p>
                    {date(e.startsAt)} · {time(e.startsAt)} · {e.venue}
                  </p>
                  <p>
                    <strong>{e.studentName}</strong> · {e.studentNumber}
                    {e.seatCode && ` · Seat ${e.seatCode}`}
                  </p>
                </div>
                <span className="registration-ref">
                  #{e.id.slice(0, 8).toUpperCase()}
                </span>
              </div>
              {e.status === "WAITLISTED" && (
                <div className="waitlist-message">
                  <strong>#{e.waitlistPosition}</strong>
                  <div>
                    <h3>Your position in the queue</h3>
                    <p>
                      We’ll confirm your place automatically if someone cancels
                      before the event starts. Check here for your QR pass; no
                      email notification is sent.
                    </p>
                  </div>
                </div>
              )}
              {e.promotedAt && e.status === "CONFIRMED" && (
                <p className="success-message">
                  Confirmed from the waitlist on {date(e.promotedAt)} at{" "}
                  {time(e.promotedAt)}.
                </p>
              )}
              {e.qrPayload && <Pass entry={e} />}
              {e.status !== "CANCELLED" &&
                !e.checkedInAt &&
                e.startsAt > Date.now() && (
                  <div className="registration-actions">
                    <button
                      className="text-button"
                      onClick={() => setCancel(e)}
                    >
                      {e.status === "WAITLISTED"
                        ? "Leave waitlist"
                        : "Cancel registration"}
                    </button>
                  </div>
                )}
              {e.status === "CANCELLED" && (
                <p className="muted">
                  This registration is cancelled. Any previous QR pass is
                  invalid.
                </p>
              )}
            </article>
          ))}
        </div>
      )}
      <dialog
        ref={dialog}
        className="auth-dialog"
        onCancel={(e) => {
          e.preventDefault();
          if (!busy) setCancel(null);
        }}
        aria-labelledby="cancel-heading"
      >
        <h2 id="cancel-heading">
          {cancel?.status === "WAITLISTED"
            ? "Leave the waitlist?"
            : "Cancel your registration?"}
        </h2>
        <p>
          {cancel?.status === "WAITLISTED"
            ? "You’ll lose your current queue position. Rejoining puts you at the back."
            : "Your QR pass will stop working. If someone is waiting, your place will go to them automatically."}
        </p>
        <div className="modal-actions">
          <button
            className="button secondary"
            disabled={busy}
            onClick={() => setCancel(null)}
          >
            Keep registration
          </button>
          <button className="button primary" disabled={busy} onClick={withdraw}>
            {busy ? "Updating…" : "Confirm cancellation"}
          </button>
        </div>
      </dialog>
    </section>
  );
}
