# Jellyfin Jellyseerr TV Integration with build in Tailscale support

Client-side integration that brings Jellyseerr discovery and requests into Jellyfin for Android TV. You can choose if you want to connect local or with Tailescale VPN

## Download

**[Download the latest APK](https://serekay.github.io/jellyarc/)**

Or download directly from [GitHub Releases](https://github.com/Serekay/jellyarc/releases)

---

Tested on Android Tv > 11

## Prerequisite: Server Plugin (Required)
- Remove already installed app if you have a version < v1.1.2
- Install and configure **Jellyfin Requests Bridge** on your Jellyfin server first, otherwise the Jellyseerr discovery/request features will not work.
- Plugin repo: https://github.com/Serekay/jellyfin-requests-bridge
- Android Tv >= 11
---

## Features

### In-app update (GitHub releases)
- Why: the app downloads APKs for in‑app updates and must be allowed to install them. On Android TV the system will prompt for this permission when the app first attempts an installation — you generally cannot enable it in advance from app settings.
- Permission flow: When the app attempts to install an update, Android may open the "Install unknown apps" settings screen. Enable the "Allow from this source" toggle for the app shown (e.g., the browser or file manager used to download the APK — or the app listed as JellyArc), then return to JellyArc and retry the update. This permission is granted per app and typically only needs to be enabled once.
- Behavior:
   - On startup the app checks for a newer release and shows an update prompt if one is available.
   - You can also trigger a manual check: Settings → Check for updates.
- Installing an update: when prompted, confirm the installation of the downloaded APK.
- Troubleshooting: if you see "App not installed", ensure you accepted the install prompt when it appeared and check available storage; free space if needed and try again. If problems persist, install the APK manually from the releases page.

## Install the Android TV APK (Sideload)

### Option A: Directly on the TV with a browser (e.g., BrowseHere)
1. Open a browser on the TV (e.g., **BrowseHere**).
2. Go to the releases: https://github.com/Serekay/jellyarc/releases
3. Download the latest `app-release.apk`.
4. After download, open the APK and confirm installation (enable “Unknown sources” if prompted).
5. If you see “App not installed”: check free storage on the TV, free up space if needed, and try again.

### Option B: File transfer with CX File Explorer (FTP)
1. On the TV: install CX File Explorer and start its built-in FTP server (note the FTP address).
2. On the PC: open that FTP address in Windows Explorer.
3. Copy `app-release.apk` to a folder on the TV.
4. On the TV: in CX File Explorer, navigate to the copied APK and install.
5. If “App not installed” appears: check the TV’s free storage and retry.

### Option C: Classic transfer (USB / “Send Files to TV” / similar)
1. Put the APK on a USB stick or send it via “Send Files to TV”.
2. Use a file manager on the TV to open and install the APK.
3. Enable “Unknown sources” in the TV security settings if required.

### After installation
1. Start the app.
2. Connect to your Jellyfin server.
3. Enjoy the Jellyseerr discover/request features.

---

## Remote Access with Tailscale (Integrated)

This app includes an integrated Tailscale client to connect to your Jellyfin server securely without opening ports on your router.

### How It Works

- **Initial Setup:** When adding a new server, you can choose to connect via Tailscale. The app will guide you through a one-time authentication process. It will display a code that you must authorize in your Tailscale account's admin dashboard.
- **Switching an Existing Server:** You can switch any existing server between a local connection and a Tailscale connection in the server's settings (`Settings -> Edit Server`). The app will guide you through the same authentication and restart process.

After authentication, the app handles the VPN connection automatically. You only need to provide your server's Tailscale address (e.g., `http://my-jellyfin-server:8096` or `http://100.x.x.x:8096`).

---

## Tips
- Keep the server plugin updated.
- “App not installed” is often low storage; free space and retry.
- “Unknown sources” may need to be enabled per app/source on some TVs.