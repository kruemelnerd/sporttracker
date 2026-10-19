DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM planned_session
        GROUP BY training_plan_id, session_date, sport, session_type, title
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate planned sessions exist; resolve them before applying V9';
    END IF;
END $$;

CREATE UNIQUE INDEX planned_session_identity_idx
    ON planned_session (training_plan_id, session_date, sport, session_type, title);
