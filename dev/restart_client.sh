#!/bin/bash
# Restart the dev client into the test world (mod/run/saves/mcmcp-test2) and wait until the mod listens.
# Windows + Git Bash only (uses PowerShell to stop whatever listens on port 25580).
powershell -NoProfile -Command '$c = Get-NetTCPConnection -LocalPort 25580 -State Listen -ErrorAction SilentlyContinue; if ($c) { Stop-Process -Id $c.OwningProcess -Force }'
sleep 3
cd "$(dirname "$0")/../mod" && rm -f run/saves/mcmcp-test2/session.lock
L="$(cygpath -u "$TEMP")/runClient.log"
(./gradlew --no-daemon runClient --args="--quickPlaySingleplayer mcmcp-test2" > "$L" 2>&1 &)
for i in $(seq 1 400); do grep -qE 'MC-MCP listening|BUILD FAILED|crash-reports|error:' "$L" 2>/dev/null && break; sleep 1; done
grep -E 'MC-MCP listening|BUILD FAILED|crash-reports|error:' "$L" | head -3
sleep 8
