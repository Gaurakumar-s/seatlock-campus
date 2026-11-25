package dev.seatlock;

import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
public class OrganizationUpgrade implements ApplicationRunner {
  private final JdbcTemplate db;
  private final Clock clock;
  private final String mode;

  public OrganizationUpgrade(
      JdbcTemplate db, Clock clock, @Value("${seatlock.mail-mode}") String mode) {
    this.db = db;
    this.clock = clock;
    this.mode = mode;
  }

  @Transactional
  public void run(ApplicationArguments args) {
    if (!db.queryForList("SELECT name FROM app_migrations WHERE name='organizations-v3'").isEmpty())
      return;
    db.update(
        "INSERT INTO organizations(id,name,email_domain,created_at,demo) VALUES(?,?,?,?,TRUE)",
        OrganizationService.DEMO_ID,
        "Sample Campus",
        "campus.example.test",
        clock.millis());
    db.update(
        "UPDATE events SET organization_id=? WHERE organization_id IS NULL",
        OrganizationService.DEMO_ID);
    // Earlier shared-password sessions must never grant workspace access.
    db.update("DELETE FROM sessions WHERE role='ADMIN'");
    // Roll numbers may legitimately repeat across colleges. Email remains unique.
    var constraints =
        db.queryForList(
            "SELECT DISTINCT tc.constraint_name FROM information_schema.table_constraints tc JOIN"
                + " information_schema.key_column_usage k ON tc.constraint_name=k.constraint_name"
                + " AND tc.table_schema=k.table_schema AND tc.table_name=k.table_name WHERE"
                + " LOWER(tc.table_name)='students' AND tc.constraint_type='UNIQUE' AND"
                + " LOWER(k.column_name)='student_number'");
    for (var row : constraints) {
      String name = (String) row.get("constraint_name");
      db.execute("ALTER TABLE students DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
    }
    if (mode.equals("development")) {
      String id = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO organizers(id,name,email,password_hash) VALUES(?,?,?,?)",
          id,
          "Demo Organizer",
          "organizer@campus.example.test",
          new BCryptPasswordEncoder(12).encode("Campus-demo-2026!"));
      db.update(
          "INSERT INTO organization_organizers(organization_id,organizer_id,role)"
              + " VALUES(?,?,'OWNER')",
          OrganizationService.DEMO_ID,
          id);
    }
    db.update(
        "INSERT INTO app_migrations(name,applied_at) VALUES('organizations-v3',?)", clock.millis());
  }
}
