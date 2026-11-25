CREATE TABLE IF NOT EXISTS students (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(80) NOT NULL,
  email VARCHAR(160) NOT NULL UNIQUE, student_number VARCHAR(30) NOT NULL,
  password_hash VARCHAR(100) NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  id VARCHAR(64) PRIMARY KEY, role VARCHAR(16) NOT NULL, student_id VARCHAR(36) REFERENCES students(id),
  created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
  id VARCHAR(36) PRIMARY KEY, title VARCHAR(100) NOT NULL,
  category VARCHAR(30) NOT NULL, venue VARCHAR(120) NOT NULL,
  description VARCHAR(2000) NOT NULL, starts_at BIGINT NOT NULL,
  base_price INTEGER NOT NULL CHECK (base_price >= 0), theme VARCHAR(16) NOT NULL, speaker VARCHAR(100) NOT NULL, host_club VARCHAR(100) NOT NULL, seats_per_row INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS holds (
  id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36) NOT NULL REFERENCES students(id),
  event_id VARCHAR(36) NOT NULL REFERENCES events(id), seat_codes VARCHAR(200) NOT NULL,
  total INTEGER NOT NULL, expires_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS holds_owner ON holds(student_id);
CREATE TABLE IF NOT EXISTS bookings (
  id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36) NOT NULL REFERENCES students(id),
  event_id VARCHAR(36) NOT NULL REFERENCES events(id), hold_id VARCHAR(36) NOT NULL UNIQUE REFERENCES holds(id),
  seat_codes VARCHAR(200) NOT NULL, total INTEGER NOT NULL, status VARCHAR(16) NOT NULL,
  request_key VARCHAR(80) NOT NULL, created_at BIGINT NOT NULL,
  UNIQUE(student_id, request_key)
);
CREATE TABLE IF NOT EXISTS seats (
  event_id VARCHAR(36) NOT NULL REFERENCES events(id), code VARCHAR(8) NOT NULL, seat_index INTEGER NOT NULL,
  price INTEGER NOT NULL CHECK (price >= 0), hold_id VARCHAR(36) REFERENCES holds(id),
  hold_expires_at BIGINT, booking_id VARCHAR(36) REFERENCES bookings(id),
  PRIMARY KEY(event_id, code), CHECK (hold_id IS NULL OR booking_id IS NULL)
);

-- Only confirmed registrations live here; cancelling deletes the entry.
-- The unique key is an additional database guard against two confirmed seats.
CREATE TABLE IF NOT EXISTS registrations (
  student_id VARCHAR(36) NOT NULL REFERENCES students(id),
  event_id VARCHAR(36) NOT NULL REFERENCES events(id),
  booking_id VARCHAR(36) NOT NULL UNIQUE REFERENCES bookings(id),
  PRIMARY KEY(student_id,event_id)
);

-- Additive upgrade from the first Campus release. Legacy booking tables above
-- are retained for a one-time import; new writes use event_entries exclusively.
ALTER TABLE events ADD COLUMN IF NOT EXISTS capacity INTEGER NOT NULL DEFAULT 0;
ALTER TABLE events ADD COLUMN IF NOT EXISTS booking_mode VARCHAR(16) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE events ADD COLUMN IF NOT EXISTS next_sequence BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS event_entries (
  id VARCHAR(36) PRIMARY KEY,
  student_id VARCHAR(36) NOT NULL REFERENCES students(id),
  event_id VARCHAR(36) NOT NULL REFERENCES events(id),
  status VARCHAR(16) NOT NULL CHECK(status IN ('CONFIRMED','WAITLISTED','CANCELLED')),
  seat_code VARCHAR(8), queue_sequence BIGINT NOT NULL,
  request_key VARCHAR(80) NOT NULL, created_at BIGINT NOT NULL,
  promoted_at BIGINT, cancelled_at BIGINT, checked_in_at BIGINT,
  checkin_token VARCHAR(64) UNIQUE,
  UNIQUE(student_id,request_key),
  UNIQUE(event_id,seat_code),
  CHECK(status='CONFIRMED' OR (seat_code IS NULL AND checkin_token IS NULL AND checked_in_at IS NULL))
);
CREATE INDEX IF NOT EXISTS entries_queue ON event_entries(event_id,status,queue_sequence);
CREATE TABLE IF NOT EXISTS active_entries (
  student_id VARCHAR(36) NOT NULL REFERENCES students(id),
  event_id VARCHAR(36) NOT NULL REFERENCES events(id),
  entry_id VARCHAR(36) NOT NULL UNIQUE REFERENCES event_entries(id),
  PRIMARY KEY(student_id,event_id)
);
CREATE TABLE IF NOT EXISTS app_migrations (name VARCHAR(80) PRIMARY KEY, applied_at BIGINT NOT NULL);

-- Multi-college identities. Existing student accounts are retained, unverified.
ALTER TABLE students ADD COLUMN IF NOT EXISTS email_verified_at BIGINT;
CREATE TABLE IF NOT EXISTS organizers (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(80) NOT NULL,
  email VARCHAR(160) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
  email_verified_at BIGINT
);
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS organizer_id VARCHAR(36) REFERENCES organizers(id);
CREATE TABLE IF NOT EXISTS organizations (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL,
  email_domain VARCHAR(160) NOT NULL, created_at BIGINT NOT NULL,
  demo BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS organization_organizers (
  organization_id VARCHAR(36) NOT NULL REFERENCES organizations(id),
  organizer_id VARCHAR(36) NOT NULL REFERENCES organizers(id),
  role VARCHAR(16) NOT NULL CHECK(role IN ('OWNER','ORGANIZER')),
  PRIMARY KEY(organization_id,organizer_id)
);
ALTER TABLE events ADD COLUMN IF NOT EXISTS organization_id VARCHAR(36) REFERENCES organizations(id);
ALTER TABLE events ADD COLUMN IF NOT EXISTS audience VARCHAR(16) NOT NULL DEFAULT 'COLLEGE';
CREATE INDEX IF NOT EXISTS events_organization ON events(organization_id);
CREATE TABLE IF NOT EXISTS email_challenges (
  account_id VARCHAR(36) NOT NULL, role VARCHAR(16) NOT NULL,
  code_hash VARCHAR(100) NOT NULL, expires_at BIGINT NOT NULL,
  sent_at BIGINT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(account_id,role)
);

ALTER TABLE students ADD COLUMN IF NOT EXISTS email_verification_mode VARCHAR(16);
ALTER TABLE organizers ADD COLUMN IF NOT EXISTS email_verification_mode VARCHAR(16);
