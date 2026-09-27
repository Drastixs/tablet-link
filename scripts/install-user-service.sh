#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="${1:-HDMI-A-1}"
if [[ ! "$OUTPUT" =~ ^(HDMI-A|DP)-[0-9]+$ ]]; then
  echo 'Use a connector name such as HDMI-A-1 or DP-1' >&2
  exit 1
fi
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/tablet-link.service" <<EOF
[Unit]
Description=Tablet Link desktop companion
After=graphical-session.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 "$ROOT/linux/tablet_link.py" --output $OUTPUT
Restart=on-failure
RestartSec=5

[Install]
WantedBy=graphical-session.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now tablet-link.service
echo "Installed Tablet Link for login, using $OUTPUT"
