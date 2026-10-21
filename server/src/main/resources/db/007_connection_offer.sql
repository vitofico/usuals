-- A signed-in browser's one-scan phone connection. The QR carries the secret; the relay keeps its hash
-- and, once a phone claims it, the ticket that this account may approve.
CREATE TABLE connection_offer (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES web_user(id),
    secret_hash bytea NOT NULL,
    expires bigint NOT NULL,
    ticket uuid UNIQUE
);
CREATE INDEX connection_offer_user ON connection_offer(user_id);
