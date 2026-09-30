#!/bin/sh
set -eu

backup() {
    file="/backups/flowforge-$(date -u +%Y%m%d-%H%M%S).dump"
    pg_dump --format=custom --file="$file.partial"
    mv "$file.partial" "$file"
    find /backups -name 'flowforge-*.dump' -mtime "+${BACKUP_KEEP_DAYS:-14}" -delete
    echo "Backup written to $file"
}

if [ "${1:-}" = "now" ]; then
    backup
    exit 0
fi

while true; do
    now=$(date -u +%s)
    next=$(date -u -d "$(date -u +%Y-%m-%d) 02:30:00" +%s)
    if [ "$next" -le "$now" ]; then
        next=$((next + 86400))
    fi
    echo "Next backup at $(date -u -d "@$next")"
    sleep $((next - now))
    backup
done
