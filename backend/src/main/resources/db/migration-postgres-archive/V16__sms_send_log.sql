-- V16 - log every SMS send attempt made through the Durbar SMS
-- gateway, since Durbar's own accountinfo counters (Available*SmsCount)
-- read 0 on our Postpaid Unlimited account and can't be trusted as a
-- usage source. This table is what future volume/cost-avoidance
-- calculations will be based on instead.
--
-- One row per (recipient, event) pair, not per batch call — even
-- though Durbar's sendsms API can accept comma-separated numbers in
-- one request, SmsNotificationService sends one call per recipient
-- so failures can be attributed to a specific number (see
-- notification-module design notes: this drove the per-recipient
-- decision over one batched call).
CREATE TABLE sms_send_log (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT REFERENCES applications(id),
    recipient_phone_number VARCHAR(20) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    is_error BOOLEAN NOT NULL,
    inserted_sms_id VARCHAR(50),
    response_message TEXT,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Supports "usage per application" and "usage per month" queries,
-- the two most likely shapes for future calculation per Inan's
-- stated plan to use this data later.
CREATE INDEX idx_sms_send_log_application_id ON sms_send_log(application_id);
CREATE INDEX idx_sms_send_log_sent_at ON sms_send_log(sent_at);
