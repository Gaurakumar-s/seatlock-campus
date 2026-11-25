import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  LockKeyhole,
  LogOut,
  Plus,
  RefreshCw,
  X,
  CalendarDays,
  Ticket,
  Users,
  CheckCircle2,
} from "lucide-react";
import { api, date, time, type Overview, type Entry } from "./api";
import { CheckInDesk } from "./CheckInDesk";

export function Organizer({ onEventCreated }: { onEventCreated: () => void }) {
  const signed = true;
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [overview, setOverview] = useState<Overview | null>(null),
    [create, setCreate] = useState(false),
    [mode, setMode] = useState("GENERAL"),
    [eventId, setEventId] = useState(""),
    [roster, setRoster] = useState<Entry[]>([]),
    [rosterReady, setRosterReady] = useState(false);
  const rosterContext = useRef({ eventId, signed });
  rosterContext.current = { eventId, signed };
  const rosterRequest = useRef(0);
  async function load() {
    try {
      const result = await api<Overview>(
        "/admin/overview",
        "GET",
        undefined,
        true,
      );
      setOverview(result);
      setError("");
    } catch (e) {
      setError((e as Error).message);
      if ((e as { code?: string }).code === "SESSION_EXPIRED") {
        localStorage.removeItem("seatlock-admin");
      }
    }
  }
  async function loadRoster() {
    if (!eventId) return;
    const request = ++rosterRequest.current;
    const isCurrent = () =>
      rosterContext.current.eventId === eventId &&
      rosterContext.current.signed &&
      rosterRequest.current === request;
    try {
      const result = await api<Entry[]>(
        `/admin/events/${eventId}/registrations`,
        "GET",
        undefined,
        true,
      );
      if (!isCurrent()) return;
      setRoster(result);
      setRosterReady(true);
    } catch (e) {
      if (isCurrent()) setError((e as Error).message);
    }
  }
  useEffect(() => {
    if (signed) void load();
  }, [signed]);
  useEffect(() => {
    setRoster([]);
    setRosterReady(false);
    if (signed && eventId) {
      void loadRoster();
      const timer = setInterval(loadRoster, 5000);
      return () => {
        clearInterval(timer);
        rosterRequest.current++;
      };
    }
  }, [eventId, signed]);
  async function publish(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const f = new FormData(e.currentTarget);
    try {
      const result = await api<{ id: string }>(
        "/admin/events",
        "POST",
        {
          title: f.get("title"),
          category: f.get("category"),
          venue: f.get("venue"),
          description: f.get("description"),
          startsAt: new Date(String(f.get("startsAt")) + "+05:30").getTime(),
          capacity: Number(f.get("capacity")),
          seatsPerRow: mode === "ASSIGNED" ? Number(f.get("seatsPerRow")) : 10,
          speaker: f.get("speaker"),
          hostClub: f.get("hostClub"),
          bookingMode: mode,
          audience: f.get("audience"),
        },
        true,
      );
      setCreate(false);
      setEventId(result.id);
      await load();
      onEventCreated();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const selected = overview?.events.find((e) => e.id === eventId);
  return (
    <section className="inner-page">
      <div className="section-heading">
        <div>
          <p className="eyebrow dark">BRING YOUR CAMPUS TOGETHER</p>
          <h1>Organizer overview</h1>
        </div>
        <div className="inline-actions">
          <button
            className="button secondary"
            onClick={() => {
              void load();
              void loadRoster();
            }}
            aria-label="Refresh organizer data"
          >
            <RefreshCw size={18} />
          </button>
          <button className="button primary" onClick={() => setCreate(!create)}>
            <Plus size={18} />
            New event
          </button>
        </div>
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {overview && (
        <>
          <div className="stats-grid">
            <div>
              <span>Total events</span>
              <strong>{overview.events.length}</strong>
              <CalendarDays />
            </div>
            <div>
              <span>Confirmed registrations</span>
              <strong>{overview.confirmed}</strong>
              <Ticket />
            </div>
            <div>
              <span>On waitlists</span>
              <strong>{overview.waitlisted}</strong>
              <Users />
            </div>
            <div>
              <span>Checked in</span>
              <strong>{overview.checkedIn}</strong>
              <CheckCircle2 />
            </div>
          </div>
          {create && (
            <form className="create-form" onSubmit={publish}>
              <div className="panel-heading">
                <h2>Create a campus event</h2>
                <button
                  className="icon-button"
                  type="button"
                  onClick={() => setCreate(false)}
                  aria-label="Close event form"
                >
                  <X size={20} />
                </button>
              </div>
              <div className="form-grid">
                <label>
                  Event title
                  <input name="title" required maxLength={100} />
                </label>
                <label>
                  Category
                  <select name="category">
                    <option>Guest Talk</option>
                    <option>Workshop</option>
                    <option>Cultural</option>
                    <option>Club Meetup</option>
                  </select>
                </label>
                <label>
                  Who can register?
                  <select name="audience" defaultValue="COLLEGE">
                    <option value="COLLEGE">
                      College only — verified college email
                    </option>
                    <option value="OPEN">
                      Open to everyone — any verified email
                    </option>
                  </select>
                </label>
                <label>
                  Venue
                  <input name="venue" required maxLength={120} />
                </label>
                <label>
                  Date & time (IST)
                  <input name="startsAt" type="datetime-local" required />
                </label>
                <label>
                  Capacity
                  <input
                    name="capacity"
                    type="number"
                    min="1"
                    max="500"
                    defaultValue="200"
                    required
                  />
                </label>
                <label>
                  Registration type
                  <select
                    value={mode}
                    onChange={(e) => setMode(e.target.value)}
                  >
                    <option value="GENERAL">
                      Reserve a spot · open seating
                    </option>
                    <option value="ASSIGNED">Choose a numbered seat</option>
                  </select>
                </label>
                {mode === "ASSIGNED" && (
                  <label>
                    Seats per row
                    <select name="seatsPerRow" defaultValue="10">
                      {[8, 10, 12, 16, 20].map((x) => (
                        <option key={x}>{x}</option>
                      ))}
                    </select>
                  </label>
                )}
                <label>
                  Host club / department
                  <input name="hostClub" required maxLength={100} />
                </label>
                <label>
                  Speaker / performers (optional)
                  <input name="speaker" maxLength={100} />
                </label>
              </div>
              <label>
                Description
                <textarea
                  name="description"
                  rows={3}
                  required
                  maxLength={2000}
                />
              </label>
              <p className="muted">
                Free entry. One registration per student. Full events
                automatically accept a waitlist; cancellations promote the
                earliest waiting student.
              </p>
              <button className="button primary" disabled={busy}>
                {busy ? "Creating…" : "Create event"}
              </button>
            </form>
          )}
          <div className="table-panel">
            <h2>Your campus events</h2>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Event</th>
                    <th>Date</th>
                    <th>Registration</th>
                    <th>Available</th>
                    <th>Waitlist</th>
                    <th>Attendance</th>
                    <th>Manage</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.events.map((e) => (
                    <tr key={e.id}>
                      <td>
                        <strong>{e.title}</strong>
                        <small>
                          {e.audience === "OPEN"
                            ? "Open to everyone"
                            : "College only"}
                        </small>
                        <small>{e.venue}</small>
                      </td>
                      <td>
                        {date(e.startsAt)}
                        <small>{time(e.startsAt)}</small>
                      </td>
                      <td>
                        {e.bookingMode === "GENERAL"
                          ? "Open seating"
                          : "Numbered seats"}
                      </td>
                      <td>
                        {e.available} / {e.capacity}
                      </td>
                      <td>{e.waitlisted}</td>
                      <td>{e.checkedIn}</td>
                      <td>
                        <button
                          className="button secondary"
                          onClick={() => setEventId(e.id)}
                        >
                          Manage {e.title}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
          {selected && (
            <div className="event-management" key={selected.id}>
              <div className="section-heading">
                <div>
                  <p className="eyebrow dark">EVENT MANAGEMENT</p>
                  <h2>{selected.title}</h2>
                </div>
                <button className="text-button" onClick={() => setEventId("")}>
                  Close event
                </button>
              </div>
              <CheckInDesk
                event={selected}
                onCheckedIn={() => {
                  void load();
                  void loadRoster();
                }}
              />
              <div className="table-panel">
                <h2>Student registrations</h2>
                <p className="muted">
                  All registrations for this event. Queue order follows the
                  server’s registration sequence.
                </p>
                {!rosterReady ? (
                  <p>Loading registrations…</p>
                ) : !roster.length ? (
                  <p className="muted">No registrations yet.</p>
                ) : (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>Student</th>
                          <th>Status</th>
                          <th>Seat / queue</th>
                          <th>Checked in</th>
                        </tr>
                      </thead>
                      <tbody>
                        {roster.map((e) => (
                          <tr key={e.id}>
                            <td>
                              <strong>{e.studentName}</strong>
                              <small>{e.studentNumber}</small>
                            </td>
                            <td>
                              <span
                                className={`status ${e.status.toLowerCase()}`}
                              >
                                {e.status}
                              </span>
                              {e.promotedAt && (
                                <small>Promoted from waitlist</small>
                              )}
                            </td>
                            <td>
                              {e.status === "WAITLISTED"
                                ? `#${e.waitlistPosition}`
                                : e.seatCode || "—"}
                            </td>
                            <td>{e.checkedInAt ? time(e.checkedInAt) : "—"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
