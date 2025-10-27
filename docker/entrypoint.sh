#!/usr/bin/env sh
set -eu

mkdir -p /data/raw_users /data/processed_users /data/dlq /data/state || true
chown -R app:app /data || true

[ -d /var/sqlite ] && chown -R app:app /var/sqlite || true

exec su-exec app:app sh -lc "java $JAVA_OPTS -jar /app/app.jar"
