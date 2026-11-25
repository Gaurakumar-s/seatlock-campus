package dev.seatlock;

import static dev.seatlock.Models.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final JdbcTemplate db;
  private final Clock clock;
  private final String mailMode;
  private final SecureRandom random = new SecureRandom();
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final String dummyHash = passwords.encode("unusable-dummy-password");

  public AuthService(
      JdbcTemplate db, Clock clock, @Value("${seatlock.mail-mode}") String mailMode) {
    this.db = db;
    this.clock = clock;
    this.mailMode = mailMode;
  }

  static String email(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }

  static String domain(String value) {
    return value.substring(value.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
  }

  private String passwordHash(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiError(400, "INVALID_PASSWORD", "Password must fit within 72 UTF-8 bytes.");
    return passwords.encode(password);
  }

  @Transactional
  public Session register(Register input) {
    String id = UUID.randomUUID().toString();
    try {
      db.update(
          "INSERT INTO students(id,name,email,student_number,password_hash) VALUES(?,?,?,?,?)",
          id,
          input.name().strip(),
          email(input.email()),
          input.studentNumber() == null
              ? "ID-" + id.substring(0, 8)
              : input.studentNumber().toUpperCase(Locale.ROOT),
          passwordHash(input.password()));
    } catch (DuplicateKeyException e) {
      throw new ApiError(
          409, "ACCOUNT_EXISTS", "That email is already registered. Sign in to your account.");
    }
    return create("STUDENT", id);
  }

  @Transactional
  public Session registerOrganizer(OrganizerRegister input) {
    String id = UUID.randomUUID().toString();
    try {
      db.update(
          "INSERT INTO organizers(id,name,email,password_hash) VALUES(?,?,?,?)",
          id,
          input.name().strip(),
          email(input.email()),
          passwordHash(input.password()));
    } catch (DuplicateKeyException e) {
      throw new ApiError(
          409, "ACCOUNT_EXISTS", "That organizer email is already registered. Sign in instead.");
    }
    return create("ORGANIZER", id);
  }

  public Session login(Login input) {
    return login(input, false);
  }

  public Session login(Login input, boolean organizer) {
    String table = organizer ? "organizers" : "students";
    var rows =
        db.queryForList(
            "SELECT id,password_hash FROM " + table + " WHERE email=?", email(input.email()));
    String hash = rows.isEmpty() ? dummyHash : (String) rows.get(0).get("password_hash");
    boolean valid =
        input.password().getBytes(StandardCharsets.UTF_8).length <= 72
            && passwords.matches(input.password(), hash);
    if (rows.isEmpty() || !valid)
      throw new ApiError(401, "INVALID_CREDENTIALS", "Email or password is incorrect.");
    return create(organizer ? "ORGANIZER" : "STUDENT", (String) rows.get(0).get("id"));
  }

  Session create(String role, String accountId) {
    if (!Set.of("STUDENT", "ORGANIZER").contains(role))
      throw new IllegalArgumentException("Unsupported session role");
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    boolean organizer = role.equals("ORGANIZER");
    db.update(
        "INSERT INTO sessions(id,role,student_id,organizer_id,created_at,expires_at)"
            + " VALUES(?,?,?,?,?,?)",
        hash(token),
        role,
        organizer ? null : accountId,
        organizer ? accountId : null,
        clock.millis(),
        clock.millis() + 86400000L * 7);
    return new Session(
        token,
        role,
        organizer ? null : student(accountId),
        organizer ? organizer(accountId) : null);
  }

  public record Principal(String id, String role) {
    public boolean organizer() {
      return role.equals("ORGANIZER");
    }
  }

  public Principal principal(String header) {
    var rows =
        db.queryForList(
            "SELECT role,student_id,organizer_id FROM sessions WHERE id=? AND expires_at>?",
            sessionId(header),
            clock.millis());
    if (rows.isEmpty()) throw unauthorized();
    var row = rows.get(0);
    String role = (String) row.get("role");
    if (!Set.of("STUDENT", "ORGANIZER").contains(role)) throw unauthorized();
    return new Principal(
        (String) row.get(role.equals("ORGANIZER") ? "organizer_id" : "student_id"), role);
  }

  public String require(String header, boolean organizer) {
    Principal p = principal(header);
    if (p.organizer() != organizer)
      throw new ApiError(
          403,
          "FORBIDDEN",
          organizer ? "Organizer account required." : "Student account required.");
    return p.id();
  }

  public Student me(String header) {
    return student(require(header, false));
  }

  public OrganizerAccount organizerMe(String header) {
    return organizer(require(header, true));
  }

  public void logout(String header) {
    db.update("DELETE FROM sessions WHERE id=?", sessionId(header));
  }

  Student student(String id) {
    return db.queryForObject(
        "SELECT * FROM students WHERE id=?",
        (r, n) ->
            new Student(
                r.getString("id"),
                r.getString("name"),
                r.getString("email"),
                r.getString("student_number"),
                r.getObject("email_verified_at") != null
                    && (mailMode.equals("development")
                        || "smtp".equals(r.getString("email_verification_mode")))),
        id);
  }

  OrganizerAccount organizer(String id) {
    return db.queryForObject(
        "SELECT * FROM organizers WHERE id=?",
        (r, n) ->
            new OrganizerAccount(
                r.getString("id"),
                r.getString("name"),
                r.getString("email"),
                r.getObject("email_verified_at") != null
                    && (mailMode.equals("development")
                        || "smtp".equals(r.getString("email_verification_mode")))),
        id);
  }

  private String sessionId(String header) {
    if (header == null || !header.matches("Bearer [a-f0-9]{64}")) throw unauthorized();
    return hash(header.substring(7));
  }

  private ApiError unauthorized() {
    return new ApiError(401, "SESSION_EXPIRED", "Your session has expired. Please sign in again.");
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
