import { useEffect, useState, type FormEvent } from "react";
import { Building2, LogOut, Plus } from "lucide-react";
import {
  api,
  type OrganizerAccount,
  type Organization,
  type Session,
} from "./api";
import { VerifyEmail } from "./VerifyEmail";
import { Organizer } from "./Organizer";

type Member = { id: string; name: string; email: string; role: string };
export function OrganizerWorkspace({
  onEventCreated,
}: {
  onEventCreated: () => void;
}) {
  const [account, setAccount] = useState<OrganizerAccount | null>(null),
    [ready, setReady] = useState(false),
    [register, setRegister] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [organizations, setOrganizations] = useState<Organization[]>([]),
    [selected, setSelected] = useState(""),
    [creating, setCreating] = useState(false),
    [team, setTeam] = useState<Member[]>([]),
    [showTeam, setShowTeam] = useState(false),
    [mailMode, setMailMode] = useState("");
  async function loadAccount() {
    const a = await api<OrganizerAccount>(
      "/organizer/me",
      "GET",
      undefined,
      true,
    );
    setAccount(a);
    if (a.emailVerified) {
      const list = await api<Organization[]>(
        "/organizer/organizations",
        "GET",
        undefined,
        true,
      );
      setOrganizations(list);
      const remembered = localStorage.getItem("seatlock-organization");
      const id = list.find((o) => o.id === remembered)?.id || list[0]?.id || "";
      choose(id);
    }
  }
  function choose(id: string) {
    localStorage.setItem("seatlock-organization", id);
    setSelected(id);
    setTeam([]);
    setShowTeam(false);
  }
  useEffect(() => {
    void api<{ mode: string }>("/auth/config").then((r) => setMailMode(r.mode));
    if (!localStorage.getItem("seatlock-admin")) {
      setReady(true);
      return;
    }
    void loadAccount()
      .catch((e) => {
        setError(e.message);
        localStorage.removeItem("seatlock-admin");
      })
      .finally(() => setReady(true));
  }, []);
  async function login(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const f = new FormData(e.currentTarget);
    try {
      const session = await api<Session>(
        register ? "/organizer/register" : "/organizer/login",
        "POST",
        {
          email: f.get("email"),
          password: f.get("password"),
          ...(register ? { name: f.get("name") } : {}),
        },
        true,
      );
      localStorage.setItem("seatlock-admin", session.token);
      await loadAccount();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const f = new FormData(e.currentTarget);
    try {
      const o = await api<Organization>(
        "/organizer/organizations",
        "POST",
        { name: f.get("name"), emailDomain: f.get("emailDomain") },
        true,
      );
      setOrganizations((v) => [...v, o]);
      choose(o.id);
      setCreating(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function loadTeam() {
    setError("");
    try {
      setTeam(
        await api<Member[]>(
          `/organizer/organizations/${selected}/team`,
          "GET",
          undefined,
          true,
        ),
      );
      setShowTeam(true);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  async function add(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    setBusy(true);
    setError("");
    try {
      await api(
        `/organizer/organizations/${selected}/team`,
        "POST",
        { email: new FormData(form).get("email") },
        true,
      );
      form.reset();
      await loadTeam();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const org = organizations.find((o) => o.id === selected);
  if (!ready)
    return <section className="empty">Loading organizer account…</section>;
  if (!account)
    return (
      <section className="organizer-login">
        <div className="login-symbol">
          <Building2 />
        </div>
        <p className="eyebrow dark">YOUR COLLEGE. YOUR COMMUNITY.</p>
        <h1>{register ? "Create organizer account" : "Organizer sign in"}</h1>
        <p>
          Use your own college email. Each workspace has a separate team and
          event list.
        </p>
        <form onSubmit={login}>
          {register && (
            <label>
              Full name
              <input name="name" autoComplete="name" maxLength={80} required />
            </label>
          )}
          <label>
            Organizer email
            <input
              name="email"
              type="email"
              autoComplete="email"
              required
              maxLength={160}
            />
          </label>
          <label>
            Password
            <input
              name="password"
              type="password"
              autoComplete={register ? "new-password" : "current-password"}
              minLength={register ? 10 : undefined}
              maxLength={72}
              required
            />
          </label>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <button className="button primary full" disabled={busy}>
            {busy
              ? "Please wait…"
              : register
                ? "Create organizer account"
                : "Sign in as organizer"}
          </button>
        </form>
        <button
          className="text-button full"
          onClick={() => {
            setRegister(!register);
            setError("");
          }}
        >
          {register
            ? "Already have an account? Sign in"
            : "New organizer? Create an account"}
        </button>
        {mailMode === "development" && (
          <details className="demo-notice">
            <summary>Try the fictional Sample Campus</summary>
            <p>
              Email: organizer@campus.example.test
              <br />
              Password: Campus-demo-2026!
            </p>
            <p>Use the local test inbox to verify this demo account.</p>
          </details>
        )}
      </section>
    );
  return (
    <>
      <section className="workspace-bar">
        <div>
          <p className="eyebrow dark">ORGANIZER ACCOUNT</p>
          <strong>{account.name}</strong>
          <p>
            {account.email} ·{" "}
            {account.emailVerified ? "Email verified" : "Verification needed"}
          </p>
        </div>
        <button
          className="button secondary"
          onClick={() =>
            void api("/auth/logout", "POST", undefined, true)
              .then(() => {
                localStorage.removeItem("seatlock-admin");
                localStorage.removeItem("seatlock-organization");
                setAccount(null);
                setOrganizations([]);
                setSelected("");
              })
              .catch((e) => setError(e.message))
          }
        >
          <LogOut size={17} />
          Sign out organizer
        </button>
      </section>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {!account.emailVerified ? (
        <VerifyEmail
          email={account.email}
          organizer
          onVerified={() =>
            void loadAccount().catch((e) => setError(e.message))
          }
        />
      ) : (
        <>
          <section className="workspace-controls">
            <label>
              College workspace
              <select
                value={selected}
                onChange={(e) => choose(e.target.value)}
                disabled={!organizations.length}
              >
                {!organizations.length && (
                  <option value="">No workspaces yet</option>
                )}
                {organizations.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                    {o.demo ? " (demo)" : ""}
                  </option>
                ))}
              </select>
            </label>
            <button
              className="button secondary"
              onClick={() => setCreating(!creating)}
            >
              <Plus size={17} />
              New college workspace
            </button>
            {org && (
              <button
                className="button secondary"
                onClick={() =>
                  showTeam ? setShowTeam(false) : void loadTeam()
                }
              >
                Organizer team
              </button>
            )}
          </section>
          {creating && (
            <form className="create-form" onSubmit={create}>
              <h2>Set up your college workspace</h2>
              <p>
                Use the exact domain of your verified college email. This
                creates an organizer-managed workspace, not an official
                endorsement by the college.
              </p>
              <div className="form-grid">
                <label>
                  College / organization name
                  <input
                    name="name"
                    maxLength={100}
                    required
                    placeholder="Your college name"
                  />
                </label>
                <label>
                  College email domain
                  <input
                    name="emailDomain"
                    defaultValue={account.email.split("@")[1]}
                    maxLength={160}
                    required
                    placeholder="students.yourcollege.edu"
                  />
                </label>
              </div>
              <p className="muted">
                College-only events require this exact domain. Open events
                accept any verified email. If students and staff use different
                domains, domain administration is a future extension.
              </p>
              <button className="button primary" disabled={busy}>
                Create workspace
              </button>
            </form>
          )}
          {showTeam && org && (
            <section className="team-panel">
              <h2>{org.name} organizer team</h2>
              <p>
                Members use their own verified accounts. Only the owner can add
                or remove organizers.
              </p>
              <ul>
                {team.map((m) => (
                  <li key={m.id}>
                    <div>
                      <strong>{m.name}</strong>
                      <p>
                        {m.email} · {m.role}
                      </p>
                    </div>
                    {org.role === "OWNER" && m.role !== "OWNER" && (
                      <button
                        className="button secondary"
                        onClick={() =>
                          void api(
                            `/organizer/organizations/${org.id}/team/${m.id}`,
                            "DELETE",
                            undefined,
                            true,
                          )
                            .then(loadTeam)
                            .catch((e) => setError(e.message))
                        }
                      >
                        Remove {m.name}
                      </button>
                    )}
                  </li>
                ))}
              </ul>
              {org.role === "OWNER" && (
                <form onSubmit={add}>
                  <label>
                    Add a verified organizer
                    <input
                      name="email"
                      type="email"
                      required
                      placeholder={`name@${org.emailDomain}`}
                    />
                  </label>
                  <p className="muted">
                    They must first create an organizer account and verify this
                    college email.
                  </p>
                  <button className="button primary" disabled={busy}>
                    Add organizer
                  </button>
                </form>
              )}
            </section>
          )}
          {org ? (
            <>
              <div className="workspace-context">
                <strong>{org.name}</strong>
                <span>
                  @{org.emailDomain} ·{" "}
                  {org.demo
                    ? "Fictional demo workspace"
                    : "Organizer-managed workspace"}
                </span>
              </div>
              <Organizer key={org.id} onEventCreated={onEventCreated} />
            </>
          ) : (
            !creating && (
              <section className="empty">
                <Building2 size={40} />
                <h2>Your college starts here.</h2>
                <p>
                  Create a workspace, or ask its owner to add your verified
                  organizer email.
                </p>
              </section>
            )
          )}
        </>
      )}
    </>
  );
}
