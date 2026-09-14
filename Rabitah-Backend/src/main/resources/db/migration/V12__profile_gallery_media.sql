CREATE TABLE profile_gallery_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    storage_key varchar(500) NOT NULL,
    original_name varchar(255) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_profile_gallery_media_user
    ON profile_gallery_media(user_id, created_at DESC);
