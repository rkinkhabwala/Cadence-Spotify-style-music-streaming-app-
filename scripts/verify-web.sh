#!/usr/bin/env bash
# Browser check of spec 9 Phase 2 AC5 (and AC1 in the UI): the real web client keeps playing across navigation.
# Needs the stack with seeded data (make up, make app or run-api/run-transcoder, make seed), the web client on
# WEB_URL (default http://localhost:5173: `make web`; use http://localhost:3000 for the container) and Google Chrome.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
[ -d cadence-web/node_modules ] || (cd cadence-web && npm install --no-audit --no-fund)
cd cadence-web && node scripts/verify-browser.mjs
