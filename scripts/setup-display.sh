#!/usr/bin/env bash
# Force an unused DRM connector to appear connected to Niri.
set -euo pipefail
CONNECTOR="${1:-HDMI-A-1}"
if [[ ! "$CONNECTOR" =~ ^(HDMI-A|DP)-[0-9]+$ ]]; then
  echo 'Use a connector name such as HDMI-A-1 or DP-1' >&2
  exit 1
fi
if [[ "$EUID" -ne 0 ]]; then
  echo "Run: sudo $0 $CONNECTOR" >&2
  exit 1
fi
MATCHES=(/sys/class/drm/card*-$CONNECTOR)
if [[ ! -e "${MATCHES[0]}/status" ]]; then
  echo "Connector $CONNECTOR was not found in /sys/class/drm" >&2
  exit 1
fi
if [[ "$(cat "${MATCHES[0]}/status")" == connected ]]; then
  echo "$CONNECTOR is already connected to a physical display; refusing to force it" >&2
  exit 1
fi
CARD="${MATCHES[0]##*/}"
CARD="${CARD%%-*}"
UNIT=/etc/systemd/system/tablet-link-display.service
cat > "$UNIT" <<EOF
[Unit]
Description=Force spare DRM connector for Tablet Link
Before=display-manager.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c 'printf on > /sys/class/drm/$CARD-$CONNECTOR/status; printf change > /sys/class/drm/$CARD/uevent'
ExecStop=/bin/sh -c 'printf detect > /sys/class/drm/$CARD-$CONNECTOR/status; printf change > /sys/class/drm/$CARD/uevent'

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now tablet-link-display.service
echo "Forced $CONNECTOR on and enabled it at boot."
echo "Check 'niri msg outputs' in your desktop session. If it is absent, log out and back in."
echo "To undo: sudo systemctl disable --now tablet-link-display.service"
