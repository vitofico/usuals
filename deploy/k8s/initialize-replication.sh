#!/bin/sh
set -eu
printf '%s\n' 'host replication comanda_backup 0.0.0.0/0 scram-sha-256' 'host replication comanda_backup ::/0 scram-sha-256' >> "$PGDATA/pg_hba.conf"
