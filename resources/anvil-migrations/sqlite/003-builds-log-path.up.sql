-- Per-build streaming-log file path. When set, the build's stdout +
-- stderr were redirected directly to disk via the dispatcher's
-- `:log-file` opt rather than buffered in memory. The console-log
-- column may be empty in streaming mode — the file is the source of
-- truth, slurped on read.
ALTER TABLE anvil_builds ADD COLUMN log_path TEXT;
