CREATE TABLE web_created_household (
    household uuid PRIMARY KEY REFERENCES household(id),
    user_id uuid NOT NULL REFERENCES web_user(id)
);
CREATE INDEX web_created_household_user ON web_created_household(user_id);
