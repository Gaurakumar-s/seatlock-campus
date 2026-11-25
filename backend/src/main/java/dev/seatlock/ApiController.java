package dev.seatlock;

import static dev.seatlock.Models.*;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final AuthService auth;
  private final RegistrationService service;
  private final OrganizationService organizations;
  private final VerificationService verification;

  public ApiController(
      AuthService auth,
      RegistrationService service,
      OrganizationService organizations,
      VerificationService verification) {
    this.auth = auth;
    this.service = service;
    this.organizations = organizations;
    this.verification = verification;
  }

  @GetMapping("/health")
  Object health() {
    return Map.of("status", "ok");
  }

  @GetMapping("/auth/config")
  Object config() {
    return verification.config();
  }

  @PostMapping("/auth/register")
  Object register(@Valid @RequestBody Register input) {
    return auth.register(input);
  }

  @PostMapping("/auth/login")
  Object login(@Valid @RequestBody Login input) {
    return auth.login(input);
  }

  @GetMapping("/auth/me")
  Object me(@RequestHeader(value = "Authorization", required = false) String h) {
    return auth.me(h);
  }

  @PostMapping("/auth/logout")
  Object logout(@RequestHeader(value = "Authorization", required = false) String h) {
    auth.logout(h);
    return Map.of("signedOut", true);
  }

  @PostMapping("/organizer/register")
  Object organizerRegister(@Valid @RequestBody OrganizerRegister input) {
    return auth.registerOrganizer(input);
  }

  @PostMapping("/organizer/login")
  Object organizerLogin(@Valid @RequestBody Login input) {
    return auth.login(input, true);
  }

  @GetMapping("/organizer/me")
  Object organizerMe(@RequestHeader(value = "Authorization", required = false) String h) {
    return auth.organizerMe(h);
  }

  @PostMapping("/verification/send")
  Object send(@RequestHeader(value = "Authorization", required = false) String h) {
    return verification.send(auth.principal(h));
  }

  @PostMapping("/verification/confirm")
  Object confirm(
      @Valid @RequestBody VerifyCode input,
      @RequestHeader(value = "Authorization", required = false) String h) {
    return verification.confirm(auth.principal(h), input.code());
  }

  @GetMapping("/verification/test-inbox")
  Object inbox(@RequestHeader(value = "Authorization", required = false) String h) {
    return verification.testInbox(auth.principal(h));
  }

  @GetMapping("/organizations")
  Object allOrganizations() {
    return organizations.all();
  }

  @GetMapping("/organizer/organizations")
  Object myOrganizations(@RequestHeader(value = "Authorization", required = false) String h) {
    return organizations.mine(auth.require(h, true));
  }

  @PostMapping("/organizer/organizations")
  Object newOrganization(
      @Valid @RequestBody NewOrganization input,
      @RequestHeader(value = "Authorization", required = false) String h) {
    return organizations.create(auth.require(h, true), input);
  }

  @GetMapping("/organizer/organizations/{id}/team")
  Object team(
      @PathVariable String id, @RequestHeader(value = "Authorization", required = false) String h) {
    return organizations.team(auth.require(h, true), id);
  }

  @PostMapping("/organizer/organizations/{id}/team")
  Object add(
      @PathVariable String id,
      @Valid @RequestBody AddOrganizer input,
      @RequestHeader(value = "Authorization", required = false) String h) {
    return organizations.add(auth.require(h, true), id, input.email());
  }

  @DeleteMapping("/organizer/organizations/{id}/team/{member}")
  Object remove(
      @PathVariable String id,
      @PathVariable String member,
      @RequestHeader(value = "Authorization", required = false) String h) {
    organizations.remove(auth.require(h, true), id, member);
    return Map.of("removed", true);
  }

  @GetMapping("/events")
  Object events() {
    return service.events();
  }

  @GetMapping("/events/{id}")
  Object event(@PathVariable String id) {
    return service.event(id);
  }

  @GetMapping("/events/{id}/seats")
  Object seats(@PathVariable String id) {
    return service.seats(id);
  }

  @PostMapping("/events/{id}/registrations")
  Object signup(
      @PathVariable String id,
      @Valid @RequestBody Signup input,
      @RequestHeader(value = "Authorization", required = false) String h) {
    return service.register(auth.require(h, false), id, input);
  }

  @GetMapping("/registrations")
  Object mine(@RequestHeader(value = "Authorization", required = false) String h) {
    return service.mine(auth.require(h, false));
  }

  @DeleteMapping("/registrations/{id}")
  Object cancel(
      @PathVariable String id, @RequestHeader(value = "Authorization", required = false) String h) {
    return service.cancel(auth.require(h, false), id);
  }

  @GetMapping("/admin/overview")
  Object overview(
      @RequestHeader(value = "Authorization", required = false) String h,
      @RequestHeader(value = "X-Organization-ID", required = false) String org) {
    return service.overview(organizations.require(auth.require(h, true), org, false));
  }

  @PostMapping("/admin/events")
  Object create(
      @Valid @RequestBody NewEvent input,
      @RequestHeader(value = "Authorization", required = false) String h,
      @RequestHeader(value = "X-Organization-ID", required = false) String org) {
    return Map.of(
        "id", service.createEvent(organizations.require(auth.require(h, true), org, false), input));
  }

  @GetMapping("/admin/events/{id}/registrations")
  Object roster(
      @PathVariable String id, @RequestHeader(value = "Authorization", required = false) String h) {
    organizations.event(auth.require(h, true), id);
    return service.roster(id);
  }

  @PostMapping("/admin/events/{id}/check-in")
  Object checkIn(
      @PathVariable String id,
      @Valid @RequestBody CheckIn input,
      @RequestHeader(value = "Authorization", required = false) String h) {
    organizations.event(auth.require(h, true), id);
    return service.checkIn(id, input.qrPayload());
  }
}
