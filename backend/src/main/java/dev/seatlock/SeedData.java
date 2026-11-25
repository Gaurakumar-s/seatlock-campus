package dev.seatlock;

import static dev.seatlock.Models.*;

import java.time.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class SeedData implements ApplicationRunner {
  private final JdbcTemplate db;
  private final RegistrationService service;
  private final Clock clock;

  public SeedData(JdbcTemplate db, RegistrationService service, Clock clock) {
    this.db = db;
    this.service = service;
    this.clock = clock;
  }

  public void run(ApplicationArguments args) {
    if (db.queryForObject("SELECT COUNT(*) FROM events", Integer.class) > 0) {
      if (db.queryForObject(
              "SELECT COUNT(*) FROM events WHERE booking_mode='GENERAL' AND category='Guest Talk'",
              Integer.class)
          == 0)
        service.createEvent(
            new NewEvent(
                "Inside the industry",
                "Guest Talk",
                "Main Auditorium",
                "Meet an industry speaker, explore career paths and bring your questions. Reserve"
                    + " your place; choose an available chair when you arrive. Fictional sample"
                    + " event.",
                clock.millis() + 7 * 86400000L,
                200,
                10,
                "Guest industry speaker",
                "Career & Innovation Cell",
                "GENERAL"));
      return;
    }
    long evening =
        LocalDate.now(clock)
            .plusDays(18)
            .atTime(18, 30)
            .atZone(ZoneId.of("Asia/Kolkata"))
            .toInstant()
            .toEpochMilli();
    service.createEvent(
        new NewEvent(
            "Beyond the classroom",
            "Guest Talk",
            "Main Auditorium",
            "An interactive guest session on turning campus ideas into real-world projects. Explore"
                + " practical career lessons and bring your questions for the closing Q&A. This is"
                + " a fictional sample event.",
            evening,
            200,
            10,
            "Guest industry speaker",
            "Career & Innovation Cell",
            "GENERAL"));
    service.createEvent(
        new NewEvent(
            "Build something real",
            "Workshop",
            "Innovation Lab · Block B",
            "A hands-on web development workshop. Bring your laptop, pair up with fellow builders,"
                + " and leave with a working prototype. This is a fictional sample event.",
            evening + 86400000L * 3,
            60,
            10,
            "Student mentors",
            "Coding Club",
            "GENERAL"));
    service.createEvent(
        new NewEvent(
            "The campus unplugged",
            "Cultural",
            "Student Activity Centre",
            "An evening of acoustic music, poetry and student performances. Find your seat and"
                + " celebrate the talent around you. This is a fictional sample event.",
            evening + 86400000L * 6,
            120,
            12,
            "Student performers",
            "Cultural Society",
            "ASSIGNED"));
  }
}
