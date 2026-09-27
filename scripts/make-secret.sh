#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -e .local-secret ]]; then
  echo 'Secret already exists in .local-secret'
  exit 0
fi
umask 077
python3 - <<'PY' > .local-secret
import secrets
print(secrets.token_hex(32))
PY
echo 'Generated .local-secret'
