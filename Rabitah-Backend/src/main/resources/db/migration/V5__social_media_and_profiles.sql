ALTER TABLE users ADD COLUMN profile_photo_key varchar(500);

CREATE INDEX idx_post_media_post ON post_media(post_id, created_at);
