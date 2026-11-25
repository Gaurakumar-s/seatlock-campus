package dev.seatlock;

import static dev.seatlock.Models.*;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
  private final JdbcTemplate db;
  private final Clock clock;
  private final OrganizationService organizations;
  private final SecureRandom random = new SecureRandom();

  public RegistrationService(JdbcTemplate db, Clock clock, OrganizationService organizations) {
    this.db = db;
    this.clock = clock;
    this.organizations = organizations;
  }

  private static final String EVENT_QUERY =
      "SELECT e.*,o.name AS organization_name, e.capacity-(SELECT COUNT(*) FROM event_entries x"
          + " WHERE x.event_id=e.id AND x.status='CONFIRMED') AS available, (SELECT COUNT(*) FROM"
          + " event_entries x WHERE x.event_id=e.id AND x.status='WAITLISTED') AS waitlisted,"
          + " (SELECT COUNT(*) FROM event_entries x WHERE x.event_id=e.id AND x.checked_in_at IS"
          + " NOT NULL) AS checked_in FROM events e JOIN organizations o ON o.id=e.organization_id"
          + " ";
  private static final String ENTRY_QUERY =
      "SELECT x.*,e.title AS event_title,e.venue,e.starts_at,e.booking_mode,s.name AS"
          + " student_name,s.student_number, CASE WHEN x.status='WAITLISTED' THEN (SELECT COUNT(*)"
          + " FROM event_entries w WHERE w.event_id=x.event_id AND w.status='WAITLISTED' AND"
          + " w.queue_sequence<=x.queue_sequence) ELSE 0 END AS position FROM event_entries x JOIN"
          + " events e ON e.id=x.event_id JOIN students s ON s.id=x.student_id ";

  public List<Event> events() {
    return db.query(
        EVENT_QUERY + "WHERE e.starts_at>? ORDER BY e.starts_at", this::mapEvent, clock.millis());
  }

  public Event event(String id) {
    var rows = db.query(EVENT_QUERY + "WHERE e.id=?", this::mapEvent, id);
    if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "Event not found.");
    return rows.get(0);
  }

  private Event mapEvent(ResultSet r, int n) throws SQLException {
    return new Event(
        r.getString("id"),
        r.getString("title"),
        r.getString("category"),
        r.getString("venue"),
        r.getString("description"),
        r.getLong("starts_at"),
        r.getString("theme"),
        r.getInt("available"),
        r.getInt("capacity"),
        r.getString("speaker"),
        r.getString("host_club"),
        r.getInt("seats_per_row"),
        r.getString("booking_mode"),
        r.getInt("waitlisted"),
        r.getInt("checked_in"),
        r.getString("organization_id"),
        r.getString("organization_name"),
        r.getString("audience"));
  }

  private Entry entry(ResultSet r, boolean privatePass) throws SQLException {
    String token = r.getString("checkin_token");
    return new Entry(
        r.getString("id"),
        r.getString("event_id"),
        r.getString("event_title"),
        r.getString("venue"),
        r.getLong("starts_at"),
        r.getString("status"),
        r.getString("seat_code"),
        r.getInt("position"),
        r.getLong("created_at"),
        r.getObject("promoted_at", Long.class),
        r.getObject("checked_in_at", Long.class),
        r.getString("student_name"),
        r.getString("student_number"),
        privatePass && token != null ? "seatlock:" + token : null,
        r.getString("booking_mode"));
  }

  private Entry entry(String id, boolean privatePass) {
    return db.queryForObject(ENTRY_QUERY + "WHERE x.id=?", (r, n) -> entry(r, privatePass), id);
  }

  public List<Entry> mine(String student) {
    return db.query(
        ENTRY_QUERY + "WHERE x.student_id=? ORDER BY x.created_at DESC,x.id",
        (r, n) -> entry(r, true),
        student);
  }

  public List<Entry> roster(String eventId) {
    event(eventId);
    return db.query(
        ENTRY_QUERY + "WHERE x.event_id=? ORDER BY x.queue_sequence",
        (r, n) -> entry(r, false),
        eventId);
  }

  public List<Seat> seats(String eventId) {
    event(eventId);
    return db.query(
        "SELECT s.code,CASE WHEN x.id IS NULL THEN 'AVAILABLE' ELSE 'BOOKED' END AS status FROM"
            + " seats s LEFT JOIN event_entries x ON x.event_id=s.event_id AND x.seat_code=s.code"
            + " AND x.status='CONFIRMED' WHERE s.event_id=? ORDER BY s.seat_index",
        (r, n) -> new Seat(r.getString("code"), r.getString("status")),
        eventId);
  }

  // All mutations for one event take the same event-row lock FIRST.
  // This serializes capacity checks, FIFO promotions and check-in/cancel races
  // across all students and sessions, without an in-process mutex.
  private Event lockEvent(String eventId) {
    if (db.queryForList("SELECT id FROM events WHERE id=? FOR UPDATE", eventId).isEmpty())
      throw new ApiError(404, "NOT_FOUND", "Event not found.");
    return event(eventId);
  }

  private void open(Event e) {
    if (e.startsAt() <= clock.millis())
      throw new ApiError(
          409, "REGISTRATION_CLOSED", "Registration and cancellation close when the event starts.");
  }

  String token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  @Transactional
  public Entry register(String student, String eventId, Signup request) {
    Event e = lockEvent(eventId);
    var prior =
        db.queryForList(
            "SELECT id,event_id FROM event_entries WHERE student_id=? AND request_key=?",
            student,
            request.requestKey());
    if (!prior.isEmpty()) {
      if (!eventId.equals(prior.get(0).get("event_id")))
        throw new ApiError(409, "KEY_REUSED", "Use a new registration key for this event.");
      return entry((String) prior.get(0).get("id"), true);
    }
    var existing =
        db.queryForList(
            "SELECT entry_id FROM active_entries WHERE student_id=? AND event_id=?",
            student,
            eventId);
    if (!existing.isEmpty()) return entry((String) existing.get(0).get("entry_id"), true);
    open(e);
    organizations.eligible(student, eventId);
    if (e.bookingMode().equals("GENERAL") && request.seatCode() != null)
      throw new ApiError(
          400,
          "NO_ASSIGNED_SEATS",
          "This event uses open seating. Reserve a place without choosing a chair.");
    String status = e.available() > 0 ? "CONFIRMED" : "WAITLISTED";
    String seat = null;
    if (status.equals("CONFIRMED") && e.bookingMode().equals("ASSIGNED")) {
      seat = request.seatCode();
      if (seat == null) throw new ApiError(400, "SEAT_REQUIRED", "Select one available seat.");
      if (db.queryForList("SELECT code FROM seats WHERE event_id=? AND code=?", eventId, seat)
          .isEmpty()) throw new ApiError(400, "INVALID_SEAT", "That seat does not exist.");
      if (!db.queryForList(
              "SELECT id FROM event_entries WHERE event_id=? AND seat_code=?", eventId, seat)
          .isEmpty())
        throw new ApiError(409, "SEAT_TAKEN", "That seat was just booked. Select another seat.");
    }
    long sequence =
        db.queryForObject("SELECT next_sequence FROM events WHERE id=?", Long.class, eventId) + 1;
    db.update("UPDATE events SET next_sequence=? WHERE id=?", sequence, eventId);
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " event_entries(id,student_id,event_id,status,seat_code,queue_sequence,request_key,created_at,checkin_token)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        student,
        eventId,
        status,
        seat,
        sequence,
        request.requestKey(),
        clock.millis(),
        status.equals("CONFIRMED") ? token() : null);
    db.update(
        "INSERT INTO active_entries(student_id,event_id,entry_id) VALUES(?,?,?)",
        student,
        eventId,
        id);
    return entry(id, true);
  }

  @Transactional
  public Entry cancel(String student, String id) {
    var rows =
        db.queryForList(
            "SELECT event_id FROM event_entries WHERE id=? AND student_id=?", id, student);
    if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "Registration not found.");
    Event e = lockEvent((String) rows.get(0).get("event_id"));
    Entry current = entry(id, true);
    if (current.status().equals("CANCELLED")) return current;
    open(e);
    if (current.checkedInAt() != null)
      throw new ApiError(
          409, "ALREADY_CHECKED_IN", "A checked-in registration cannot be cancelled.");
    db.update(
        "UPDATE event_entries SET"
            + " status='CANCELLED',seat_code=NULL,checkin_token=NULL,cancelled_at=? WHERE id=?",
        clock.millis(),
        id);
    db.update("DELETE FROM active_entries WHERE entry_id=?", id);
    if (current.status().equals("CONFIRMED")) {
      var waiting =
          db.queryForList(
              "SELECT id FROM event_entries WHERE event_id=? AND status='WAITLISTED' ORDER BY"
                  + " queue_sequence LIMIT 1",
              e.id());
      if (!waiting.isEmpty())
        db.update(
            "UPDATE event_entries SET status='CONFIRMED',seat_code=?,checkin_token=?,promoted_at=?"
                + " WHERE id=?",
            current.seatCode(),
            token(),
            clock.millis(),
            waiting.get(0).get("id"));
    }
    return entry(id, true);
  }

  @Transactional
  public CheckInResult checkIn(String eventId, String payload) {
    Event e = lockEvent(eventId);
    if (!payload.matches("seatlock:[a-f0-9]{64}"))
      throw new ApiError(400, "INVALID_PASS", "Use a SeatLock QR pass or its full pass code.");
    var rows =
        db.queryForList(
            "SELECT id FROM event_entries WHERE event_id=? AND checkin_token=? AND"
                + " status='CONFIRMED'",
            eventId,
            payload.substring(9));
    if (rows.isEmpty())
      throw new ApiError(
          404, "INVALID_PASS", "This pass is invalid, cancelled, or belongs to another event.");
    String id = (String) rows.get(0).get("id");
    Entry existing = entry(id, false);
    organizations.eligible(
        db.queryForObject("SELECT student_id FROM event_entries WHERE id=?", String.class, id),
        eventId);
    if (existing.checkedInAt() != null) return new CheckInResult(existing, true);
    // The documented admission window is 2 hours before to 12 hours after start.
    long now = clock.millis();
    if (now < e.startsAt() - 7200000L || now > e.startsAt() + 43200000L)
      throw new ApiError(
          409,
          "CHECKIN_CLOSED",
          "Check-in opens 2 hours before the event and closes 12 hours after it starts.");
    db.update("UPDATE event_entries SET checked_in_at=? WHERE id=?", now, id);
    return new CheckInResult(entry(id, false), false);
  }

  public Map<String, Object> overview(String organization) {
    var events =
        db.query(
            EVENT_QUERY + "WHERE e.organization_id=? ORDER BY e.starts_at DESC",
            this::mapEvent,
            organization);
    return Map.of(
        "events",
        events,
        "confirmed",
        events.stream().mapToInt(e -> e.capacity() - e.available()).sum(),
        "waitlisted",
        events.stream().mapToInt(Event::waitlisted).sum(),
        "checkedIn",
        events.stream().mapToInt(Event::checkedIn).sum());
  }

  // Used by the fictional seed data and fixtures; HTTP creation supplies an authorized
  // organization.
  @Transactional
  public String createEvent(NewEvent r) {
    return createEvent(OrganizationService.DEMO_ID, r);
  }

  @Transactional
  public String createEvent(String organization, NewEvent r) {
    if (r.startsAt() <= clock.millis())
      throw new ApiError(400, "INVALID_DATE", "Choose a future date.");
    if (r.capacity() < 1 || r.capacity() > 500 || r.seatsPerRow() < 8 || r.seatsPerRow() > 20)
      throw new ApiError(400, "INVALID_CAPACITY", "Use 1–500 places and 8–20 seats per row.");
    String id = UUID.randomUUID().toString();
    String theme =
        switch (r.category()) {
          case "Guest Talk" -> "coral";
          case "Workshop" -> "violet";
          default -> "lime";
        };
    db.update(
        "INSERT INTO"
            + " events(id,title,category,venue,description,starts_at,base_price,theme,speaker,host_club,seats_per_row,capacity,booking_mode,organization_id,audience)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        r.title().strip(),
        r.category(),
        r.venue().strip(),
        r.description().strip(),
        r.startsAt(),
        0,
        theme,
        r.speaker().strip(),
        r.hostClub().strip(),
        r.seatsPerRow(),
        r.capacity(),
        r.bookingMode(),
        organization,
        r.audience());
    if (r.bookingMode().equals("ASSIGNED"))
      for (int i = 0; i < r.capacity(); i++) {
        String row = "";
        int n = i / r.seatsPerRow() + 1;
        while (n > 0) {
          n--;
          row = (char) ('A' + n % 26) + row;
          n /= 26;
        }
        db.update(
            "INSERT INTO seats(event_id,code,seat_index,price) VALUES(?,?,?,0)",
            id,
            row + (i % r.seatsPerRow() + 1),
            i);
      }
    return id;
  }
}
