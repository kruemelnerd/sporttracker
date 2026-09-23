ALTER TABLE activity ADD COLUMN maximum_heart_rate INTEGER;

ALTER TABLE activity DROP CONSTRAINT IF EXISTS activity_sport_start_time_key;
