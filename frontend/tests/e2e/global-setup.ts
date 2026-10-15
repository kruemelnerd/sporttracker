import { spawnSync } from 'node:child_process'

export default function globalSetup() {
  const sql = `DO $$ BEGIN
    IF current_database() <> 'running_dashboard_e2e' OR current_user <> 'running_dashboard_e2e' THEN
      RAISE EXCEPTION 'Refusing to reset non-E2E database: % / %', current_database(), current_user;
    END IF;
    EXECUTE 'TRUNCATE activity, import_file, activity_record, lap RESTART IDENTITY CASCADE';
    IF to_regclass('public.training_plan') IS NOT NULL THEN
      EXECUTE 'TRUNCATE training_plan CASCADE';
    END IF;
  END $$;`
  const result = spawnSync('docker', [
    'compose', '-p', 'sporttracker-m4-e2e', '-f', '../compose.e2e.yaml', 'exec', '-T', 'db',
    'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'running_dashboard_e2e', '-d', 'running_dashboard_e2e', '-c', sql,
  ], { encoding: 'utf8' })
  if (result.status !== 0) throw new Error(`E2E database reset refused/failed: ${result.stderr || result.stdout}`)
}
