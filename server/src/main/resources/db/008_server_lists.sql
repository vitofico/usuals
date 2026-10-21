-- Server-held lists (ADR 0017). The household relay stored end-to-end encrypted operations this server
-- cannot read or convert, so its tables go and each owner uploads their lists again.
DROP TABLE web_device_session, web_device, web_created_household, key_package, published_snapshot, snapshot_candidate,
    operation, shared_list, join_request, invitation, auth_session, auth_challenge, manifest, membership, household,
    principal, bootstrap_token;
-- projection is the encrypted list; NULL marks a deleted list, whose id is never reused.
CREATE TABLE list (
    id uuid PRIMARY KEY,
    owner uuid NOT NULL REFERENCES web_user(id),
    projection bytea,
    seq bigint NOT NULL,
    created_at bigint NOT NULL,
    deleted_at bigint,
    -- The owner archived the list for every member; only the owner restores it.
    archived boolean NOT NULL DEFAULT false,
    CHECK ((deleted_at IS NULL) = (projection IS NOT NULL))
);
CREATE INDEX list_owner ON list(owner) WHERE deleted_at IS NULL;
-- removed is the change seq at which the account lost the list, NULL while it is a member.
CREATE TABLE list_member (
    list_id uuid NOT NULL REFERENCES list(id),
    user_id uuid NOT NULL REFERENCES web_user(id),
    role text NOT NULL CHECK (role IN ('owner','editor')),
    removed bigint,
    -- An editor's own choice to hide the list from their main lists; other members are unaffected.
    archived boolean NOT NULL DEFAULT false,
    PRIMARY KEY (list_id, user_id)
);
CREATE INDEX list_member_user ON list_member(user_id);
-- One row orders every change: a transaction holds its lock from taking a seq until it commits, so a reader
-- never sees a higher seq before a lower one. A sequence hands out numbers without that ordering.
CREATE TABLE list_counter (singleton integer PRIMARY KEY CHECK (singleton = 1), seq bigint NOT NULL);
INSERT INTO list_counter VALUES (1, 0);
-- A claimed offer now approves its phone at once, so it lives only as long as the browser session that made it.
DELETE FROM connection_offer;
ALTER TABLE connection_offer ADD COLUMN session uuid NOT NULL REFERENCES web_session(id) ON DELETE CASCADE;
