\set ON_ERROR_STOP on
SELECT format('CREATE ROLE comanda LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', trim(pg_read_file('/run/secrets/app-password'))) \gexec
SELECT format('CREATE ROLE comanda_backup LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', trim(pg_read_file('/run/secrets/backup-password'))) \gexec
CREATE DATABASE comanda OWNER comanda;
