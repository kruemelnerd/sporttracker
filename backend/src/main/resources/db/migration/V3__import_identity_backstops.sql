ALTER TABLE import_file DROP CONSTRAINT import_file_activity_id_key;
ALTER TABLE activity ADD CONSTRAINT activity_sport_start_time_key UNIQUE (sport, start_time);
