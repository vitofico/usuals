CREATE TABLE connection_ticket (
    id uuid PRIMARY KEY,
    signing bytea NOT NULL,
    recipient bytea NOT NULL,
    device uuid NOT NULL,
    verifier_hash bytea NOT NULL,
    poll_hash bytea NOT NULL,
    expires bigint NOT NULL,
    user_id uuid REFERENCES web_user(id)
);
CREATE TABLE native_session (
    hash bytea PRIMARY KEY,
    ticket uuid NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES web_user(id),
    expires bigint NOT NULL,
    revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX native_session_user ON native_session(user_id);
CREATE TABLE ai_preference (
    user_id uuid PRIMARY KEY REFERENCES web_user(id),
    profile text NOT NULL,
    model text,
    credential bytea
);
CREATE TABLE ai_usage (
    user_id uuid PRIMARY KEY REFERENCES web_user(id),
    day bigint NOT NULL,
    attempts integer NOT NULL
);
CREATE TABLE ai_vision_check (
    user_id uuid PRIMARY KEY REFERENCES web_user(id),
    profile_hash text NOT NULL,
    expires bigint NOT NULL
);
