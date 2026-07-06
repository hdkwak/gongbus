-- Recalculate duration_seconds if it's vastly different from FIT's Session total_timer_time
-- This is a one-time migration script for existing data if needed,
-- but since we use ON CONFLICT DO UPDATE, re-uploading the file will also fix it.

-- Ensure the summary columns exist (they should already)
ALTER TABLE activities ADD COLUMN IF NOT EXISTS avg_heart_rate INTEGER;
ALTER TABLE activities ADD COLUMN IF NOT EXISTS max_heart_rate INTEGER;
ALTER TABLE activities ADD COLUMN IF NOT EXISTS avg_cadence INTEGER;
ALTER TABLE activities ADD COLUMN IF NOT EXISTS total_calories INTEGER;
