ALTER TABLE account_limit ADD COLUMN monthly_ai_requests integer CHECK (monthly_ai_requests BETWEEN 0 AND 10000);
ALTER TABLE account_limit ADD COLUMN fallback_monthly_ai_requests integer CHECK (fallback_monthly_ai_requests BETWEEN 0 AND 10000);
CREATE TABLE ai_usage_monthly (
    user_id uuid PRIMARY KEY REFERENCES web_user(id),
    month text NOT NULL,
    requests integer NOT NULL CHECK (requests >= 0)
);
