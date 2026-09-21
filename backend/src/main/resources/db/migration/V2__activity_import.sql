CREATE TABLE activity (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sport TEXT NOT NULL,
    sub_sport TEXT,
    start_time TIMESTAMPTZ NOT NULL,
    elapsed_seconds DOUBLE PRECISION,
    moving_seconds DOUBLE PRECISION,
    distance_meters DOUBLE PRECISION,
    average_speed_mps DOUBLE PRECISION,
    average_heart_rate INTEGER,
    average_cadence INTEGER,
    ascent_meters DOUBLE PRECISION,
    calories INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (elapsed_seconds IS NULL OR elapsed_seconds >= 0),
    CHECK (moving_seconds IS NULL OR moving_seconds >= 0),
    CHECK (distance_meters IS NULL OR distance_meters >= 0)
);

CREATE TABLE import_file (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    activity_id BIGINT NOT NULL UNIQUE REFERENCES activity(id) ON DELETE CASCADE,
    sha256 CHAR(64) NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    original_path TEXT,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE activity_record (
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    record_index INTEGER NOT NULL,
    record_time TIMESTAMPTZ,
    elapsed_seconds DOUBLE PRECISION,
    distance_meters DOUBLE PRECISION,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    altitude_meters DOUBLE PRECISION,
    speed_mps DOUBLE PRECISION,
    heart_rate INTEGER,
    cadence INTEGER,
    PRIMARY KEY (activity_id, record_index),
    CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE TABLE lap (
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    lap_number INTEGER NOT NULL CHECK (lap_number > 0),
    duration_seconds DOUBLE PRECISION,
    distance_meters DOUBLE PRECISION,
    average_speed_mps DOUBLE PRECISION,
    average_heart_rate INTEGER,
    average_cadence INTEGER,
    ascent_meters DOUBLE PRECISION,
    PRIMARY KEY (activity_id, lap_number)
);
