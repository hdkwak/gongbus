-- Enable PostGIS extension
CREATE EXTENSION IF NOT EXISTS postgis;

-- Create users table
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    avatar_url TEXT,
    marathon_goal_sec INTEGER,
    weekly_target_km NUMERIC(10, 2),
    monthly_target_km NUMERIC(10, 2),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create activities table
CREATE TABLE IF NOT EXISTS activities (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255),
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    distance_meters INTEGER,
    duration_seconds INTEGER,
    route_line GEOMETRY(LineString, 4326),
    raw_file_url TEXT
);

-- Standard Indexes
CREATE INDEX idx_activities_user_id ON activities(user_id);
CREATE INDEX idx_activities_start_time ON activities(start_time);

-- Spatial index for the route line
CREATE INDEX idx_activities_route_line ON activities USING GIST (route_line);
