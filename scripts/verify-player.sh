#!/usr/bin/env bash
# Browser check of spec 9 Phase 1 AC4: the dev hls.js page plays and seeks a READY track in headless Chrome.
# Needs the API with the dev profile (make app / make run-api), at least one READY track (make seed) and Chrome.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
API="${CADENCE_API_URL:-http://localhost:${CADENCE_API_PORT:-8080}}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
json() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

TOKEN=$(curl -fsS -XPOST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$CADENCE_ADMIN_EMAIL\",\"password\":\"$CADENCE_ADMIN_PASSWORD\"}" | json '["accessToken"]')
TRACK="${1:-$(curl -fsS "$API/api/v1/admin/tracks?status=READY&limit=1" -H "Authorization: Bearer $TOKEN" | json '["items"][0]["id"]')}"
PROFILE=$(mktemp -d)
PORT=9333
"$CHROME" --headless=new --disable-gpu --mute-audio --no-first-run --user-data-dir="$PROFILE" \
  --autoplay-policy=no-user-gesture-required --remote-debugging-port=$PORT \
  "$API/dev/player.html#autotest&track=$TRACK&token=$TOKEN" >/dev/null 2>&1 &
CHROME_PID=$!
trap 'kill $CHROME_PID 2>/dev/null; wait $CHROME_PID 2>/dev/null; rm -rf "$PROFILE"' EXIT

# real-time playback: poll the page title through the DevTools endpoint (the page reports within 25 s)
TITLE="no result"
for _ in $(seq 1 60); do
  sleep 0.5
  TITLE=$(curl -fsS "http://127.0.0.1:$PORT/json" 2>/dev/null | python3 -c \
    'import sys,json; print(next((t["title"] for t in json.load(sys.stdin) if t.get("type")=="page"), ""))' 2>/dev/null || true)
  [[ "$TITLE" == AUTOTEST* ]] && break
done
echo "track $TRACK: $TITLE"
[[ "$TITLE" == AUTOTEST\ PASS* ]]
