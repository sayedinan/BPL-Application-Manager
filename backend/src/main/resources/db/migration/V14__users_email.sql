-- V14 - add optional email address to users, for the notification
-- module (offline-application alerts). Nullable: existing users have
-- no email on record yet and must be backfilled via the Users page
-- or a direct UPDATE; the app must keep working for users with none
-- (they simply receive no email alerts, per NotificationRecipientResolver).
ALTER TABLE users ADD COLUMN email VARCHAR(255);
