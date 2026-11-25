package dev.seatlock;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerificationService {
  private final JdbcTemplate db;
  private final Clock clock;
  private final JavaMailSender mail;
  private final String mode, from;
  private final SecureRandom random = new SecureRandom();
  private final BCryptPasswordEncoder hashes = new BCryptPasswordEncoder(10);
  // Local demonstration only: never populated or exposed in SMTP mode.
  private final Map<String, Map<String, Object>> inbox = new ConcurrentHashMap<>();

  public VerificationService(
      JdbcTemplate db,
      Clock clock,
      JavaMailSender mail,
      @Value("${seatlock.mail-mode}") String mode,
      @Value("${seatlock.mail-from}") String from,
      @Value("${server.address}") String bind) {
    this.db = db;
    this.clock = clock;
    this.mail = mail;
    this.mode = mode;
    this.from = from;
    if (!Set.of("smtp", "development").contains(mode))
      throw new IllegalStateException("MAIL_MODE must be smtp or development");
    if (mode.equals("development") && !Set.of("127.0.0.1", "localhost", "::1").contains(bind))
      throw new IllegalStateException(
          "Development email is only allowed on loopback. Configure SMTP for shared hosting.");
  }

  public Map<String, Object> config() {
    return Map.of("mode", mode, "realEmail", mode.equals("smtp"));
  }

  private String table(AuthService.Principal p) {
    return p.organizer() ? "organizers" : "students";
  }

  private String key(AuthService.Principal p) {
    return p.role() + ":" + p.id();
  }

  @Transactional
  public Map<String, Object> send(AuthService.Principal p) {
    var account =
        db.queryForMap(
            "SELECT email,email_verified_at,email_verification_mode FROM "
                + table(p)
                + " WHERE id=? FOR UPDATE",
            p.id());
    if (account.get("email_verified_at") != null
        && (mode.equals("development") || mode.equals(account.get("email_verification_mode"))))
      return Map.of("verified", true, "mode", mode);
    var previous =
        db.queryForList(
            "SELECT sent_at FROM email_challenges WHERE account_id=? AND role=?", p.id(), p.role());
    if (!previous.isEmpty()
        && clock.millis() - ((Number) previous.get(0).get("sent_at")).longValue() < 60000)
      throw new ApiError(429, "SEND_COOLDOWN", "Wait 60 seconds before requesting another code.");
    String code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
    long expires = clock.millis() + 600000;
    db.update("DELETE FROM email_challenges WHERE account_id=? AND role=?", p.id(), p.role());
    db.update(
        "INSERT INTO email_challenges(account_id,role,code_hash,expires_at,sent_at,attempts)"
            + " VALUES(?,?,?,?,?,0)",
        p.id(),
        p.role(),
        hashes.encode(code),
        expires,
        clock.millis());
    String recipient = (String) account.get("email");
    if (mode.equals("smtp")) {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(recipient);
      message.setSubject("Your SeatLock Campus verification code");
      message.setText(
          "Your verification code is "
              + code
              + ". It expires in 10 minutes.\n\nThis verifies your "
              + p.role().toLowerCase(Locale.ROOT)
              + " account's email address. Do not share this code. If you did not request it,"
              + " ignore this email.");
      try {
        mail.send(message);
      } catch (org.springframework.mail.MailException e) {
        throw new ApiError(
            503,
            "EMAIL_UNAVAILABLE",
            "Email could not be sent. Check the server's SMTP configuration and try again.");
      }
    } else {
      if (inbox.size() > 1000)
        inbox
            .entrySet()
            .removeIf(e -> ((Number) e.getValue().get("expiresAt")).longValue() < clock.millis());
      inbox.put(
          key(p),
          Map.of(
              "email",
              recipient,
              "code",
              code,
              "expiresAt",
              expires,
              "notice",
              "Local test inbox. No real email was sent."));
    }
    return Map.of("sent", true, "mode", mode, "expiresAt", expires, "retryAfterSeconds", 60);
  }

  // Persist failed attempts even when returning a validation error.
  @Transactional(noRollbackFor = ApiError.class)
  public Map<String, Object> confirm(AuthService.Principal p, String code) {
    db.queryForMap("SELECT id FROM " + table(p) + " WHERE id=? FOR UPDATE", p.id());
    var rows =
        db.queryForList(
            "SELECT * FROM email_challenges WHERE account_id=? AND role=?", p.id(), p.role());
    if (rows.isEmpty())
      throw new ApiError(400, "CODE_REQUIRED", "Request a verification code first.");
    var challenge = rows.get(0);
    if (((Number) challenge.get("expires_at")).longValue() <= clock.millis())
      throw new ApiError(400, "CODE_EXPIRED", "This code expired. Request another one.");
    if (((Number) challenge.get("attempts")).intValue() >= 5)
      throw new ApiError(
          429,
          "CODE_LOCKED",
          "Too many incorrect attempts. Request a new code after the cooldown.");
    db.update(
        "UPDATE email_challenges SET attempts=attempts+1 WHERE account_id=? AND role=?",
        p.id(),
        p.role());
    if (!hashes.matches(code, (String) challenge.get("code_hash")))
      throw new ApiError(400, "INVALID_CODE", "That code is incorrect.");
    db.update(
        "UPDATE " + table(p) + " SET email_verified_at=?,email_verification_mode=? WHERE id=?",
        clock.millis(),
        mode,
        p.id());
    db.update("DELETE FROM email_challenges WHERE account_id=? AND role=?", p.id(), p.role());
    inbox.remove(key(p));
    return Map.of("verified", true);
  }

  public Object testInbox(AuthService.Principal p) {
    if (!mode.equals("development")) throw new ApiError(404, "NOT_FOUND", "Not found.");
    var item = inbox.get(key(p));
    return item != null && ((Number) item.get("expiresAt")).longValue() > clock.millis()
        ? item
        : Map.of("notice", "No current test message. Request a code first.");
  }
}
