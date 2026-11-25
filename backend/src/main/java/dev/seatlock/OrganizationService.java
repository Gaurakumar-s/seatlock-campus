package dev.seatlock;

import static dev.seatlock.Models.*;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
  public static final String DEMO_ID = "campus-demo";
  private final JdbcTemplate db;
  private final AuthService auth;
  private final Clock clock;

  public OrganizationService(JdbcTemplate db, AuthService auth, Clock clock) {
    this.db = db;
    this.auth = auth;
    this.clock = clock;
  }

  public List<Organization> all() {
    return db.query(
        "SELECT * FROM organizations ORDER BY name",
        (r, n) ->
            new Organization(
                r.getString("id"),
                r.getString("name"),
                r.getString("email_domain"),
                r.getBoolean("demo"),
                null));
  }

  public List<Organization> mine(String organizer) {
    return db.query(
        "SELECT o.*,m.role FROM organizations o JOIN organization_organizers m ON"
            + " m.organization_id=o.id WHERE m.organizer_id=? ORDER BY o.name",
        (r, n) ->
            new Organization(
                r.getString("id"),
                r.getString("name"),
                r.getString("email_domain"),
                r.getBoolean("demo"),
                r.getString("role")),
        organizer);
  }

  public String require(String organizer, String organization, boolean owner) {
    if (organization == null || organization.isBlank())
      throw new ApiError(400, "ORGANIZATION_REQUIRED", "Select a college workspace.");
    if (!auth.organizer(organizer).emailVerified())
      throw new ApiError(403, "EMAIL_NOT_VERIFIED", "Verify your organizer email first.");
    var rows =
        db.queryForList(
            "SELECT role FROM organization_organizers WHERE organizer_id=? AND organization_id=?",
            organizer,
            organization);
    if (rows.isEmpty() || (owner && !rows.get(0).get("role").equals("OWNER")))
      throw new ApiError(
          403,
          "ORGANIZATION_FORBIDDEN",
          owner
              ? "Only this workspace's owner can manage organizers."
              : "You do not have access to that workspace.");
    return organization;
  }

  public String event(String organizer, String eventId) {
    var rows = db.queryForList("SELECT organization_id FROM events WHERE id=?", eventId);
    if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "Event not found.");
    String organization = (String) rows.get(0).get("organization_id");
    require(organizer, organization, false);
    return organization;
  }

  @Transactional
  public Organization create(String organizer, NewOrganization input) {
    OrganizerAccount account = auth.organizer(organizer);
    if (!account.emailVerified())
      throw new ApiError(403, "EMAIL_NOT_VERIFIED", "Verify your organizer email first.");
    String domain = input.emailDomain().strip().toLowerCase(Locale.ROOT);
    if (!domain.matches("(?=.{1,160}$)([a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}"))
      throw new ApiError(
          400, "INVALID_DOMAIN", "Enter a full email domain, without @ or a web address.");
    if (Set.of(
            "gmail.com",
            "googlemail.com",
            "outlook.com",
            "hotmail.com",
            "yahoo.com",
            "icloud.com",
            "proton.me",
            "protonmail.com")
        .contains(domain))
      throw new ApiError(
          400, "PUBLIC_DOMAIN", "Use your college's email domain, not a public email provider.");
    if (!AuthService.domain(account.email()).equals(domain))
      throw new ApiError(
          403,
          "DOMAIN_MISMATCH",
          "Create the workspace using an organizer account verified at this exact college email"
              + " domain.");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO organizations(id,name,email_domain,created_at) VALUES(?,?,?,?)",
        id,
        input.name().strip(),
        domain,
        clock.millis());
    db.update(
        "INSERT INTO organization_organizers(organization_id,organizer_id,role)"
            + " VALUES(?,?,'OWNER')",
        id,
        organizer);
    return new Organization(id, input.name().strip(), domain, false, "OWNER");
  }

  public List<Map<String, Object>> team(String organizer, String organization) {
    require(organizer, organization, false);
    return db.queryForList(
        "SELECT a.id,a.name,a.email,m.role FROM organizers a JOIN organization_organizers m ON"
            + " a.id=m.organizer_id WHERE m.organization_id=? ORDER BY a.name",
        organization);
  }

  @Transactional
  public Object add(String organizer, String organization, String email) {
    require(organizer, organization, true);
    db.queryForMap("SELECT id FROM organizations WHERE id=? FOR UPDATE", organization);
    var rows =
        db.queryForList(
            "SELECT id,email,email_verified_at FROM organizers WHERE email=?",
            AuthService.email(email));
    if (rows.isEmpty() || rows.get(0).get("email_verified_at") == null)
      throw new ApiError(
          400,
          "ORGANIZER_NOT_READY",
          "Ask this person to create and verify their organizer account first.");
    String domain =
        db.queryForObject(
            "SELECT email_domain FROM organizations WHERE id=?", String.class, organization);
    if (!AuthService.domain(email).equals(domain))
      throw new ApiError(
          400, "DOMAIN_MISMATCH", "Team members must use this college's exact email domain.");
    String id = (String) rows.get(0).get("id");
    if (db.queryForList(
            "SELECT role FROM organization_organizers WHERE organization_id=? AND organizer_id=?",
            organization,
            id)
        .isEmpty())
      db.update(
          "INSERT INTO organization_organizers(organization_id,organizer_id,role)"
              + " VALUES(?,?,'ORGANIZER')",
          organization,
          id);
    return Map.of("added", true);
  }

  @Transactional
  public void remove(String organizer, String organization, String member) {
    require(organizer, organization, true);
    db.queryForMap("SELECT id FROM organizations WHERE id=? FOR UPDATE", organization);
    db.update(
        "DELETE FROM organization_organizers WHERE organization_id=? AND organizer_id=? AND"
            + " role='ORGANIZER'",
        organization,
        member);
  }

  public void eligible(String student, String eventId) {
    var rows =
        db.queryForList(
            "SELECT s.email,s.email_verified_at,o.email_domain,e.audience FROM students s CROSS"
                + " JOIN events e JOIN organizations o ON o.id=e.organization_id WHERE s.id=? AND"
                + " e.id=?",
            student,
            eventId);
    if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "Student or event not found.");
    var row = rows.get(0);
    if (!auth.student(student).emailVerified())
      throw new ApiError(
          403, "EMAIL_NOT_VERIFIED", "Verify your email before registering for an event.");
    if (row.get("audience").equals("COLLEGE")
        && !AuthService.domain((String) row.get("email")).equals(row.get("email_domain")))
      throw new ApiError(
          403,
          "COLLEGE_ONLY",
          "This event is limited to verified @"
              + row.get("email_domain")
              + " accounts. Browse open events to join from another college.");
  }
}
