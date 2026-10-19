CREATE TABLE activity_import_source (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider VARCHAR(50) NOT NULL CHECK (length(trim(provider)) > 0),
    external_id VARCHAR(255) NOT NULL CHECK (length(trim(external_id)) > 0),
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider, external_id)
);

CREATE INDEX activity_import_source_activity_idx ON activity_import_source(activity_id);
