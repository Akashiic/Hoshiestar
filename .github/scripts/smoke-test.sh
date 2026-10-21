#!/usr/bin/env bash
# Boots a Hoshiestar server jar on a fresh directory, waits for "Done", stops it and checks that it
# saved and exited cleanly. Fails on crash reports or a missing/unclean shutdown.
#
# usage: smoke-test.sh SERVER_JAR [TIMEOUT_SECONDS]
set -euo pipefail

JAR=$(realpath "$1")
TIMEOUT=${2:-600}
DIR=$(mktemp -d)
cd "$DIR"

echo "eula=true" > eula.txt
cat > server.properties <<'EOF'
online-mode=false
server-port=25599
view-distance=4
simulation-distance=4
spawn-protection=0
EOF

mkfifo console
# keep the console pipe open for the whole run
sleep infinity > console &
HOLD=$!
java -Xms1G -Xmx3G -jar "$JAR" nogui < console > server.log 2>&1 &
PID=$!

cleanup() {
  kill "$HOLD" 2>/dev/null || true
  kill -9 "$PID" 2>/dev/null || true
}
trap cleanup EXIT

deadline=$((SECONDS + TIMEOUT))
until grep -q 'Done (' server.log; do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "::error::Server exited before finishing startup"
    tail -n 80 server.log
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "::error::Server did not finish startup within ${TIMEOUT}s"
    tail -n 80 server.log
    exit 1
  fi
  sleep 2
done
grep 'Done (' server.log

sleep 20
echo "stop" > console

deadline=$((SECONDS + 120))
while kill -0 "$PID" 2>/dev/null; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "::error::Server did not exit within 120s after 'stop'"
    tail -n 80 server.log
    exit 1
  fi
  sleep 2
done

status=0
if ls crash-reports/* >/dev/null 2>&1; then
  echo "::error::Crash report written"
  cat crash-reports/*
  status=1
fi
if ! grep -q 'All dimensions are saved' server.log; then
  echo "::error::Worlds were not saved on shutdown"
  status=1
fi
if grep -E 'FATAL|Preparing crash report' server.log; then
  echo "::error::Fatal errors in the log"
  status=1
fi

echo "---- errors and exceptions (informational) ----"
grep -E 'ERROR|Exception' server.log | head -n 40 || true
[ "$status" -eq 0 ] && echo "Smoke test passed"
exit "$status"
