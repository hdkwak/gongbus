-- 1. Add strava_id column
ALTER TABLE activities ADD COLUMN IF NOT EXISTS strava_id BIGINT UNIQUE;

-- 2. Remove duplicates by (user_id, start_time)
-- We keep the one with the latest 'id' (likely has more data/streams)
DELETE FROM activities
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY user_id, start_time ORDER BY id DESC) as row_num
        FROM activities
    ) t
    WHERE t.row_num > 1
);

-- 3. Add the missing UNIQUE constraint that was used in code but didn't exist in DB
ALTER TABLE activities ADD CONSTRAINT unique_user_start_time UNIQUE (user_id, start_time);
