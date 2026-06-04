-- SQLite < 3.35 has no DROP COLUMN; the 008 down rebuilds the table
-- without ANY of the SCM columns; 009 and 010 downs are no-ops because
-- 008's rollback already removed their columns.
CREATE TABLE anvil_jobs_pre008 AS
  SELECT name, jenkinsfile_source, buildable, color,
         last_build, last_successful_build, last_failed_build,
         created_at, updated_at
  FROM anvil_jobs;
--;;
DROP TABLE anvil_jobs;
--;;
ALTER TABLE anvil_jobs_pre008 RENAME TO anvil_jobs;
