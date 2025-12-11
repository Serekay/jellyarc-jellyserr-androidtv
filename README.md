# Jellyarc - Jellyfin Android TV Client

Android TV client for Jellyfin with built-in Jellyseerr integration and Tailscale VPN support.

## What is Jellyarc?

Jellyarc lets you browse the Jellyseerr discovery catalog and request new content directly from your TV. It also includes a built-in Tailscale VPN client for secure remote access without opening ports.

---

## Requirements

- Android TV 11 or newer (Anrdoird TV < 11 maybe alsow work)
- Jellyfin server with [Requests Bridge Plugin](https://github.com/Serekay/jellyfin-requests-bridge) installed and configured.
- Optional: Tailscale account for remote access

---

## Installation

### On your TV (easiest)

1. Install a browser on your TV (e.g. **BrowseHere** or **Downloader** from Play Store)
2. Open the browser and go to: **https://serekay.github.io/jellyarc**
3. The APK download starts automatically
4. Open the downloaded APK and install
5. If prompted, enable "Unknown sources" for the browser app, maybe you need do open the Donwloader app again.

### From your PC

1. Download the APK from [GitHub Releases](https://github.com/Serekay/jellyarc/releases)
2. Transfer to your TV via:
   - USB stick
   - FTP (e.g. with CX File Explorer on TV)
   - "Send Files to TV" app
3. Open the APK on your TV and install

---

## Setup

1. Start Jellyarc
2. Enter your Jellyfin server address
3. Login with your Jellyfin credentials
4. Done - enjoy Jellyseerr discover & request features

---

## Remote Access with Tailscale

Want to access your server from anywhere? Jellyarc has Tailscale built-in.

1. When adding a server, choose "Connect via Tailscale"
2. The app shows a code - authorize it at [Tailscale Admin](https://login.tailscale.com/admin/machines)
3. Use your server's Tailscale address (e.g. `http://100.x.x.x:8096`)

You can switch existing servers to Tailscale anytime: Settings -> Edit Server

---

## Updates

Jellyarc updates itself automatically. When a new version is available:
1. The app shows an update prompt
2. Allow "Install unknown apps" for Jellyarc (first time only)
3. Confirm the installation

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "App not installed" | Free up storage on your TV and retry |
| "Unknown sources" error | Enable in TV Settings -> Security for each app |
| Tailscale code timeout | Restart the TV and try again |
| Download not starting | Use the manual link on the download page |

---

## Build from Source

Requirements: Java 21, Android SDK

```powershell
# Build debug APK
./gradlew assembleDebug

# Build release APK with version
.\release.ps1 -Version 1.2.3

# Build and create GitHub release
.\release.ps1 -Version 1.2.3 -Changelog "Your changes" -Release
```

The release script builds the APK, copies it to `dist/`, and optionally uploads to GitHub Releases.
