CREATE TABLE training_plan (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    goal VARCHAR(200) NOT NULL CHECK (length(trim(goal)) > 0),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL CHECK (end_date >= start_date),
    race_date DATE,
    target_time_minutes INTEGER CHECK (target_time_minutes IS NULL OR target_time_minutes > 0),
    description TEXT
);

CREATE TABLE training_phase (
    id UUID PRIMARY KEY,
    training_plan_id UUID NOT NULL REFERENCES training_plan(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL CHECK (end_date >= start_date),
    description TEXT
);

CREATE INDEX training_phase_plan_dates_idx ON training_phase(training_plan_id, start_date, end_date);

CREATE TABLE planned_session (
    id UUID PRIMARY KEY,
    training_plan_id UUID NOT NULL REFERENCES training_plan(id) ON DELETE CASCADE,
    session_date DATE NOT NULL,
    sport VARCHAR(16) NOT NULL CHECK (sport IN ('RUN', 'BIKE', 'STRENGTH', 'OTHER')),
    session_type VARCHAR(16) NOT NULL CHECK (session_type IN ('EASY', 'RECOVERY', 'LONG_RUN', 'TEMPO', 'INTERVAL', 'TEST', 'RACE', 'OTHER')),
    title VARCHAR(200) NOT NULL CHECK (length(trim(title)) > 0),
    target_distance_km DOUBLE PRECISION CHECK (target_distance_km IS NULL OR target_distance_km > 0),
    target_duration_minutes INTEGER CHECK (target_duration_minutes IS NULL OR target_duration_minutes > 0),
    target_pace_from DOUBLE PRECISION CHECK (target_pace_from IS NULL OR target_pace_from > 0),
    target_pace_to DOUBLE PRECISION CHECK (target_pace_to IS NULL OR target_pace_to > 0),
    target_rpe INTEGER CHECK (target_rpe IS NULL OR target_rpe BETWEEN 1 AND 10),
    instructions TEXT,
    notes TEXT,
    CHECK (target_pace_from IS NULL OR target_pace_to IS NULL OR target_pace_from <= target_pace_to)
);

CREATE INDEX planned_session_plan_date_idx ON planned_session(training_plan_id, session_date);
