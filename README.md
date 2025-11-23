# Jellyfin Jellyseerr TV Integration

Client-side integration that brings Jellyseerr discovery and requests into Jellyfin for Android TV.

## Prerequisite: Server Plugin (Required)
- Install and configure **Jellyfin Requests Bridge** on your Jellyfin server first, otherwise the Jellyseerr discovery/request features will not work.
- Plugin repo: https://github.com/Serekay/jellyfin-requests-bridge

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
2. Go to the releases: https://github.com/Serekay/jellyfin-jellyserr-tv/releases
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

## Optional bonus information: Remote Access with Tailscale (VPN)

Use Tailscale to reach your Jellyfin server without opening ports.
- If your Jellyfin server runs in Docker/Unraid, make sure Tailscale is already set up in your stack (see https://tailscale.com/kb/1352/servers and read the documentation).

### Desktop/Laptop without sharing credentials
1. Install Tailscale.
2. In Terminal/CMD: `tailscale up`.
3. Send the login link shown to the Tailscale admin; the admin authorizes the device.

### Android TV setup
1. Install Tailscale on the TV  
   - Play Store → search “Tailscale” → install.
2. Start Tailscale and show the code  
   - Open the app → tap “Log in” → note the 6-digit code.
3. Authorize from a PC/phone  
   - Go to https://login.tailscale.com/admin/machines → “Add device” → enter the code.
4. Sign in with your Tailscale account (Google / Microsoft / GitHub).
5. Verify connection  
   - When Tailscale shows “Connected”, you are good to go.
6. In the Jellyfin app, add the server using the Tailscale IP (e.g., `http://100.x.x.x:8096`) and sign in.

### Always-On via ADB (keeps VPN alive after reboot, other apps stay local)
1. Install “ADB TV”/“ADB Shell” on the TV.
2. Enable developer options (Settings → Device → About → tap “Build number” 7×), then enable USB debugging.
3. In ADB TV run, one by one:
   ```bash
   settings put secure always_on_vpn_app com.tailscale.ipn
   settings put secure always_on_vpn_lockdown 0
   ```
4. Restart the TV. Tailscale connects automatically; Netflix/YouTube remain outside the VPN (lockdown=0).
   - Why only Jellyfin goes through the VPN: with `always_on_vpn_lockdown` set to `0`, Android allows non-VPN traffic. Your Jellyfin app uses the Tailscale IP of the server (100.x.x.x), so that traffic is routed via the VPN interface. Other apps keep using the normal LAN/WAN route, so your TV’s general traffic is not tunneled just the Jellyfin App.

---

## Tips
- Keep the server plugin updated.
- “App not installed” is often low storage; free space and retry.
- “Unknown sources” may need to be enabled per app/source on some TVs.
