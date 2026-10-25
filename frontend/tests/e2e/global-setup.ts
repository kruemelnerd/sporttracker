import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const runId = process.env.TEST_RUN_ID ?? ''
const project = process.env.TEST_COMPOSE_PROJECT ?? ''
const database = process.env.TEST_DATABASE_NAME ?? ''
const username = process.env.TEST_DATABASE_USERNAME ?? ''

if (!/^[a-f0-9]{24}$/.test(runId) || project !== `sporttracker-ci-${runId}` ||
  database !== `running_dashboard_test_${runId}` || username !== database) {
  throw new Error('Refusing to reset a database without the current isolated test run identity')
}

export default function globalSetup() {
  const sql = `DO $$ BEGIN
    IF current_database() <> '${database}' OR current_user <> '${username}' OR
       (SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()) <> 'sporttracker-test:${runId}:${project}' THEN
      RAISE EXCEPTION 'Refusing to reset database with an unexpected test identity';
    END IF;
    EXECUTE 'TRUNCATE activity, import_file, activity_record, lap RESTART IDENTITY CASCADE';
    IF to_regclass('public.training_plan') IS NOT NULL THEN
      EXECUTE 'TRUNCATE training_plan CASCADE';
    END IF;
  END $$;`
  const result = spawnSync('docker', [
    'compose', '-p', project, '-f', fileURLToPath(new URL('../../../compose.test.yaml', import.meta.url)), 'exec', '-T', 'db',
    'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', username, '-d', database, '-c', sql,
  ], { encoding: 'utf8' })
  if (result.status !== 0) throw new Error(`E2E database reset refused/failed: ${result.stderr || result.stdout}`)
}
