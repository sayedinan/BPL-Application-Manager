-- Maintenance windows become "from X to Y" (V21 only stored the end).
ALTER TABLE application_maintenance ADD COLUMN starts_at TIMESTAMPTZ;
UPDATE application_maintenance SET starts_at = started_at;
ALTER TABLE application_maintenance ALTER COLUMN starts_at SET NOT NULL;
ALTER TABLE application_maintenance
    ADD CONSTRAINT application_maintenance_window_order CHECK (ends_at > starts_at);
