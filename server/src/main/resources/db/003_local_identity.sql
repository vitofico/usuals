ALTER TABLE web_user ADD COLUMN local_admin boolean NOT NULL DEFAULT false;
ALTER TABLE web_user ADD COLUMN local_username text;
CREATE UNIQUE INDEX web_user_local_username ON web_user(local_username) WHERE local_username IS NOT NULL;
CREATE TABLE local_credential (
    user_id uuid PRIMARY KEY REFERENCES web_user(id),
    password_hash bytea,
    salt bytea,
    version integer NOT NULL DEFAULT 1 CHECK(version=1),
    setup_hash bytea,
    setup_expires bigint,
    CHECK ((password_hash IS NULL AND salt IS NULL) OR (octet_length(password_hash)=32 AND octet_length(salt)=32)),
    CHECK ((setup_hash IS NULL AND setup_expires IS NULL) OR (octet_length(setup_hash)=32 AND setup_expires IS NOT NULL))
);
CREATE TABLE local_login (
    browser_hash bytea PRIMARY KEY,
    csrf_hash bytea NOT NULL,
    expires bigint NOT NULL
);
