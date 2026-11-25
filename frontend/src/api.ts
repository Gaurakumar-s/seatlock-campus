export type Event = {
  id: string;
  title: string;
  category: string;
  venue: string;
  description: string;
  startsAt: number;
  theme: string;
  available: number;
  capacity: number;
  speaker: string;
  hostClub: string;
  seatsPerRow: number;
  bookingMode: "GENERAL" | "ASSIGNED";
  waitlisted: number;
  checkedIn: number;
  organizationId: string;
  organizationName: string;
  audience: "COLLEGE" | "OPEN";
};
export type Seat = { code: string; status: "AVAILABLE" | "BOOKED" };
export type Entry = {
  id: string;
  eventId: string;
  eventTitle: string;
  venue: string;
  startsAt: number;
  status: "CONFIRMED" | "WAITLISTED" | "CANCELLED";
  seatCode: string | null;
  waitlistPosition: number;
  createdAt: number;
  promotedAt: number | null;
  checkedInAt: number | null;
  studentName: string;
  studentNumber: string;
  qrPayload: string | null;
  bookingMode: "GENERAL" | "ASSIGNED";
};
export type Overview = {
  events: Event[];
  confirmed: number;
  waitlisted: number;
  checkedIn: number;
};
export type CheckInResult = { registration: Entry; alreadyCheckedIn: boolean };
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
  admin = false,
): Promise<T> {
  const token = localStorage.getItem(
    admin ? "seatlock-admin" : "seatlock-session",
  );
  const response = await fetch("/api" + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(admin && localStorage.getItem("seatlock-organization")
        ? {
            "X-Organization-ID": localStorage.getItem("seatlock-organization")!,
          }
        : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok)
    throw new ApiError(
      data.code || "REQUEST_FAILED",
      data.message || "Something went wrong. Please try again.",
    );
  return data;
}
export type Student = {
  id: string;
  name: string;
  email: string;
  studentNumber: string;
  emailVerified: boolean;
};
export type OrganizerAccount = {
  id: string;
  name: string;
  email: string;
  emailVerified: boolean;
};
export type Organization = {
  id: string;
  name: string;
  emailDomain: string;
  demo: boolean;
  role: string | null;
};
export type Session = {
  token: string;
  role: string;
  student: Student;
  organizer: OrganizerAccount;
};
export async function connect(): Promise<Student | null> {
  if (!localStorage.getItem("seatlock-session")) return null;
  try {
    return await api<Student>("/auth/me");
  } catch (e) {
    if (e instanceof ApiError && e.code === "SESSION_EXPIRED") {
      localStorage.removeItem("seatlock-session");
      return null;
    }
    throw e;
  }
}
export const money = (paise: number) =>
  paise === 0
    ? "Free"
    : new Intl.NumberFormat("en-IN", {
        style: "currency",
        currency: "INR",
        maximumFractionDigits: 2,
      }).format(paise / 100);
export const date = (ms: number) =>
  new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "Asia/Kolkata",
  }).format(ms);
export const time = (ms: number) =>
  new Intl.DateTimeFormat("en-IN", {
    hour: "numeric",
    minute: "2-digit",
    timeZone: "Asia/Kolkata",
  }).format(ms) + " IST";
