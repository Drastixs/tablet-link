# Tablet Link

Personal Android tablet companion for Linux. The Android app connects to a saved Tailscale IP and becomes an extra Linux display, while presenting the tablet speaker and microphone as PipeWire devices. Taps, drags, and two-finger scrolling are sent to Linux.

## The extra display

The installed Niri 26.04 session does not expose virtual-output creation, and this Debian kernel has `CONFIG_DRM_VKMS` disabled. `xrandr` cannot add a monitor to Wayland. This machine has spare DRM connectors, so `scripts/setup-display.sh` forces an unused one on and persists that setting across boots. This is verified on this machine with `HDMI-A-1`. A hardware dummy display adapter is an alternative on machines where forcing a connector does not work. The companion exits with an actionable error if the output is absent.

While a tablet is connected the companion turns the output on, sets it to the tablet's shape (`--mode`, default `2560x1600@60`, with `--scale 2`), and places it to the left of the largest monitor (`--side right` for the other side). It is turned off again on disconnect. Until the companion has started once after login, the forced output is on at 1024x768 with nothing showing it.

## Build and run

No third-party libraries are needed. The Android build uses the installed Android SDK build tools directly. The Linux companion needs Python 3, `wf-recorder` with VAAPI H.264 encoding, `pactl`, write access to `/dev/uinput`, and an active PipeWire Pulse server.

1. Run `./scripts/make-secret.sh` once. This generates a private 256-bit secret in `.local-secret`.
2. Run `./android/build.sh`. The signed APK is `android/build/tablet-link.apk`; install it with `adb install -r android/build/tablet-link.apk` or copy it to the tablet. The APK contains the secret, so keep it private.
3. To create the extra output, run `sudo ./scripts/setup-display.sh HDMI-A-1`, then check `niri msg outputs`. If it remains absent, log out and back in. The script can be undone with `sudo systemctl disable --now tablet-link-display.service`.
4. Connect both devices to Tailscale. Check the desktop's IP with `tailscale ip -4`. Start the companion with `python3 linux/tablet_link.py --output HDMI-A-1`. The server binds only to the desktop's Tailscale IPv4 address, on TCP and UDP port 39471. To start it automatically at login, run `./scripts/install-user-service.sh HDMI-A-1` after a successful manual connection.
5. Open the Android app, enter the desktop's Tailscale IP once, then save. Future launches connect automatically.

Audio devices appear as **Tablet_Link_Speaker** and **Tablet_Link_Microphone**. They become the Linux defaults while connected, so new calls use them automatically; the previous defaults are restored on disconnect. Applications that pin a specific device may still need that device changed in their settings. On disconnect, the companion turns off the tablet output and unloads both PipeWire devices.

## Protocol and limits

The TCP connection uses a random challenge and HMAC-SHA256 with the shared secret, and carries audio, touch and status. Tailscale provides transport encryption. Audio is mono 48 kHz 16-bit PCM. Only one tablet can connect at a time. The app disconnects when it leaves the foreground, on Back, or from the Disconnect button, which shows for a few seconds after connecting and on a three-finger touch.

Video is hardware-encoded H.264 at 30 frames per second (`--fps`, `--bitrate` in kbit/s, default 3000), sent over UDP as numbered parts of each picture. The tablet decodes with its hardware decoder. It asks again for parts that go missing; if they still do not arrive it holds the last picture until the next keyframe, at most a second later. When the path cannot keep up the desktop drops pictures rather than falling behind. A static screen uses about 200 kbit/s.

The stream holds 30 frames per second over a Tailscale relay when the network is healthy, but delay follows the path: a direct connection or the same network is noticeably more responsive than a relay. On a poor link, lower `--bitrate`, or use a smaller `--mode` such as `1920x1200@60` with `--scale 1.5`. Run `adb logcat -s TabletLink` to see frame rate, losses and bitrate as the tablet measures them.

The Android app targets API 29 or newer and requests microphone permission on first use. The app does not need screen-capture permission because Linux sends the display image to it.
