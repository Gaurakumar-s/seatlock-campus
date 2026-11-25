import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  Ticket,
  MapPin,
  Clock3,
  Check,
  X,
  LockKeyhole,
  CalendarDays,
  GraduationCap,
  SlidersHorizontal,
  ShieldCheck,
  Plus,
  LogOut,
  RefreshCw,
  CheckCircle2,
  Armchair,
  Search,
  ChevronLeft,
  Download,
  Sparkles,
} from "lucide-react";
import {
  api,
  connect,
  date,
  time,
  type Event,
  type Student,
  type Organization,
} from "./api";
import { EventDetail } from "./EventDetail";
import { MyRegistrations } from "./MyRegistrations";
import { OrganizerWorkspace } from "./OrganizerWorkspace";
import { VerifyEmail } from "./VerifyEmail";
import "./style.css";
import { StudentAuth } from "./StudentAuth";

function Art({ theme, compact = false }: { theme: string; compact?: boolean }) {
  return (
    <div
      className={`art ${theme} ${compact ? "compact" : ""}`}
      aria-hidden="true"
    >
      <div className="art-grid" />
      <div className="orb orb-one" />
      <div className="orb orb-two" />
      <div className="art-mark">
        {theme === "coral" ? (
          <GraduationCap />
        ) : theme === "violet" ? (
          <Sparkles />
        ) : (
          <span>hello.</span>
        )}
      </div>
      <span className="art-caption">
        {theme === "coral"
          ? "IDEAS / QUESTIONS / POSSIBILITIES"
          : theme === "violet"
            ? "IDEAS BECOME REAL"
            : "YOUR CAMPUS. YOUR COMMUNITY."}
      </span>
    </div>
  );
}
type View = "discover" | "bookings" | "organizer";
function App() {
  const [student, setStudent] = useState<Student | null>(null);
  const [authOpen, setAuthOpen] = useState(false);
  const [view, setView] = useState<View>("discover");
  const [events, setEvents] = useState<Event[]>([]);
  const [selected, setSelected] = useState<Event | null>(null);
  const [filter, setFilter] = useState("All events");
  const [query, setQuery] = useState("");
  const [college, setCollege] = useState("");
  const [audience, setAudience] = useState("");
  const [colleges, setColleges] = useState<Organization[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [mailMode, setMailMode] = useState("");
  const refresh = async () => {
    const [list, organizations] = await Promise.all([
      api<Event[]>("/events"),
      api<Organization[]>("/organizations"),
    ]);
    setColleges(organizations);
    setEvents(list);
    return list;
  };
  const boot = async () => {
    setLoading(true);
    setError("");
    try {
      setStudent(await connect());
      setMailMode((await api<{ mode: string }>("/auth/config")).mode);
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    void boot();
  }, []);
  useEffect(() => {
    if (notice) {
      const t = setTimeout(() => setNotice(""), 6000);
      return () => clearTimeout(t);
    }
  }, [notice]);
  function navigate(v: View) {
    setView(v);
    setSelected(null);
    void refresh().catch(() => {});
  }
  async function signOut() {
    try {
      await api("/auth/logout", "POST");
      localStorage.removeItem("seatlock-session");
      setStudent(null);
      setNotice("Signed out. Your registrations are saved to your account.");
    } catch (e) {
      setNotice((e as Error).message);
    }
  }
  const shown = events.filter(
    (e) =>
      (filter === "All events" || e.category === filter) &&
      (!college || e.organizationId === college) &&
      (!audience || e.audience === audience) &&
      (e.title + " " + e.venue).toLowerCase().includes(query.toLowerCase()),
  );
  return (
    <>
      <header className="header">
        <div className="nav-wrap">
          <button
            className="brand"
            onClick={() => navigate("discover")}
            aria-label="SeatLock Campus home"
          >
            <span className="brand-icon">
              <Ticket size={24} />
            </span>
            seatlock<span className="brand-period">.</span>
            <span className="campus-label">CAMPUS</span>
          </button>
          <nav aria-label="Main navigation">
            <button
              className={view === "discover" ? "nav-link active" : "nav-link"}
              onClick={() => navigate("discover")}
            >
              Discover
            </button>
            <button
              className={view === "bookings" ? "nav-link active" : "nav-link"}
              onClick={() => navigate("bookings")}
            >
              My registrations
            </button>
          </nav>
          <button
            className={`organizer-link ${view === "organizer" ? "active" : ""}`}
            onClick={() => navigate("organizer")}
          >
            Organizer space <span className="avatar">O</span>
          </button>
          <button
            className="student-nav"
            onClick={student ? () => void signOut() : () => setAuthOpen(true)}
          >
            {student ? (
              <>
                <span className="avatar">{student.name[0].toUpperCase()}</span>
                <span>Sign out</span>
              </>
            ) : (
              <>Student login</>
            )}
          </button>
        </div>
      </header>
      {notice && (
        <div className="toast" role="status">
          <CheckCircle2 size={20} />
          {notice}
          <button
            aria-label="Dismiss notification"
            onClick={() => setNotice("")}
          >
            <X size={16} />
          </button>
        </div>
      )}
      {authOpen && (
        <StudentAuth
          onClose={() => setAuthOpen(false)}
          onSuccess={(s) => {
            setStudent(s);
            setAuthOpen(false);
            setNotice(`Welcome, ${s.name.split(" ")[0]}.`);
          }}
        />
      )}
      <main>
        {mailMode === "development" && (
          <div className="demo-notice">
            <strong>Local email demo</strong> · Verification uses a test inbox.
            Real email delivery is not connected.
          </div>
        )}
        {student && !student.emailVerified && view !== "organizer" && (
          <VerifyEmail
            email={student.email}
            onVerified={() => void connect().then(setStudent)}
          />
        )}
        {loading ? (
          <div className="empty">
            <span className="spinner" />
            <h2>Finding your next moment…</h2>
          </div>
        ) : error ? (
          <div className="empty" role="alert">
            <h2>We couldn’t connect.</h2>
            <p>{error}</p>
            <button className="button primary" onClick={boot}>
              <RefreshCw size={16} />
              Try again
            </button>
          </div>
        ) : view === "organizer" ? (
          <OrganizerWorkspace
            onEventCreated={() => {
              void refresh();
              setNotice("Your event is ready for reservations.");
            }}
          />
        ) : view === "bookings" && !student ? (
          <section className="empty">
            <Ticket size={40} />
            <h2>Your campus plans, in one place.</h2>
            <p>
              Sign in to see the registrations saved to your student account.
            </p>
            <button
              className="button primary"
              onClick={() => setAuthOpen(true)}
            >
              Student login
            </button>
          </section>
        ) : view === "bookings" ? (
          <MyRegistrations
            onBrowse={() => navigate("discover")}
            onChange={() => {
              void refresh();
            }}
          />
        ) : selected ? (
          <EventDetail
            key={selected.id + student?.id}
            eventId={selected.id}
            student={student}
            onLogin={() => setAuthOpen(true)}
            onTickets={() => navigate("bookings")}
            onBack={() => {
              setSelected(null);
              void refresh();
            }}
            onRegistered={(entry) => {
              navigate("bookings");
              setNotice(
                entry.status === "WAITLISTED"
                  ? "You’re on the waitlist. We’ll update your pass when a place opens."
                  : "Your place is confirmed. Your QR pass is ready.",
              );
            }}
          />
        ) : (
          <>
            <section className="college-filters">
              <label>
                College
                <select
                  value={college}
                  onChange={(e) => setCollege(e.target.value)}
                >
                  <option value="">All colleges</option>
                  {colleges.map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.name}
                      {o.demo ? " (demo)" : ""}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Event access
                <select
                  value={audience}
                  onChange={(e) => setAudience(e.target.value)}
                >
                  <option value="">All events</option>
                  <option value="COLLEGE">College only</option>
                  <option value="OPEN">Open to everyone</option>
                </select>
              </label>
            </section>
            <section className="hero">
              <div className="hero-copy">
                <div className="eyebrow">
                  <span className="mini-line" /> YOUR CAMPUS. YOUR NEXT
                  EXPERIENCE.
                </div>
                <h1>
                  Big ideas.
                  <br />
                  <em>Your spot.</em>
                </h1>
                <p>
                  Guest talks, workshops and campus evenings.
                  <br className="desktop" /> One student. One registration.
                  Something to look forward to.
                </p>
                <div className="hero-bottom">
                  <span className="location">
                    <MapPin size={17} />
                    Around your campus
                  </span>
                  <span className="hero-divider" />
                  <span>Show up for something good.</span>
                </div>
              </div>
              <div className="hero-visual">
                <div className="hero-circle" />
                <div className="floating-word">BE THERE.</div>
                <div className="hero-ticket">
                  <div className="ticket-top">
                    <span>THE CAMPUS EDITION</span>
                    <Ticket size={24} />
                  </div>
                  <div className="ticket-big">
                    Your spot.
                    <br />
                    Your campus.
                  </div>
                  <div className="ticket-dashes" />
                  <div className="ticket-bottom">
                    <span>
                      ADMIT ONE
                      <br />
                      <strong>ONE STUDENT · ONE PASS</strong>
                    </span>
                    <span className="barcode" />
                  </div>
                </div>
                <span className="sticker">
                  SEE YOU
                  <br />
                  ON CAMPUS
                </span>
                <Sparkles className="hero-sparkle" size={36} />
              </div>
            </section>
            <section className="discovery">
              <div className="section-heading">
                <div>
                  <p className="eyebrow dark">FIND YOUR PEOPLE</p>
                  <h2>On the calendar</h2>
                </div>
                <span className="result-count">
                  {shown.length} upcoming events
                </span>
              </div>
              <div className="filter-row">
                <div className="filters" aria-label="Event category">
                  {[
                    "All events",
                    "Guest Talk",
                    "Workshop",
                    "Cultural",
                    "Club Meetup",
                  ].map((f) => (
                    <button
                      key={f}
                      className={filter === f ? "filter selected" : "filter"}
                      onClick={() => setFilter(f)}
                    >
                      {f === "All events" && <SlidersHorizontal size={15} />}{" "}
                      {f}
                    </button>
                  ))}
                </div>
                <label className="search">
                  <Search size={17} />
                  <input
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder="Find a campus event"
                    aria-label="Search events"
                  />
                </label>
              </div>
              <div className="event-grid">
                {shown.map((e) => (
                  <button
                    className="event-card"
                    key={e.id}
                    onClick={() => setSelected(e)}
                  >
                    <div className="card-image">
                      <Art theme={e.theme} />
                      <span className="category-badge">{e.category}</span>
                      <span className="date-badge">
                        <strong>
                          {new Date(e.startsAt).toLocaleDateString("en-IN", {
                            day: "2-digit",
                            timeZone: "Asia/Kolkata",
                          })}
                        </strong>
                        {new Date(e.startsAt)
                          .toLocaleDateString("en-IN", {
                            month: "short",
                            timeZone: "Asia/Kolkata",
                          })
                          .toUpperCase()}
                      </span>
                    </div>
                    <div className="card-content">
                      <div className="event-meta">
                        {time(e.startsAt)} <span>•</span> {e.available} places
                        available
                      </div>
                      <h3>{e.title}</h3>
                      <p className="event-access">
                        {e.organizationName} ·{" "}
                        {e.audience === "OPEN"
                          ? "Open to everyone"
                          : "College only"}
                      </p>
                      <p>
                        <MapPin size={15} />
                        {e.venue}
                      </p>
                      <div className="card-bottom">
                        <span>
                          <strong>Free</strong> · {e.capacity} places
                        </span>
                        <span className="choose-label">
                          {e.available === 0
                            ? "Join waitlist"
                            : e.bookingMode === "ASSIGNED"
                              ? "Choose seat"
                              : "Reserve my spot"}{" "}
                          <Ticket size={17} />
                        </span>
                      </div>
                    </div>
                  </button>
                ))}
              </div>
              {!shown.length && (
                <div className="empty">
                  <Search size={32} />
                  <h3>No events match just yet.</h3>
                  <p>Try another name or category.</p>
                  <button
                    className="button secondary"
                    onClick={() => {
                      setQuery("");
                      setFilter("All events");
                    }}
                  >
                    Clear filters
                  </button>
                </div>
              )}
            </section>
            <section className="promise">
              <div className="promise-icon">
                <ShieldCheck size={30} />
              </div>
              <div>
                <h3>A place for you. A fair chance for everyone.</h3>
                <p>
                  Reserve a spot. Join the waitlist if full. Cancel when plans
                  change.
                </p>
              </div>
              <span className="promise-note">
                A little less rush.
                <br />A lot more certainty.
              </span>
            </section>
          </>
        )}
      </main>
      <footer>
        <span className="footer-brand">seatlock.</span>
        <span>One student. One registration. Every campus event.</span>
        <span className="demo-tag">Portfolio demo · Sample campus events</span>
      </footer>
    </>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
