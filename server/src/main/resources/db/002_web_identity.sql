CREATE TABLE web_user (
    id uuid PRIMARY KEY,
    issuer text NOT NULL,
    subject text NOT NULL,
    display_name text NOT NULL,
    email text,
    disabled boolean NOT NULL DEFAULT false,
    created_at bigint NOT NULL,
    last_login_at bigint NOT NULL,
    UNIQUE(issuer,subject)
);
CREATE TABLE web_login (
    state_hash bytea PRIMARY KEY,
    browser_hash bytea NOT NULL,
    nonce_hash bytea NOT NULL,
    verifier text NOT NULL,
    expires bigint NOT NULL
);
CREATE TABLE web_session (
    id uuid PRIMARY KEY,
    token_hash bytea NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES web_user(id),
    csrf_token text NOT NULL,
    created_at bigint NOT NULL,
    expires bigint NOT NULL,
    revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX web_session_user ON web_session(user_id);
CREATE TABLE web_device (
    device uuid PRIMARY KEY REFERENCES principal(id),
    user_id uuid NOT NULL REFERENCES web_user(id)
);
CREATE INDEX web_device_user ON web_device(user_id);
CREATE TABLE web_device_session (
    auth_hash bytea PRIMARY KEY REFERENCES auth_session(hash) ON DELETE CASCADE,
    web_session_id uuid NOT NULL REFERENCES web_session(id) ON DELETE CASCADE
);
