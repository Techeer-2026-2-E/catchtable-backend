#!/bin/sh
# Optional Java 21 launch profile for the measured Linux cgroup deployment.
# Cache and waiting dispatch URIs, credentials and feature activation remain runtime configuration.
set -eu
ct_jar=${1:-/app.jar}
if [ "$#" -gt 0 ]; then shift; fi
ct_cpu=${CT_APP_CPU:-1}
ct_heap=${CT_APP_HEAP_MIB:-256}
ct_code=${CT_APP_CODE_CACHE_MIB:-64}
ct_direct=${CT_APP_DIRECT_MIB:-32}
for ct_value in "$ct_cpu" "$ct_heap" "$ct_code" "$ct_direct"; do
    case "$ct_value" in ''|*[!0-9]*) echo 'Invalid Java resource setting' >&2; exit 2;; esac
    [ "$ct_value" -gt 0 ] || exit 2
done
export MALLOC_ARENA_MAX=${MALLOC_ARENA_MAX:-2}
export WAITING_INTAKE_BATCH_ENABLED=${WAITING_INTAKE_BATCH_ENABLED:-true}
export WAITING_INTAKE_ASYNC_ENABLED=${WAITING_INTAKE_ASYNC_ENABLED:-true}
export SERVER_TOMCAT_THREADS_MAX=${SERVER_TOMCAT_THREADS_MAX:-40}
export SERVER_TOMCAT_MAXCONNECTIONS=${SERVER_TOMCAT_MAXCONNECTIONS:-4000}
export SERVER_TOMCAT_ACCEPTCOUNT=${SERVER_TOMCAT_ACCEPTCOUNT:-1365}
export SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=${SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE:-4}
exec java "-XX:ActiveProcessorCount=$ct_cpu" -XX:+UseG1GC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=2 \
    "-XX:ReservedCodeCacheSize=${ct_code}m" "-XX:MaxDirectMemorySize=${ct_direct}m" -Xss512k \
    "-Xms${ct_heap}m" "-Xmx${ct_heap}m" -jar "$ct_jar" "$@"
