#!/bin/sh
set -eu
umask 077
password=${CT_REDIS_PASSWORD:-}
if [ -n "${CT_REDIS_PASSWORD_FILE:-}" ]; then password=$(cat "$CT_REDIS_PASSWORD_FILE"); fi
test -n "$password" || { echo "Missing Redis credential" >&2; exit 2; }
case "$password" in *'
'*) echo "Redis credentials must be one line" >&2; exit 2;; esac
escaped=$(printf '%s' "$password" | sed 's/\\/\\\\/g; s/"/\\"/g')
role=${CT_REDIS_ROLE:?}
bind=${CT_REDIS_BIND:-127.0.0.1}
conf=/data/ct-redis.conf
if [ "$role" = sentinel ]; then conf=/data/ct-sentinel.conf; fi
# Sentinel rewrites its configuration and the data nodes' replication settings at promotion.
# Start from the saved role after process or host restart.
if [ ! -s "$conf" ]; then
    tmp="$conf.tmp.$$"
    case "$role" in
    cache)
        cat >"$tmp" <<EOF
bind $bind
protected-mode yes
port ${CT_REDIS_PORT:-6379}
requirepass "$escaped"
dir /data
save ""
appendonly no
maxmemory ${CT_REDIS_CACHE_MEMORY:-96mb}
maxmemory-policy allkeys-lru
EOF
        ;;
    primary|replica)
        cat >"$tmp" <<EOF
bind $bind
protected-mode yes
port ${CT_REDIS_PORT:-6379}
requirepass "$escaped"
masterauth "$escaped"
dir /data
save ""
appendonly yes
appendfsync everysec
maxmemory-policy noeviction
replica-announce-ip ${CT_REDIS_ANNOUNCE:?}
replica-announce-port ${CT_REDIS_PORT:-6379}
EOF
        if [ "$role" = replica ]; then printf 'replicaof %s %s\n' "${CT_REDIS_INITIAL_MASTER:?}" "${CT_REDIS_MASTER_PORT:-6379}" >>"$tmp"; fi
        ;;
    sentinel)
        cat >"$tmp" <<EOF
bind $bind
protected-mode yes
port ${CT_SENTINEL_PORT:-26379}
requirepass "$escaped"
dir /data
sentinel resolve-hostnames yes
sentinel announce-hostnames yes
sentinel announce-ip ${CT_REDIS_ANNOUNCE:?}
sentinel announce-port ${CT_SENTINEL_PORT:-26379}
sentinel monitor ct-waiting ${CT_REDIS_INITIAL_MASTER:?} ${CT_REDIS_MASTER_PORT:-6379} 2
sentinel auth-pass ct-waiting "$escaped"
sentinel down-after-milliseconds ct-waiting ${CT_SENTINEL_DOWN_MS:-5000}
sentinel failover-timeout ct-waiting ${CT_SENTINEL_FAILOVER_MS:-30000}
sentinel parallel-syncs ct-waiting 1
EOF
        ;;
    *) echo "Unknown Redis role" >&2; exit 2;;
    esac
    mv "$tmp" "$conf"
fi
if [ "$role" = sentinel ]; then exec redis-server "$conf" --sentinel; fi
exec redis-server "$conf"
