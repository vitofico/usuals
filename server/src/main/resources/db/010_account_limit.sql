-- Generic operator policy, keyed by the existing account rather than a commercial grouping.
CREATE TABLE account_limit (
    user_id uuid PRIMARY KEY REFERENCES web_user(id),
    max_owned_lists integer NOT NULL CHECK (max_owned_lists BETWEEN 0 AND 500),
    max_list_members integer NOT NULL CHECK (max_list_members BETWEEN 1 AND 50),
    fallback_owned_lists integer NOT NULL CHECK (fallback_owned_lists BETWEEN 0 AND 500),
    fallback_list_members integer NOT NULL CHECK (fallback_list_members BETWEEN 1 AND 50),
    expires_at bigint NOT NULL
);
