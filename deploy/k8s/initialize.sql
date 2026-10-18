\set ON_ERROR_STOP on
SELECT format('CREATE ROLE usuals LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', trim(pg_read_file('/run/secrets/app-password'))) \gexec
SELECT format('CREATE ROLE usuals_backup LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', trim(pg_read_file('/run/secrets/backup-password'))) \gexec
CREATE DATABASE usuals OWNER usuals;
