ALTER TABLE users ADD COLUMN strava_athlete_id BIGINT UNIQUE;
ALTER TABLE users ADD COLUMN strava_access_token TEXT;
ALTER TABLE users ADD COLUMN strava_refresh_token TEXT;
ALTER TABLE users ADD COLUMN strava_token_expires_at TIMESTAMP WITH TIME ZONE;

-- We can drop terra_user_id since we won't use it
ALTER TABLE users DROP COLUMN terra_user_id;
