# Tablet Link

Personal Android tablet companion for Linux. The Android app connects to a saved Tailscale IP and streams one Linux output, while presenting the tablet speaker and microphone as PipeWire devices. Taps, drags, and two-finger scrolling are sent to Linux.

## Current hardware limitation

The installed Niri 26.04 session does not expose virtual-output creation, and this Debian kernel has `CONFIG_DRM_VKMS` disabled. `xrandr` cannot add a monitor to Wayland. This machine has spare DRM connectors, so `scripts/setup-display.sh` can force an unused one on and persist that setting across boots. This path requires an administrator step and **has not yet been verified on this machine**, because sudo needs the owner's password. A hardware dummy display adapter is an alternative if forcing a connector does not work. The companion exits with an actionable error if the output is absent.

## Build and run

No third-party libraries are needed. The Android build uses the installed Android SDK build tools directly. The Linux companion needs Python 3, `grim`, `pactl`, `ydotool` and `ydotoold`, and an active PipeWire Pulse server.

1. Run `./scripts/make-secret.sh` once. This generates a private 256-bit secret in `.local-secret`.
2. Run `./android/build.sh`. The signed APK is `android/build/tablet-link.apk`; install it with `adb install -r android/build/tablet-link.apk` or copy it to the tablet. The APK contains the secret, so keep it private.
3. To create the extra output, run `sudo ./scripts/setup-display.sh HDMI-A-1`, then check `niri msg outputs`. If it remains absent, log out and back in. The script can be undone with `sudo systemctl disable --now tablet-link-display.service`.
4. Connect both devices to Tailscale. Check the desktop's IP with `tailscale ip -4`. Start the companion with `python3 linux/tablet_link.py --output HDMI-A-1`. The server binds only to the desktop's Tailscale IPv4 address. To start it automatically at login, run `./scripts/install-user-service.sh HDMI-A-1` after a successful manual connection.
5. Open the Android app, enter the desktop's Tailscale IP once, then save. Future launches connect automatically.

Audio devices appear as **Tablet_Link_Speaker** and **Tablet_Link_Microphone**. They become the Linux defaults while connected, so new calls use them automatically; the previous defaults are restored on disconnect. Applications that pin a specific device may still need that device changed in their settings. On disconnect, the companion unloads both PipeWire devices.

## Protocol and limits

The single TCP connection uses a random challenge and HMAC-SHA256 with the shared secret. Tailscale provides transport encryption. Video uses JPEG frames captured with `grim` at up to 10 fps. Audio is mono 48 kHz 16-bit PCM. This is a working first implementation, not a low-latency video codec; high-motion content and slow tailnets may feel sluggish. Only one tablet can connect at a time. The app disconnects when it leaves the foreground.

The Android app targets API 29 or newer and requests microphone permission on first use. The app does not need screen-capture permission because Linux sends the display image to it.
