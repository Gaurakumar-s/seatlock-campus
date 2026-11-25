import { useEffect, useRef, useState } from "react";
import {
  CalendarDays,
  MapPin,
  ChevronLeft,
  Ticket,
  CheckCircle2,
  Users,
  ShieldCheck,
} from "lucide-react";
import {
  api,
  date,
  time,
  type Event,
  type Entry,
  type Student,
  type Seat,
} from "./api";

export function EventDetail({
  eventId,
  student,
  onLogin,
  onTickets,
  onBack,
  onRegistered,
}: {
  eventId: string;
  student: Student | null;
  onLogin: () => void;
  onTickets: () => void;
  onBack: () => void;
  onRegistered: (e: Entry) => void;
}) {
  const [event, setEvent] = useState<Event | null>(null),
    [entry, setEntry] = useState<Entry | null>(null),
    [seats, setSeats] = useState<Seat[]>([]),
    [chosen, setChosen] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const requestKey = useRef(crypto.randomUUID());
  useEffect(() => {
    let alive = true;
    async function refresh() {
      try {
        const e = await api<Event>(`/events/${eventId}`);
        const [ss, entries] = await Promise.all([
          e.bookingMode === "ASSIGNED"
            ? api<Seat[]>(`/events/${eventId}/seats`)
            : Promise.resolve([]),
          student ? api<Entry[]>("/registrations") : Promise.resolve([]),
        ]);
        if (alive) {
          setEvent(e);
          setSeats(ss);
          setEntry(
            entries.find(
              (x) => x.eventId === eventId && x.status !== "CANCELLED",
            ) || null,
          );
        }
      } catch (e) {
        if (alive) setError((e as Error).message);
      }
    }
    void refresh();
    const timer = setInterval(refresh, 5000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [eventId, student?.id]);
  async function register() {
    if (!student || !student.emailVerified) {
      onLogin();
      return;
    }
    setBusy(true);
    setError("");
    try {
      const result = await api<Entry>(
        `/events/${eventId}/registrations`,
        "POST",
        {
          requestKey: requestKey.current,
          seatCode:
            event?.bookingMode === "ASSIGNED" && event.available > 0
              ? chosen
              : null,
        },
      );
      onRegistered(result);
    } catch (e) {
      setError((e as Error).message);
      try {
        setEvent(await api<Event>(`/events/${eventId}`));
        setSeats(await api<Seat[]>(`/events/${eventId}/seats`));
      } catch {
        /* Original error remains visible. */
      }
    } finally {
      setBusy(false);
    }
  }
  if (!event)
    return (
      <section className="empty">
        {error ? (
          <>
            <p role="alert">{error}</p>
            <button className="button secondary" onClick={onBack}>
              Back to events
            </button>
          </>
        ) : (
          <>
            <span className="spinner" />
            Loading event…
          </>
        )}
      </section>
    );
  const full = event.available === 0,
    assigned = event.bookingMode === "ASSIGNED";
  return (
    <section className="booking-page">
      <button className="back" onClick={onBack}>
        <ChevronLeft size={18} />
        All campus events
      </button>
      <div className="detail-heading">
        <div>
          <span className="pill">{event.category}</span>
          <h1>{event.title}</h1>
          <p>
            {event.organizationName} ·{" "}
            {event.audience === "OPEN"
              ? "Open to everyone with a verified email"
              : "College only — verified college email required"}
          </p>
          <div className="detail-meta">
            <span>
              <CalendarDays size={17} />
              {date(event.startsAt)} · {time(event.startsAt)}
            </span>
            <span>
              <MapPin size={17} />
              {event.venue}
            </span>
          </div>
        </div>
      </div>
      <div className="booking-layout registration-layout">
        <div>
          <div className="event-story">
            <p className="eyebrow dark">ABOUT THIS EVENT</p>
            <h2>Show up for something good.</h2>
            <p>{event.description}</p>
            <dl>
              <div>
                <dt>Hosted by</dt>
                <dd>{event.hostClub}</dd>
              </div>
              {event.speaker && (
                <div>
                  <dt>Speaker / performers</dt>
                  <dd>{event.speaker}</dd>
                </div>
              )}
              <div>
                <dt>Seating</dt>
                <dd>
                  {assigned
                    ? "Choose a numbered seat"
                    : "Open seating · choose a chair on arrival"}
                </dd>
              </div>
            </dl>
          </div>
          {assigned && !entry && !full && (
            <div className="seat-panel">
              <div className="panel-heading">
                <div>
                  <h2>Choose your seat</h2>
                  <p>One numbered seat per student. Updates every 5 seconds.</p>
                </div>
              </div>
              <div className="stage">THE STAGE</div>
              <div className="seat-scroll">
                <div className="seat-grid" aria-label="Seat selection">
                  {Array.from(
                    new Set(seats.map((s) => s.code.match(/^[A-Z]+/)![0])),
                  ).map((row) => (
                    <div className="seat-row" key={row}>
                      <span className="row-label">{row}</span>
                      {seats
                        .filter((s) => s.code.match(/^[A-Z]+/)![0] === row)
                        .map((s) => (
                          <button
                            key={s.code}
                            className={`seat ${chosen === s.code ? "picked" : s.status.toLowerCase()}`}
                            disabled={busy || s.status === "BOOKED"}
                            aria-label={`Seat ${s.code}, ${chosen === s.code ? "selected" : s.status.toLowerCase()}`}
                            aria-pressed={chosen === s.code}
                            onClick={() => setChosen(s.code)}
                          >
                            {s.code.replace(/^[A-Z]+/, "")}
                          </button>
                        ))}
                      <span className="row-label">{row}</span>
                    </div>
                  ))}
                </div>
              </div>
              <p className="muted">
                Scroll the map to see all rows and seats. A seat is confirmed
                when registration succeeds.
              </p>
            </div>
          )}
          <div className="how-it-works">
            <h2>Good to know</h2>
            <p>
              <CheckCircle2 size={20} />
              One active registration per student for this event.
            </p>
            <p>
              <Users size={20} />
              When full, join the queue. Cancelled places go to waiting students
              in order.
            </p>
            <p>
              <Ticket size={20} />
              Confirmed students get a QR pass. Bring it with your student ID.
            </p>
          </div>
        </div>
        <aside className="order-panel">
          <div className="order-top">
            <Ticket size={23} />
            <span>YOUR CAMPUS PASS</span>
          </div>
          <h2>
            {entry
              ? entry.status === "WAITLISTED"
                ? "You’re in the queue."
                : "You’re on the list."
              : full
                ? "Worth the wait."
                : "Reserve your spot."}
          </h2>
          <p>
            {entry
              ? entry.status === "WAITLISTED"
                ? `You are currently #${entry.waitlistPosition}. Your pass updates automatically if a place opens.`
                : "Your confirmed registration is saved in My registrations."
              : full
                ? "This event is full. Join the waitlist and we’ll confirm you automatically when a place opens."
                : "Get instant confirmation. No payment or extra checkout step."}
          </p>
          <div className="availability-number">
            <strong>{event.available}</strong>
            <span>of {event.capacity} places available</span>
          </div>
          <div className="capacity">
            <span
              style={{
                width: `${(100 * (event.capacity - event.available)) / event.capacity}%`,
              }}
            />
          </div>
          <div className="order-line">
            <span>Waiting students</span>
            <strong>{event.waitlisted}</strong>
          </div>
          <div className="order-line">
            <span>Entry</span>
            <strong>Free</strong>
          </div>
          {assigned && chosen && !full && !entry && (
            <div className="order-line">
              <span>Selected seat</span>
              <strong>{chosen}</strong>
            </div>
          )}
          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}
          <button
            className="button primary full"
            disabled={
              busy ||
              (!entry && !!student && assigned && !full && !chosen) ||
              event.startsAt <= Date.now()
            }
            onClick={entry ? onTickets : register}
          >
            {busy
              ? "Registering…"
              : entry
                ? "View my registration"
                : !student
                  ? "Sign in to register"
                  : !student.emailVerified
                    ? "Verify email to register"
                    : full
                      ? "Join waitlist"
                      : "Reserve my spot"}
          </button>
          <p className="checkout-note">
            <ShieldCheck size={17} />
            {assigned && full
              ? "Waitlist promotion assigns the next freed seat."
              : "Cancel before the event starts if you can’t attend."}
          </p>
        </aside>
      </div>
    </section>
  );
}
