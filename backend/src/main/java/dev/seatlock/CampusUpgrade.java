package dev.seatlock;

import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(0)
public class CampusUpgrade implements ApplicationRunner {
  private final JdbcTemplate db;
  private final RegistrationService service;
  private final Clock clock;

  public CampusUpgrade(JdbcTemplate db, RegistrationService service, Clock clock) {
    this.db = db;
    this.service = service;
    this.clock = clock;
  }

  @Transactional
  public void run(ApplicationArguments args) {
    migrate();
  }

  @Transactional
  public void migrate() {
    if (!db.queryForList("SELECT name FROM app_migrations WHERE name='registration-v2'").isEmpty())
      return;
    for (var e : db.queryForList("SELECT id FROM events WHERE capacity=0")) {
      String event = (String) e.get("id");
      int capacity =
          db.queryForObject("SELECT COUNT(*) FROM seats WHERE event_id=?", Integer.class, event);
      int confirmed =
          db.queryForObject(
              "SELECT COUNT(*) FROM bookings WHERE event_id=? AND status='CONFIRMED'",
              Integer.class,
              event);
      db.update(
          "UPDATE events SET capacity=?,booking_mode=? WHERE id=?",
          capacity,
          confirmed > 0 ? "ASSIGNED" : "GENERAL",
          event);
      long sequence = 0;
      for (var b :
          db.queryForList(
              "SELECT * FROM bookings WHERE event_id=? ORDER BY created_at,id", event)) {
        String status = (String) b.get("status"),
            id = (String) b.get("id"),
            student = (String) b.get("student_id");
        boolean active = status.equals("CONFIRMED");
        db.update(
            "INSERT INTO"
                + " event_entries(id,student_id,event_id,status,seat_code,queue_sequence,request_key,created_at,checkin_token)"
                + " VALUES(?,?,?,?,?,?,?,?,?)",
            id,
            student,
            event,
            status,
            active ? b.get("seat_codes") : null,
            ++sequence,
            "legacy-" + id,
            b.get("created_at"),
            active ? service.token() : null);
        if (active)
          db.update(
              "INSERT INTO active_entries(student_id,event_id,entry_id) VALUES(?,?,?)",
              student,
              event,
              id);
      }
      db.update("UPDATE events SET next_sequence=? WHERE id=?", sequence, event);
    }
    // Temporary holds are retired; confirmed legacy tickets retain their assigned seat.
    db.update("UPDATE holds SET status='RELEASED' WHERE status='HELD'");
    db.update("UPDATE seats SET hold_id=NULL,hold_expires_at=NULL WHERE hold_id IS NOT NULL");
    db.update(
        "INSERT INTO app_migrations(name,applied_at) VALUES('registration-v2',?)", clock.millis());
  }
}
