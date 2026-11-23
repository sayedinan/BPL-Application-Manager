-- V17 - per-application, per-event custom notification message
-- templates. Nullable: when null, EmailNotificationService/
-- SmsNotificationService fall back to their existing hardcoded
-- default wording (per Inan's decision — blank means "use default").
--
-- Placeholders supported in the text: {appName}, {actor}, {role},
-- {timestamp}. {actor}/{role} are only meaningful for the two
-- dashboard-triggered events (start/stop) — external events have no
-- logged-in actor, so those placeholders resolve to "N/A" there.
--
-- SMS columns are capped at 160 characters (single-segment SMS) at
-- the template level. Note this caps the TEMPLATE text, not the
-- final message after placeholder substitution — a template at
-- exactly 160 chars that also uses {appName} will exceed 160 once
-- substituted, which is why SmsNotificationService additionally
-- truncates the final, substituted text defensively before sending.
ALTER TABLE applications ADD COLUMN email_start_message TEXT;
ALTER TABLE applications ADD COLUMN email_stop_message TEXT;
ALTER TABLE applications ADD COLUMN email_external_online_message TEXT;
ALTER TABLE applications ADD COLUMN email_external_offline_message TEXT;

ALTER TABLE applications ADD COLUMN sms_start_message TEXT
    CHECK (char_length(sms_start_message) <= 160);
ALTER TABLE applications ADD COLUMN sms_stop_message TEXT
    CHECK (char_length(sms_stop_message) <= 160);
ALTER TABLE applications ADD COLUMN sms_external_online_message TEXT
    CHECK (char_length(sms_external_online_message) <= 160);
ALTER TABLE applications ADD COLUMN sms_external_offline_message TEXT
    CHECK (char_length(sms_external_offline_message) <= 160);
