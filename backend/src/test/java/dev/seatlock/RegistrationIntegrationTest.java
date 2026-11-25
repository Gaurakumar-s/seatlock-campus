package dev.seatlock;

import static dev.seatlock.Models.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:registrationtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=15000}",
      "spring.datasource.username=${TEST_DB_USER:sa}",
      "spring.datasource.password=${TEST_DB_PASSWORD:}"
    })
class RegistrationIntegrationTest {
  @Autowired RegistrationService service;
  @Autowired AuthService auth;
  @Autowired CampusUpgrade upgrade;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @Autowired MutableClock clock;
  String eventId, owner;
  Session session;

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    final AtomicLong millis = new AtomicLong(System.currentTimeMillis());

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }

    void advance(long ms) {
      millis.addAndGet(ms);
    }
  }

  @BeforeEach
  void setup() {
    for (String table :
        List.of(
            "email_challenges",
            "active_entries",
            "event_entries",
            "registrations",
            "seats",
            "bookings",
            "holds",
            "events",
            "sessions",
            "students")) db.update("DELETE FROM " + table);
    clock.millis.set(System.currentTimeMillis());
    eventId = create(2, "GENERAL");
    owner = student();
    session = auth.create("STUDENT", owner);
  }

  Session organizerSession() {
    String id =
        db.queryForObject(
            "SELECT organizer_id FROM organization_organizers WHERE organization_id=? AND"
                + " role='OWNER'",
            String.class,
            OrganizationService.DEMO_ID);
    db.update("UPDATE organizers SET email_verified_at=? WHERE id=?", clock.millis(), id);
    return auth.create("ORGANIZER", id);
  }

  String student() {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO students(id,name,email,student_number,password_hash) VALUES(?,?,?,?,?)",
        id,
        "Test Student",
        id + "@campus.example.test",
        id.substring(0, 24),
        "unused-fixture-hash");
    db.update("UPDATE students SET email_verified_at=? WHERE id=?", clock.millis(), id);
    return id;
  }

  String create(int capacity, String mode) {
    return service.createEvent(
        new NewEvent(
            "Campus event",
            "Guest Talk",
            "Main hall",
            "Sample event",
            clock.millis() + 3600000L,
            capacity,
            10,
            "Speaker",
            "Club",
            mode));
  }

  Entry signup(String student) {
    return service.register(student, eventId, new Signup(UUID.randomUUID().toString(), null));
  }

  int count(String status) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM event_entries WHERE event_id=? AND status=?",
        Integer.class,
        eventId,
        status);
  }

  HttpHeaders headers(String token) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-Organization-ID", OrganizationService.DEMO_ID);
    return h;
  }

  <T> List<T> race(int threads, IntFunction<T> action) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads), go = new CountDownLatch(1);
    try {
      List<Future<T>> fs = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        final int n = i;
        fs.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return action.apply(n);
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      go.countDown();
      List<T> results = new ArrayList<>();
      for (var f : fs) results.add(f.get(30, TimeUnit.SECONDS));
      return results;
    } finally {
      go.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void generalRegistrationConfirmsWithoutChair() {
    Entry e = signup(owner);
    assertEquals("CONFIRMED", e.status());
    assertNull(e.seatCode());
    assertNotNull(e.qrPayload());
    assertEquals(1, service.event(eventId).available());
    assertTrue(service.seats(eventId).isEmpty());
  }

  @Test
  void lastPlaceHasOneWinnerAmongOneHundredStudents() throws Exception {
    eventId = create(1, "GENERAL");
    List<String> students = new ArrayList<>();
    for (int i = 0; i < 100; i++) students.add(student());
    var results = race(100, i -> signup(students.get(i)));
    assertEquals(1, results.stream().filter(e -> e.status().equals("CONFIRMED")).count());
    assertEquals(99, count("WAITLISTED"));
    assertEquals(0, service.event(eventId).available());
    assertEquals(
        99,
        results.stream()
            .filter(e -> e.status().equals("WAITLISTED"))
            .map(Entry::waitlistPosition)
            .distinct()
            .count());
  }

  @Test
  void sameStudentAcrossSessionsGetsOnlyOneRegistration() throws Exception {
    Session second = auth.create("STUDENT", owner);
    String otherSessionOwner = auth.require("Bearer " + second.token(), false);
    var results = race(20, i -> signup(i % 2 == 0 ? owner : otherSessionOwner));
    assertEquals(1, results.stream().map(Entry::id).distinct().count());
    assertEquals(1, count("CONFIRMED"));
  }

  @Test
  void fullEventReturnsWaitlistPositionWithoutPass() {
    signup(student());
    signup(student());
    Entry first = signup(owner), second = signup(student());
    assertEquals("WAITLISTED", first.status());
    assertEquals(1, first.waitlistPosition());
    assertEquals(2, second.waitlistPosition());
    assertNull(first.qrPayload());
  }

  @Test
  void cancellationPromotesEarliestWaitingStudent() {
    Entry a = signup(owner);
    signup(student());
    String b = student(), c = student();
    signup(b);
    signup(c);
    service.cancel(owner, a.id());
    Entry promoted = service.mine(b).get(0);
    assertEquals("CONFIRMED", promoted.status());
    assertNotNull(promoted.promotedAt());
    assertNotNull(promoted.qrPayload());
    assertEquals(1, service.mine(c).get(0).waitlistPosition());
    assertEquals(2, count("CONFIRMED"));
  }

  @Test
  void leavingWaitlistDoesNotFreeConfirmedCapacity() {
    signup(student());
    signup(student());
    Entry e = signup(owner);
    String next = student();
    signup(next);
    service.cancel(owner, e.id());
    assertEquals(2, count("CONFIRMED"));
    assertEquals(1, service.mine(next).get(0).waitlistPosition());
  }

  @Test
  void joiningAgainGoesToBackOfQueue() {
    signup(student());
    signup(student());
    Entry e = signup(owner);
    String next = student();
    signup(next);
    service.cancel(owner, e.id());
    Entry rejoined = signup(owner);
    assertNotEquals(e.id(), rejoined.id());
    assertEquals(2, rejoined.waitlistPosition());
    assertEquals(1, service.mine(next).get(0).waitlistPosition());
  }

  @Test
  void duplicateCancellationPromotesOnlyOne() throws Exception {
    eventId = create(1, "GENERAL");
    Entry e = signup(owner);
    signup(student());
    signup(student());
    race(2, i -> service.cancel(owner, e.id()));
    assertEquals(1, count("CONFIRMED"));
    assertEquals(1, count("WAITLISTED"));
  }

  @Test
  void concurrentCancellationsPromoteExactlyTwo() throws Exception {
    String other = student();
    Entry a = signup(owner), b = signup(other);
    String first = student(), second = student(), third = student();
    signup(first);
    signup(second);
    signup(third);
    race(2, i -> service.cancel(i == 0 ? owner : other, i == 0 ? a.id() : b.id()));
    assertEquals("CONFIRMED", service.mine(first).get(0).status());
    assertEquals("CONFIRMED", service.mine(second).get(0).status());
    assertEquals(1, service.mine(third).get(0).waitlistPosition());
  }

  @Test
  void newArrivalCannotJumpQueueDuringCancellation() throws Exception {
    eventId = create(1, "GENERAL");
    Entry a = signup(owner);
    String waiting = student(), arrival = student();
    signup(waiting);
    race(2, i -> i == 0 ? service.cancel(owner, a.id()) : signup(arrival));
    assertEquals("CONFIRMED", service.mine(waiting).get(0).status());
    assertEquals("WAITLISTED", service.mine(arrival).get(0).status());
  }

  @Test
  void registrationRetriesReturnSameEntry() throws Exception {
    Signup request = new Signup("stable", null);
    var result = race(20, i -> service.register(owner, eventId, request));
    assertEquals(1, result.stream().map(Entry::id).distinct().count());
    assertEquals(1, count("CONFIRMED"));
  }

  @Test
  void retryAfterCancelDoesNotReinstateRegistration() {
    Signup request = new Signup("stable", null);
    Entry e = service.register(owner, eventId, request);
    service.cancel(owner, e.id());
    assertEquals("CANCELLED", service.register(owner, eventId, request).status());
    assertEquals(0, count("CONFIRMED"));
  }

  @Test
  void keyCannotBeReusedForAnotherEvent() {
    service.register(owner, eventId, new Signup("same-key", null));
    String other = create(10, "GENERAL");
    assertEquals(
        "KEY_REUSED",
        assertThrows(
                ApiError.class, () -> service.register(owner, other, new Signup("same-key", null)))
            .code);
  }

  @Test
  void sameStudentCanAttendDifferentEvents() {
    signup(owner);
    String other = create(5, "GENERAL");
    service.register(owner, other, new Signup("second-event", null));
    assertEquals(2, service.mine(owner).size());
  }

  @Test
  void anotherStudentCannotReadPassOrCancel() {
    Entry e = signup(owner);
    String other = student();
    assertTrue(service.mine(other).isEmpty());
    assertEquals(404, assertThrows(ApiError.class, () -> service.cancel(other, e.id())).status);
  }

  @Test
  void cancelledQrCannotCheckIn() {
    Entry e = signup(owner);
    service.cancel(owner, e.id());
    assertEquals(
        "INVALID_PASS",
        assertThrows(ApiError.class, () -> service.checkIn(eventId, e.qrPayload())).code);
  }

  @Test
  void qrMustMatchSelectedEvent() {
    Entry e = signup(owner);
    String other = create(2, "GENERAL");
    assertEquals(
        "INVALID_PASS",
        assertThrows(ApiError.class, () -> service.checkIn(other, e.qrPayload())).code);
  }

  @Test
  void checkInIsIdempotentUnderConcurrentScans() throws Exception {
    Entry e = signup(owner);
    var results = race(20, i -> service.checkIn(eventId, e.qrPayload()));
    assertEquals(1, results.stream().filter(x -> !x.alreadyCheckedIn()).count());
    assertNotNull(service.mine(owner).get(0).checkedInAt());
    assertEquals(1, service.event(eventId).checkedIn());
  }

  @Test
  void checkInWindowIsEnforced() {
    String future =
        service.createEvent(
            new NewEvent(
                "Later",
                "Workshop",
                "Lab",
                "Test",
                clock.millis() + 86400000L,
                1,
                10,
                "",
                "Club",
                "GENERAL"));
    Entry e = service.register(owner, future, new Signup("later", null));
    assertEquals(
        "CHECKIN_CLOSED",
        assertThrows(ApiError.class, () -> service.checkIn(future, e.qrPayload())).code);
    clock.advance(86400000L + 43200001L);
    assertEquals(
        "CHECKIN_CLOSED",
        assertThrows(ApiError.class, () -> service.checkIn(future, e.qrPayload())).code);
  }

  @Test
  void checkedInStudentCannotCancel() {
    Entry e = signup(owner);
    service.checkIn(eventId, e.qrPayload());
    assertEquals(
        "ALREADY_CHECKED_IN",
        assertThrows(ApiError.class, () -> service.cancel(owner, e.id())).code);
  }

  @Test
  void cancelAndCheckInRaceHasConsistentOutcome() throws Exception {
    Entry e = signup(owner);
    race(
        2,
        i -> {
          try {
            if (i == 0) service.cancel(owner, e.id());
            else service.checkIn(eventId, e.qrPayload());
            return true;
          } catch (ApiError error) {
            assertTrue(List.of("ALREADY_CHECKED_IN", "INVALID_PASS").contains(error.code));
            return false;
          }
        });
    Entry finalEntry = service.mine(owner).get(0);
    assertTrue(
        finalEntry.status().equals("CANCELLED")
            ? finalEntry.qrPayload() == null && finalEntry.checkedInAt() == null
            : finalEntry.checkedInAt() != null);
  }

  @Test
  void invalidQrIsRejected() {
    assertEquals(
        "INVALID_PASS",
        assertThrows(ApiError.class, () -> service.checkIn(eventId, "https://untrusted.example"))
            .code);
  }

  @Test
  void rosterDoesNotLeakQrCredentials() {
    signup(owner);
    assertNull(service.roster(eventId).get(0).qrPayload());
  }

  @Test
  void registrationAndCancellationCloseAtEventStart() {
    Entry e = signup(owner);
    clock.advance(3600000L);
    assertEquals("REGISTRATION_CLOSED", assertThrows(ApiError.class, () -> signup(student())).code);
    assertEquals(
        "REGISTRATION_CLOSED",
        assertThrows(ApiError.class, () -> service.cancel(owner, e.id())).code);
  }

  @Test
  void assignedSeatCannotBeDoubleBooked() throws Exception {
    eventId = create(2, "ASSIGNED");
    String other = student();
    var results =
        race(
            2,
            i -> {
              try {
                service.register(i == 0 ? owner : other, eventId, new Signup("seat-" + i, "A1"));
                return true;
              } catch (ApiError e) {
                assertEquals("SEAT_TAKEN", e.code);
                return false;
              }
            });
    assertEquals(1, results.stream().filter(x -> x).count());
  }

  @Test
  void assignedWaitlistGetsFreedSeat() {
    eventId = create(1, "ASSIGNED");
    Entry e = service.register(owner, eventId, new Signup("chair", "A1"));
    String waiting = student();
    signup(waiting);
    service.cancel(owner, e.id());
    assertEquals("A1", service.mine(waiting).get(0).seatCode());
    assertEquals("BOOKED", service.seats(eventId).get(0).status());
  }

  @Test
  void seatMapSupportsPartialRowsAndLargeLayouts() {
    String small = create(17, "ASSIGNED"), large = create(500, "ASSIGNED");
    assertEquals(17, service.seats(small).size());
    assertEquals("B7", service.seats(small).get(16).code());
    assertEquals("AA1", service.seats(large).get(260).code());
  }

  @Test
  void generalModeRejectsSeatChoice() {
    assertEquals(
        "NO_ASSIGNED_SEATS",
        assertThrows(
                ApiError.class, () -> service.register(owner, eventId, new Signup("invalid", "A1")))
            .code);
  }

  @Test
  void databaseEnforcesOneActiveEntryPerStudent() {
    Entry e = signup(owner);
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () ->
            db.update(
                "INSERT INTO active_entries(student_id,event_id,entry_id) VALUES(?,?,?)",
                owner,
                eventId,
                e.id()));
  }

  @Test
  void loginAndLogoutPreserveRegistration() {
    Session s =
        auth.register(
            new Register(
                "Student", "login@campus.example.test", "CS-123", "correct horse battery"));
    db.update(
        "UPDATE students SET email_verified_at=? WHERE id=?", clock.millis(), s.student().id());
    Entry e = signup(s.student().id());
    auth.logout("Bearer " + s.token());
    assertEquals(401, assertThrows(ApiError.class, () -> auth.me("Bearer " + s.token())).status);
    Session fresh = auth.login(new Login("LOGIN@campus.example.test", "correct horse battery"));
    assertEquals(e.id(), service.mine(fresh.student().id()).get(0).id());
  }

  @Test
  void credentialsAreHashedAndDuplicateIdentitiesRejected() {
    Session s =
        auth.register(
            new Register("Student", "test@example.test", "CS-123", "correct horse battery"));
    assertNotEquals(
        "correct horse battery",
        db.queryForObject(
            "SELECT password_hash FROM students WHERE id=?", String.class, s.student().id()));
    assertEquals(
        "ACCOUNT_EXISTS",
        assertThrows(
                ApiError.class,
                () ->
                    auth.register(
                        new Register(
                            "Other", "TEST@example.test", "CS-124", "another good password")))
            .code);
    assertNotNull(
        auth.register(
            new Register("Other", "other@example.test", "cs-123", "another good password")));
    assertEquals(
        "INVALID_CREDENTIALS",
        assertThrows(
                ApiError.class, () -> auth.login(new Login("test@example.test", "wrong password")))
            .code);
  }

  @Test
  void apiSeparatesStudentAndOrganizerRoles() {
    Entry e = signup(owner);
    assertEquals(401, http.getForEntity("/api/registrations", Map.class).getStatusCode().value());
    assertEquals(
        403,
        http.postForEntity(
                "/api/admin/events/" + eventId + "/check-in",
                new HttpEntity<>(new CheckIn(e.qrPayload()), headers(session.token())),
                Map.class)
            .getStatusCode()
            .value());
    Session admin = organizerSession();
    assertEquals(
        403,
        http.exchange(
                "/api/registrations",
                HttpMethod.GET,
                new HttpEntity<>(headers(admin.token())),
                Map.class)
            .getStatusCode()
            .value());
    assertEquals(
        200,
        http.postForEntity(
                "/api/admin/events/" + eventId + "/check-in",
                new HttpEntity<>(new CheckIn(e.qrPayload()), headers(admin.token())),
                Map.class)
            .getStatusCode()
            .value());
  }

  @Test
  void apiValidatesInputAndRejectsClientSuppliedStatus() {
    for (String json :
        List.of(
            "{\"requestKey\":\"\"}",
            "{\"requestKey\":\"key\",\"status\":\"CONFIRMED\"}",
            "{\"requestKey\":\"key\",\"seatCode\":\"bad\"}"))
      assertEquals(
          400,
          http.postForEntity(
                  "/api/events/" + eventId + "/registrations",
                  new HttpEntity<>(json, headers(session.token())),
                  Map.class)
              .getStatusCode()
              .value());
    assertEquals(
        400,
        http.postForEntity(
                "/api/auth/register", new Register("Student", "bad", "x", "short"), Map.class)
            .getStatusCode()
            .value());
  }

  @Test
  void apiRejectsInvalidCapacity() {
    var h = headers(organizerSession().token());
    for (int capacity : List.of(0, 501))
      assertEquals(
          400,
          http.postForEntity(
                  "/api/admin/events",
                  new HttpEntity<>(
                      new NewEvent(
                          "Talk",
                          "Guest Talk",
                          "Hall",
                          "Test",
                          clock.millis() + 3600000L,
                          capacity,
                          10,
                          "",
                          "Club",
                          "GENERAL"),
                      h),
                  Map.class)
              .getStatusCode()
              .value());
  }

  @Test
  void expiredLoginIsRejected() {
    clock.advance(8 * 86400000L);
    assertEquals(
        401,
        assertThrows(ApiError.class, () -> auth.require("Bearer " + session.token(), false))
            .status);
  }

  @Test
  void upgradePreservesConfirmedLegacyTicketAndRunsOnce() {
    String event = create(17, "ASSIGNED");
    db.update("UPDATE events SET capacity=0 WHERE id=?", event);
    String hold = UUID.randomUUID().toString(), booking = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO holds(id,student_id,event_id,seat_codes,total,expires_at,status,created_at)"
            + " VALUES(?,?,?,?,0,?,'CONFIRMED',?)",
        hold,
        owner,
        event,
        "A1",
        clock.millis() + 300000,
        clock.millis());
    db.update(
        "INSERT INTO"
            + " bookings(id,student_id,event_id,hold_id,seat_codes,total,status,request_key,created_at)"
            + " VALUES(?,?,?,?,?,0,'CONFIRMED',?,?)",
        booking,
        owner,
        event,
        hold,
        "A1",
        "legacy-request",
        clock.millis());
    db.update("DELETE FROM app_migrations WHERE name='registration-v2'");
    upgrade.migrate();
    upgrade.migrate();
    Entry imported = service.mine(owner).get(0);
    assertEquals(booking, imported.id());
    assertEquals("A1", imported.seatCode());
    assertNotNull(imported.qrPayload());
    assertEquals(16, service.event(event).available());
    assertEquals("ASSIGNED", service.event(event).bookingMode());
  }
}
