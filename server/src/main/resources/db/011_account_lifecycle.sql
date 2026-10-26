CREATE TABLE account_closure (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE REFERENCES web_user(id),
    delete_owned_lists boolean NOT NULL,
    issuer text,
    subject text,
    created_at bigint NOT NULL,
    closed_at bigint,
    purged_at bigint
);
