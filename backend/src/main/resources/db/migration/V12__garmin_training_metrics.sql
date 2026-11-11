ALTER TABLE activity
    ADD COLUMN garmin_training_effect DOUBLE PRECISION,
    ADD COLUMN garmin_anaerobic_training_effect DOUBLE PRECISION,
    ADD COLUMN garmin_activity_training_load DOUBLE PRECISION,
    ADD COLUMN garmin_enrichment_complete BOOLEAN,
    ADD CONSTRAINT activity_garmin_training_effect_range
        CHECK (garmin_training_effect IS NULL OR garmin_training_effect BETWEEN 0 AND 5),
    ADD CONSTRAINT activity_garmin_anaerobic_training_effect_range
        CHECK (garmin_anaerobic_training_effect IS NULL OR garmin_anaerobic_training_effect BETWEEN 0 AND 5),
    ADD CONSTRAINT activity_garmin_training_load_range
        CHECK (garmin_activity_training_load IS NULL OR garmin_activity_training_load BETWEEN 0 AND 10000);
