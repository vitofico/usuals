-- Pairing v2: the owner's descriptor that the invite link anchors, and the applicant's link-key MAC.
ALTER TABLE invitation ADD COLUMN descriptor bytea;
ALTER TABLE join_request ADD COLUMN mac bytea;
