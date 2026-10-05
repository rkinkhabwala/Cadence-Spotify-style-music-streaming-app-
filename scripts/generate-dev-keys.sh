#!/usr/bin/env bash
# Generates the local RS256 key pair used to sign JWTs. secrets/ is git-ignored; never commit these files.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p secrets
if [[ -f secrets/jwt-private.pem && "${1:-}" != "--force" ]]; then
  echo "secrets/jwt-private.pem exists (use --force to replace it; existing tokens become invalid)"
  exit 0
fi
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem 2>/dev/null
openssl pkey -in secrets/jwt-private.pem -pubout -out secrets/jwt-public.pem
chmod 600 secrets/jwt-private.pem
echo "Wrote secrets/jwt-private.pem (PKCS#8) and secrets/jwt-public.pem"
