-- V18 - add the user's full (original) name. Nullable because existing
-- users have none on record yet; new users must supply one (enforced by
-- CreateUserRequest), and old users are backfilled via the Edit form.
ALTER TABLE users ADD COLUMN full_name VARCHAR(150);
