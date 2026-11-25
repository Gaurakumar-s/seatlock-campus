package dev.seatlock;

import jakarta.validation.constraints.*;

public final class Models {
  private Models() {}

  public record Student(
      String id, String name, String email, String studentNumber, boolean emailVerified) {}

  public record OrganizerAccount(String id, String name, String email, boolean emailVerified) {}

  public record Session(String token, String role, Student student, OrganizerAccount organizer) {}

  public record OrganizerRegister(
      @NotBlank @Size(max = 80) String name,
      @NotBlank @Email @Size(max = 160) String email,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  public record VerifyCode(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

  public record NewOrganization(
      @NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 160) String emailDomain) {}

  public record AddOrganizer(@NotBlank @Email @Size(max = 160) String email) {}

  public record Organization(
      String id, String name, String emailDomain, boolean demo, String role) {}

  public record Register(
      @NotBlank @Size(max = 80) String name,
      @NotBlank @Email @Size(max = 160) String email,
      @Pattern(regexp = "[A-Za-z0-9-]{3,30}") String studentNumber,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  public record Login(
      @NotBlank @Email @Size(max = 160) String email, @NotBlank @Size(max = 72) String password) {}

  public record AdminLogin(@NotBlank @Size(max = 200) String password) {}

  public record Event(
      String id,
      String title,
      String category,
      String venue,
      String description,
      long startsAt,
      String theme,
      int available,
      int capacity,
      String speaker,
      String hostClub,
      int seatsPerRow,
      String bookingMode,
      int waitlisted,
      int checkedIn,
      String organizationId,
      String organizationName,
      String audience) {}

  public record Seat(String code, String status) {}

  public record Signup(
      @NotBlank @Size(max = 80) String requestKey,
      @Pattern(regexp = "[A-Z]{1,2}[1-9][0-9]?") String seatCode) {}

  public record Entry(
      String id,
      String eventId,
      String eventTitle,
      String venue,
      long startsAt,
      String status,
      String seatCode,
      int waitlistPosition,
      long createdAt,
      Long promotedAt,
      Long checkedInAt,
      String studentName,
      String studentNumber,
      String qrPayload,
      String bookingMode) {}

  public record CheckIn(@NotBlank @Size(max = 100) String qrPayload) {}

  public record CheckInResult(Entry registration, boolean alreadyCheckedIn) {}

  public record NewEvent(
      @NotBlank @Size(max = 100) String title,
      @NotBlank @Pattern(regexp = "Guest Talk|Workshop|Cultural|Club Meetup") String category,
      @NotBlank @Size(max = 120) String venue,
      @NotBlank @Size(max = 2000) String description,
      @NotNull Long startsAt,
      @Min(1) @Max(500) int capacity,
      @Min(8) @Max(20) int seatsPerRow,
      @NotNull @Size(max = 100) String speaker,
      @NotBlank @Size(max = 100) String hostClub,
      @NotBlank @Pattern(regexp = "GENERAL|ASSIGNED") String bookingMode,
      @NotBlank @Pattern(regexp = "COLLEGE|OPEN") String audience) {
    public NewEvent(
        String title,
        String category,
        String venue,
        String description,
        Long startsAt,
        int capacity,
        int seatsPerRow,
        String speaker,
        String hostClub,
        String bookingMode) {
      this(
          title,
          category,
          venue,
          description,
          startsAt,
          capacity,
          seatsPerRow,
          speaker,
          hostClub,
          bookingMode,
          "COLLEGE");
    }
  }
}
