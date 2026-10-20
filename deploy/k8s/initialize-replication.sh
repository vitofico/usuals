#!/bin/sh
set -eu
printf '%s\n' 'host replication usuals_backup 0.0.0.0/0 scram-sha-256' 'host replication usuals_backup ::/0 scram-sha-256' >> "$PGDATA/pg_hba.conf"
