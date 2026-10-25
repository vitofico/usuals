ALTER TABLE web_login ADD COLUMN invitation_context bytea;
ALTER TABLE web_login ADD COLUMN fresh boolean NOT NULL DEFAULT false;
ALTER TABLE web_session ADD COLUMN authenticated_at bigint;
ALTER TABLE web_session ADD COLUMN verified_email text;

CREATE TABLE email_invitation (
    id uuid PRIMARY KEY,
    sender uuid NOT NULL REFERENCES web_user(id),
    list_id uuid REFERENCES list(id),
    email text NOT NULL,
    token_hash bytea NOT NULL UNIQUE,
    created_at bigint NOT NULL,
    expires bigint NOT NULL,
    state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','revoked')),
    accepted_by uuid REFERENCES web_user(id),
    last_sent_at bigint NOT NULL,
    locale text NOT NULL,
    mail_cipher bytea,
    delivery text NOT NULL DEFAULT 'queued' CHECK (delivery IN ('queued','sent','failed','cancelled')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt bigint NOT NULL
);
CREATE INDEX email_invitation_sender ON email_invitation(sender);
CREATE INDEX email_invitation_list ON email_invitation(list_id);
CREATE INDEX email_invitation_queue ON email_invitation(next_attempt) WHERE delivery='queued';
CREATE TABLE invitation_context (
    hash bytea PRIMARY KEY,
    csrf text NOT NULL,
    invitation_id uuid REFERENCES email_invitation(id) ON DELETE CASCADE,
    token_hash bytea,
    expires bigint NOT NULL
);
