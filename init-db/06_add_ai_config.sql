ALTER TABLE users ADD COLUMN ai_provider TEXT DEFAULT 'openai';
ALTER TABLE users ADD COLUMN ai_api_key TEXT;

-- Create chat history table
CREATE TABLE IF NOT EXISTS activity_chats (
    id SERIAL PRIMARY KEY,
    activity_id INTEGER NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL, -- 'user' or 'assistant'
    message TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
