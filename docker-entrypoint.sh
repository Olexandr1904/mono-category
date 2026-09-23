#!/bin/sh
# Starts as root only long enough to hand the data volume to an unprivileged user,
# then drops to it for the life of the process.
#
# The chown has to happen here rather than in the Dockerfile: Fly mounts the volume
# over /data at container start, so anything the image did to that path is hidden by
# the mount. Without this step the application would either run as root (so a remote
# code execution would own the container) or run unprivileged and be unable to write
# its own database.
set -e

DATA_DIR="$(dirname "${DB_PATH:-/data/budget.db}")"
mkdir -p "$DATA_DIR"
chown -R app:app "$DATA_DIR"

exec setpriv --reuid=app --regid=app --init-groups \
    java $JAVA_OPTS -jar /app/app.jar
