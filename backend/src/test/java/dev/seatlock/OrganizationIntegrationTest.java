package dev.seatlock;

import static dev.seatlock.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:identitytest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=15000}",
      "spring.datasource.username=${TEST_DB_USER:sa}",
      "spring.datasource.password=${TEST_DB_PASSWORD:}",
      "seatlock.mail-mode=development"
    })
class OrganizationIntegrationTest {
  @Autowired AuthService auth;
  @Autowired OrganizationService organizations;
  @Autowired RegistrationService registrations;
  @Autowired VerificationService verification;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @Autowired Clock clock;
  String owner, otherOwner;
  Organization a, b;
  Session ownerSession, otherSession;

  @BeforeEach
  void setup() {
    owner = organizer("alpha.example.test", true);
    otherOwner = organizer("beta.example.test", true);
    a = organizations.create(owner, new NewOrganization("Alpha College", "alpha.example.test"));
    b = organizations.create(otherOwner, new NewOrganization("Beta College", "beta.example.test"));
    ownerSession = auth.create("ORGANIZER", owner);
    otherSession = auth.create("ORGANIZER", otherOwner);
  }

  String organizer(String domain, boolean verified) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO organizers(id,name,email,password_hash,email_verified_at) VALUES(?,?,?,?,?)",
        id,
        "Organizer",
        id + "@" + domain,
        "fixture-only",
        verified ? clock.millis() : null);
    return id;
  }

  String student(String domain, boolean verified) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO students(id,name,email,student_number,password_hash,email_verified_at)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        "Student",
        id + "@" + domain,
        "CS-001",
        "fixture-only",
        verified ? clock.millis() : null);
    return id;
  }

  String event(Organization org, String audience) {
    return registrations.createEvent(
        org.id(),
        new NewEvent(
            "Talk",
            "Guest Talk",
            "Hall",
            "Fixture event",
            clock.millis() + 3600000,
            2,
            10,
            "Guest",
            "Club",
            "GENERAL",
            audience));
  }

  Entry register(String s, String e) {
    return registrations.register(s, e, new Signup(UUID.randomUUID().toString(), null));
  }

  AuthService.Principal principal(String id, boolean organizer) {
    return new AuthService.Principal(id, organizer ? "ORGANIZER" : "STUDENT");
  }

  String code(AuthService.Principal p) {
    return (String) ((Map<?, ?>) verification.testInbox(p)).get("code");
  }

  HttpHeaders headers(Session s, String org) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(s.token());
    h.setContentType(MediaType.APPLICATION_JSON);
    if (org != null) h.set("X-Organization-ID", org);
    return h;
  }

  @Test
  void collegeEventAcceptsVerifiedExactDomain() {
    assertEquals(
        "CONFIRMED", register(student("alpha.example.test", true), event(a, "COLLEGE")).status());
  }

  @Test
  void collegeEventRejectsOtherCollegeAndLookalike() {
    String e = event(a, "COLLEGE");
    for (String domain :
        List.of("beta.example.test", "alpha.example.test.evil.test", "sub.alpha.example.test"))
      assertEquals(
          "COLLEGE_ONLY",
          assertThrows(ApiError.class, () -> register(student(domain, true), e)).code);
  }

  @Test
  void openEventAcceptsOutsideEmail() {
    assertEquals(
        "CONFIRMED", register(student("outside.example.test", true), event(a, "OPEN")).status());
  }

  @Test
  void unverifiedEmailCannotJoinEitherAudience() {
    for (String audience : List.of("COLLEGE", "OPEN"))
      assertEquals(
          "EMAIL_NOT_VERIFIED",
          assertThrows(
                  ApiError.class,
                  () -> register(student("alpha.example.test", false), event(a, audience)))
              .code);
  }

  @Test
  void verificationCodeIsPrivateSingleUseAndHashed() {
    String id = student("alpha.example.test", false);
    var p = principal(id, false);
    var sent = verification.send(p);
    assertFalse(sent.containsKey("code"));
    String code = code(p);
    assertNotNull(code);
    assertNotEquals(
        code,
        db.queryForObject(
            "SELECT code_hash FROM email_challenges WHERE account_id=?", String.class, id));
    verification.confirm(p, code);
    assertTrue(auth.student(id).emailVerified());
    assertEquals(
        "CODE_REQUIRED", assertThrows(ApiError.class, () -> verification.confirm(p, code)).code);
  }

  @Test
  void codeForAnotherAccountCannotVerifyYou() {
    var one = principal(student("alpha.example.test", false), false);
    var two = principal(student("alpha.example.test", false), false);
    verification.send(one);
    assertEquals(
        "CODE_REQUIRED",
        assertThrows(ApiError.class, () -> verification.confirm(two, code(one))).code);
    assertNull(((Map<?, ?>) verification.testInbox(two)).get("code"));
  }

  @Test
  void failedAttemptsPersistAndLockCode() {
    var p = principal(student("alpha.example.test", false), false);
    verification.send(p);
    String real = code(p), wrong = real.equals("000000") ? "111111" : "000000";
    for (int i = 0; i < 5; i++)
      assertEquals(
          "INVALID_CODE", assertThrows(ApiError.class, () -> verification.confirm(p, wrong)).code);
    assertEquals(
        "CODE_LOCKED", assertThrows(ApiError.class, () -> verification.confirm(p, real)).code);
    assertFalse(auth.student(p.id()).emailVerified());
  }

  @Test
  void expiryAndPersistentResendCooldownAreEnforced() {
    var p = principal(student("alpha.example.test", false), false);
    verification.send(p);
    assertEquals("SEND_COOLDOWN", assertThrows(ApiError.class, () -> verification.send(p)).code);
    db.update("UPDATE email_challenges SET expires_at=0 WHERE account_id=?", p.id());
    assertEquals(
        "CODE_EXPIRED", assertThrows(ApiError.class, () -> verification.confirm(p, code(p))).code);
    db.update("UPDATE email_challenges SET sent_at=0 WHERE account_id=?", p.id());
    verification.send(p);
    verification.confirm(p, code(p));
    assertTrue(auth.student(p.id()).emailVerified());
  }

  @Test
  void organizerMustVerifyBeforeCreatingOrManaging() {
    String id = organizer("alpha.example.test", false);
    assertEquals(
        "EMAIL_NOT_VERIFIED",
        assertThrows(
                ApiError.class,
                () -> organizations.create(id, new NewOrganization("Alpha", "alpha.example.test")))
            .code);
    assertEquals(
        "EMAIL_NOT_VERIFIED",
        assertThrows(ApiError.class, () -> organizations.require(id, a.id(), false)).code);
    var p = principal(id, true);
    verification.send(p);
    verification.confirm(p, code(p));
    assertTrue(auth.organizer(id).emailVerified());
  }

  @Test
  void organizerCannotClaimUnrelatedOrPublicDomain() {
    assertEquals(
        "DOMAIN_MISMATCH",
        assertThrows(
                ApiError.class,
                () ->
                    organizations.create(owner, new NewOrganization("Other", "beta.example.test")))
            .code);
    String gmail = organizer("gmail.com", true);
    assertEquals(
        "PUBLIC_DOMAIN",
        assertThrows(
                ApiError.class,
                () -> organizations.create(gmail, new NewOrganization("Fake college", "gmail.com")))
            .code);
  }

  @Test
  void loginUsesIndividualOrganizerPassword() {
    String email = UUID.randomUUID() + "@alpha.example.test";
    Session created =
        auth.registerOrganizer(
            new OrganizerRegister("My organizer", email, "correct organizer password"));
    assertNotNull(auth.login(new Login(email, "correct organizer password"), true).organizer());
    assertEquals(
        "INVALID_CREDENTIALS",
        assertThrows(ApiError.class, () -> auth.login(new Login(email, "wrong password"), true))
            .code);
    assertFalse(created.organizer().emailVerified());
    assertNotEquals(
        "correct organizer password",
        db.queryForObject(
            "SELECT password_hash FROM organizers WHERE id=?",
            String.class,
            created.organizer().id()));
  }

  @Test
  void workspaceAccessCannotBeForgedWithHeaderOrEventId() {
    String e = event(a, "OPEN");
    var wrong = headers(otherSession, a.id());
    assertEquals(
        403,
        http.exchange("/api/admin/overview", HttpMethod.GET, new HttpEntity<>(wrong), Map.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        http.exchange(
                "/api/admin/events/" + e + "/registrations",
                HttpMethod.GET,
                new HttpEntity<>(wrong),
                Map.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        http.postForEntity(
                "/api/admin/events/" + e + "/check-in",
                new HttpEntity<>(new CheckIn("seatlock:" + "a".repeat(64)), wrong),
                Map.class)
            .getStatusCode()
            .value());
  }

  @Test
  void organizerOverviewIsScoped() {
    String own = event(a, "OPEN"), other = event(b, "OPEN");
    var overview = registrations.overview(organizations.require(owner, a.id(), false));
    var events = (List<Event>) overview.get("events");
    assertTrue(events.stream().anyMatch(e -> e.id().equals(own)));
    assertFalse(events.stream().anyMatch(e -> e.id().equals(other)));
  }

  @Test
  void teamMemberNeedsVerifiedCollegeAccountAndOwnerApproval() {
    String member = organizer("alpha.example.test", false);
    String email = auth.organizer(member).email();
    assertEquals(
        "ORGANIZER_NOT_READY",
        assertThrows(ApiError.class, () -> organizations.add(owner, a.id(), email)).code);
    db.update("UPDATE organizers SET email_verified_at=? WHERE id=?", clock.millis(), member);
    organizations.add(owner, a.id(), email);
    assertEquals(a.id(), organizations.require(member, a.id(), false));
    assertEquals(
        "ORGANIZATION_FORBIDDEN",
        assertThrows(
                ApiError.class,
                () -> organizations.add(member, a.id(), auth.organizer(owner).email()))
            .code);
    assertEquals(
        "DOMAIN_MISMATCH",
        assertThrows(
                ApiError.class,
                () -> organizations.add(owner, a.id(), auth.organizer(otherOwner).email()))
            .code);
  }

  @Test
  void removingOrganizerRevokesAccessForExistingSession() {
    String member = organizer("alpha.example.test", true);
    organizations.add(owner, a.id(), auth.organizer(member).email());
    Session session = auth.create("ORGANIZER", member);
    organizations.remove(owner, a.id(), member);
    assertEquals(
        403,
        http.exchange(
                "/api/admin/overview",
                HttpMethod.GET,
                new HttpEntity<>(headers(session, a.id())),
                Map.class)
            .getStatusCode()
            .value());
    organizations.remove(owner, a.id(), owner);
    assertEquals(a.id(), organizations.require(owner, a.id(), true));
  }

  @Test
  void unverifiedExistingPassCannotBeCheckedIn() {
    String s = student("alpha.example.test", true), e = event(a, "COLLEGE");
    Entry registration = register(s, e);
    db.update("UPDATE students SET email_verified_at=NULL WHERE id=?", s);
    assertEquals(
        "EMAIL_NOT_VERIFIED",
        assertThrows(ApiError.class, () -> registrations.checkIn(e, registration.qrPayload()))
            .code);
  }

  @Test
  void smtpModeSendsToAccountEmailAndHidesTestInbox() {
    JavaMailSender sender = mock(JavaMailSender.class);
    var smtp =
        new VerificationService(db, clock, sender, "smtp", "sender@example.test", "127.0.0.1");
    String id = student("alpha.example.test", false);
    var p = principal(id, false);
    smtp.send(p);
    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(message.capture());
    assertArrayEquals(new String[] {auth.student(id).email()}, message.getValue().getTo());
    assertTrue(message.getValue().getText().matches("(?s).*code is [0-9]{6}.*"));
    assertEquals(404, assertThrows(ApiError.class, () -> smtp.testInbox(p)).status);
  }

  @Test
  void smtpFailureDoesNotPretendSuccess() {
    JavaMailSender sender = mock(JavaMailSender.class);
    doThrow(new MailSendException("fixture failure"))
        .when(sender)
        .send(any(SimpleMailMessage.class));
    var smtp =
        new VerificationService(db, clock, sender, "smtp", "sender@example.test", "127.0.0.1");
    assertEquals(
        "EMAIL_UNAVAILABLE",
        assertThrows(
                ApiError.class,
                () -> smtp.send(principal(student("alpha.example.test", false), false)))
            .code);
  }

  @Test
  void developmentMailCannotBindToPublicInterface() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new VerificationService(
                db,
                clock,
                mock(JavaMailSender.class),
                "development",
                "sender@example.test",
                "0.0.0.0"));
  }

  @Test
  void sharedPasswordEndpointNoLongerExists() {
    assertEquals(
        404,
        http.postForEntity(
                "/api/admin/sessions", Map.of("password", "seatlock-local-demo"), Map.class)
            .getStatusCode()
            .value());
  }

  @Test
  void localVerificationDoesNotBecomeRealVerificationWhenSmtpIsEnabled() {
    String id = student("alpha.example.test", false);
    var p = principal(id, false);
    verification.send(p);
    verification.confirm(p, code(p));
    assertTrue(auth.student(id).emailVerified());
    AuthService smtpAuth = new AuthService(db, clock, "smtp");
    assertFalse(smtpAuth.student(id).emailVerified());
    db.update("UPDATE students SET email_verification_mode='smtp' WHERE id=?", id);
    assertTrue(smtpAuth.student(id).emailVerified());
  }
}
