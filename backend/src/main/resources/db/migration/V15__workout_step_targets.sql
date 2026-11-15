ALTER TABLE activity_workout_step
    ADD COLUMN target_type TEXT,
    ADD COLUMN target_zone INTEGER CHECK (target_zone IS NULL OR target_zone >= 0),
    ADD COLUMN target_low DOUBLE PRECISION CHECK (target_low IS NULL OR target_low >= 0),
    ADD COLUMN target_high DOUBLE PRECISION CHECK (target_high IS NULL OR target_high >= 0),
    ADD COLUMN target_unit VARCHAR(20),
    ADD CONSTRAINT activity_workout_step_target_range
        CHECK (target_low IS NULL OR target_high IS NULL OR target_high >= target_low);
