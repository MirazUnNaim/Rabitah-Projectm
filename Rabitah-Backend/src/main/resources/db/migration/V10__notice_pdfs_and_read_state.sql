CREATE TABLE notice_reads(
    notice_id uuid NOT NULL REFERENCES notices(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(notice_id, user_id)
);

CREATE INDEX idx_notice_reads_user ON notice_reads(user_id, notice_id);

-- Notices published before this feature are history, not new alerts for every account.
INSERT INTO notice_reads(notice_id, user_id, read_at)
SELECT n.id, u.id, n.published_at
FROM notices n
CROSS JOIN users u
WHERE n.deleted_at IS NULL
ON CONFLICT DO NOTHING;
