-- A private message has one possible recipient, so a per-message read timestamp is both durable
-- and sufficient to calculate unread counts for either participant in a conversation.
ALTER TABLE private_messages ADD COLUMN IF NOT EXISTS read_at timestamptz;

-- There was no receipt state before this migration. Treat the already-visible history as read so
-- an upgrade does not turn an entire legacy inbox into new-message badges.
UPDATE private_messages SET read_at=sent_at WHERE read_at IS NULL;

-- Supports the people-pane unread badge query and marking a conversation read without scanning
-- messages that have already been acknowledged.
CREATE INDEX IF NOT EXISTS idx_private_messages_unread
    ON private_messages(conversation_id, sender_id, sent_at)
    WHERE read_at IS NULL;
