-- V15 - add optional phone number to users, for the SMS notification
-- module (Durbar SMS system). Nullable: existing users have no phone
-- number on record yet and must be backfilled via the Users page or a
-- direct UPDATE; the app must keep working for users with none (they
-- simply receive no SMS alerts, per NotificationRecipientResolver).
--
-- Stored format is exactly what Durbar's sendsms API requires: "8801"
-- followed by 9 digits (13 digits total, e.g. 8801791027113). The UI
-- locks the "8801" prefix and only lets the user type the remaining
-- 9 digits, so no format conversion is needed between storage and the
-- Durbar API call. The CHECK constraint enforces this shape at the DB
-- level as a second line of defense against bad data (e.g. direct SQL
-- inserts, future API clients that bypass the UI).
ALTER TABLE users ADD COLUMN phone_number VARCHAR(20);

ALTER TABLE users ADD CONSTRAINT users_phone_number_format
    CHECK (phone_number IS NULL OR phone_number ~ '^8801[0-9]{9}$');
