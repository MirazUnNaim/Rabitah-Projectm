CREATE TABLE password_reset_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_password_hash varchar(100) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    requested_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by uuid REFERENCES users(id),
    reviewed_at timestamptz,
    rejection_reason varchar(500),
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED'))
);

CREATE UNIQUE INDEX uq_password_reset_request_pending
    ON password_reset_requests(user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_password_reset_requests_pending
    ON password_reset_requests(status, requested_at);

CREATE TABLE login_branding (
    id smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    storage_key varchar(500) NOT NULL,
    original_name varchar(255) NOT NULL,
    content_type varchar(100) NOT NULL,
    updated_by uuid NOT NULL REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now()
);
