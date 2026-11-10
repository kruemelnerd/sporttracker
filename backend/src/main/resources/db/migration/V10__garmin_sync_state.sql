CREATE TABLE garmin_sync_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    last_attempt_at TIMESTAMPTZ,
    last_successful_sync_at TIMESTAMPTZ,
    last_successful_authentication_at TIMESTAMPTZ,
    retry_after_until TIMESTAMPTZ,
    checked_count INTEGER NOT NULL DEFAULT 0 CHECK (checked_count >= 0),
    imported_count INTEGER NOT NULL DEFAULT 0 CHECK (imported_count >= 0),
    already_present_count INTEGER NOT NULL DEFAULT 0 CHECK (already_present_count >= 0),
    skipped_count INTEGER NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
    failed_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    last_error_code VARCHAR(64)
);
