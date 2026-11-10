ALTER TABLE import_file
    ADD COLUMN source VARCHAR(20) CHECK (source IS NULL OR source IN ('MANUAL', 'GARMIN', 'INTERVALS_ICU'));

CREATE TABLE data_management_sync_pause (
    provider VARCHAR(20) PRIMARY KEY CHECK (provider IN ('GARMIN', 'INTERVALS_ICU')),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO data_management_sync_pause (provider) VALUES ('GARMIN'), ('INTERVALS_ICU');

CREATE TABLE pending_file_deletion (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    relative_path TEXT NOT NULL UNIQUE CHECK (length(trim(relative_path)) > 0),
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'FAILED', 'DONE')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX pending_file_deletion_status_idx ON pending_file_deletion(status, created_at);
