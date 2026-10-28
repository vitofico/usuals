-- Recent notification metadata uses the managed list key, with a separate cipher context.
ALTER TABLE list ADD COLUMN activity bytea;
