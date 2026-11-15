ALTER TABLE activity_record
    ADD COLUMN step_length_m DOUBLE PRECISION CHECK (step_length_m IS NULL OR step_length_m >= 0),
    ADD COLUMN vertical_oscillation_cm DOUBLE PRECISION CHECK (vertical_oscillation_cm IS NULL OR vertical_oscillation_cm >= 0),
    ADD COLUMN vertical_ratio_percent DOUBLE PRECISION CHECK (vertical_ratio_percent IS NULL OR vertical_ratio_percent BETWEEN 0 AND 100),
    ADD COLUMN ground_contact_time_ms DOUBLE PRECISION CHECK (ground_contact_time_ms IS NULL OR ground_contact_time_ms >= 0),
    ADD COLUMN ground_contact_balance_percent DOUBLE PRECISION CHECK (ground_contact_balance_percent IS NULL OR ground_contact_balance_percent BETWEEN 0 AND 100),
    ADD COLUMN temperature_celsius DOUBLE PRECISION CHECK (temperature_celsius IS NULL OR temperature_celsius BETWEEN -100 AND 100),
    ADD COLUMN power_watts DOUBLE PRECISION CHECK (power_watts IS NULL OR power_watts >= 0);

CREATE TABLE activity_workout_step (
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    step_index INTEGER NOT NULL CHECK (step_index >= 0),
    step_name TEXT,
    duration_type TEXT,
    planned_duration_seconds DOUBLE PRECISION CHECK (planned_duration_seconds IS NULL OR planned_duration_seconds >= 0),
    planned_distance_meters DOUBLE PRECISION CHECK (planned_distance_meters IS NULL OR planned_distance_meters >= 0),
    repeat_from_step_index INTEGER,
    repeat_count INTEGER CHECK (repeat_count IS NULL OR repeat_count >= 0),
    intensity TEXT,
    PRIMARY KEY (activity_id, step_index)
);

ALTER TABLE lap
    ADD COLUMN workout_step_index INTEGER,
    ADD COLUMN repetition_number INTEGER,
    ADD COLUMN start_elapsed_seconds DOUBLE PRECISION CHECK (start_elapsed_seconds IS NULL OR start_elapsed_seconds >= 0),
    ADD COLUMN end_elapsed_seconds DOUBLE PRECISION CHECK (end_elapsed_seconds IS NULL OR end_elapsed_seconds >= start_elapsed_seconds),
    ADD COLUMN start_distance_meters DOUBLE PRECISION CHECK (start_distance_meters IS NULL OR start_distance_meters >= 0),
    ADD COLUMN end_distance_meters DOUBLE PRECISION CHECK (end_distance_meters IS NULL OR end_distance_meters >= start_distance_meters);

CREATE TABLE activity_pause (
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    pause_number INTEGER NOT NULL CHECK (pause_number > 0),
    start_elapsed_seconds DOUBLE PRECISION NOT NULL CHECK (start_elapsed_seconds >= 0),
    end_elapsed_seconds DOUBLE PRECISION NOT NULL CHECK (end_elapsed_seconds > start_elapsed_seconds),
    PRIMARY KEY (activity_id, pause_number)
);
