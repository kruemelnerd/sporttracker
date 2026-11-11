ALTER TABLE activity
    ADD COLUMN garmin_activity_name VARCHAR(200),
    ADD COLUMN garmin_average_speed_mps DOUBLE PRECISION,
    ADD CONSTRAINT activity_garmin_average_speed_range
        CHECK (garmin_average_speed_mps IS NULL OR garmin_average_speed_mps BETWEEN 0 AND 20);
