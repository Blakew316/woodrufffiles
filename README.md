# Woodruff Films

A media hub and file manager built for Android TV and Google TV, made to be driven entirely with a remote.
It plays videos, music and photos from the TV, USB drives and Google Drive.
It's a ground-up rewrite inspired by [AnExplorer](https://github.com/DWorkS/AnExplorer) (Apache 2.0).
None of its code is reused: the original no longer builds, because it depends on jcenter, Fabric and a 2018 Android toolchain.

**See the app without a TV:** [UI gallery](https://blakew316.github.io/woodrufffiles/ui/). Every build redraws
each TV screen and phone-app tab, light and dark (`gallery` job in the build workflow).

## Features

- **Settings** (side menu): Google Drive keys, mail accounts, theme, pop-ups and phone pairing.
  Secrets are stored **encrypted** with a key in the Android Keystore. They persist across restarts and updates,
  and you can type them on the TV or paste them from your phone.
- **Mail**: iCloud Mail, Gmail, Yahoo or any IMAP account, using an app-specific password. There's an inbox and a reader,
  and new mail shows up as a notification.
- **Phone connection**: scan the QR code once (the pairing key persists). On iPhone, tap **Share → Add to Home Screen**
  and it opens like an app that stays paired. It has five tabs:
  - **Files**: upload, download, delete, *Play on TV*, *Background* (photos) and *Slideshow* (folders).
  - **Keyboard**: type on the phone and the text appears in whatever text box is open on the TV (search, rename,
    notes, passwords, Google keys). Enter on the phone presses Done on the TV.
  - **Notes**: the same notes as the TV's Notes screen.
  - **Send to TV**: announcements to the TV and/or chosen phones, links (YouTube and Apple Music links open in those apps), slideshow.
  - **Settings**: name this phone, lock-screen alerts, Google keys and mail accounts.
- **Notes** (side menu): pinned and recent notes, editable from the TV or any paired phone.
- **Announcements to phones**: each phone gets a name ("Mom's iPhone"). From the TV (Notifications → Announce) or a phone,
  pick who gets it. Phones that turned on *Lock-screen alerts* get a real notification through the free
  [ntfy](https://ntfy.sh) app. The others see it whenever Woodruff is open.
- **Screensaver and background**: any photo can be the app background, and a photo slideshow (with a clock) can run from
  Home, after a few idle minutes, or as the TV's own screensaver (Settings → Screensaver & background).
- **Command center**: live Google Home / Nest cameras and doorbells (WebRTC, so the newest models work). Either
  cameras only or "all on one" next to the clock, notifications and notes. Select a camera for full screen with sound,
  Left/Right flips between cameras. It can open automatically when the app starts. Setup is [below](#connect-google-home-cameras).
- **Watch & listen**: Home shortcuts to YouTube, Apple Music and Spotify.

  An iPhone **Shortcut** can POST to `/api/notify?k=…` (the URL is shown on that tab) for automations like "when I get home".
- **Notifications**: messages from the phone and new mail appear as a pop-up, even over other apps once
  "Pop-ups over other apps" is allowed in Settings. They're also kept in the Notifications screen.
- **Typeface**: Inter, the closest open-license match to Apple's San Francisco font, with Apple-style tracking.

- **Google Drive**: stream videos and music (with seeking), view photos, or download files to the TV. Read-only access.
  You sign in on your phone by scanning a QR code; it needs a one-time setup, described [below](#connect-google-drive-one-time-setup).
- **Continue watching**: playback resumes where you stopped, and Home shows what you're partway through.
- **iCloud**: iCloud Mail works through Mail (Apple supports IMAP with an app-specific password).
  iCloud Drive has no supported sign-in for Android apps, so files come from the iPhone through **Phone → Files → Upload → Browse**.

- **Browse everything**: internal storage, SD cards and USB drives. The list refreshes when a drive is plugged in.
- **Library shortcuts**: Videos, Music, Pictures, Documents, APK files, Archives and Downloads, across all drives.
- **File operations**: copy, move, rename, delete, new folder, zip and unzip. Multi-select works with the remote.
  Large copies show progress and can be cancelled. Existing files are never overwritten (the copy is named `name (1).ext` instead).
- **Built-in player and viewer**: videos and music play in a Media3 (ExoPlayer) player, and photos open in a viewer with a slideshow.
  Many TVs ship without either app. "Open with…" still hands files to VLC, Kodi and so on.
- **APK sideloading**: open an `.apk` to install it. If needed, the app walks you through the "install unknown apps" setting.
- **App manager**: launch apps, back up an APK to `Download/TVExplorer/Apps`, open App info, or uninstall.
- **Send files**: start a small web server on the TV, then scan the QR code with your phone or open the URL on a computer.
  You can drag and drop uploads (streamed straight to disk, so they can go to a USB drive), download, create folders and delete.
- **Light or dark mode**: switch it at the bottom of the side menu (Display → Light mode); the choice is remembered.
- **TV-first navigation**: a side rail, large focus targets, and Back always does the expected thing.
  Coming back to a folder puts you on the item you left. Long-press OK or the Menu key opens the action menu.

## Remote controls

| Where | Key | Action |
|---|---|---|
| File list | OK | Open (or toggle, in selection mode) |
| File list | Long-press OK / Menu | Actions: copy, move, rename, zip, details, delete… |
| File list | Back | Leave selection, then go back a folder, then go Home |
| Photo viewer | Left / Right | Previous / next photo |
| Photo viewer | Up / Down | Rotate |
| Photo viewer | OK / Play | Start or stop the slideshow |

## Install on your TV

1. Download **WoodruffFilms.apk** from the latest release:
   https://github.com/Blakew316/woodrufffiles/releases/latest/download/WoodruffFilms.apk
   (The older WoodruffPictures.apk / WoodruffShows.apk links still work.)
   (The older `WoodruffShows.apk` link still works and always gets the same latest build.)
   It's signed with a debug key so it can be sideloaded.
2. Get it onto the TV with any of these:
   - type the link above into the **Downloader** app on the TV (easiest, no computer needed)
   - `adb connect <tv-ip>` then `adb install TVExplorer-release.apk`
   - copy it to a USB drive and open it with the TV's file app
   - use an app like "Send files to TV" or "Downloader"
3. On first launch, grant **All files access**. Some TV builds hide this settings screen. If yours does, the app shows the adb command that grants it:
   `adb shell appops set --uid com.woodruff.shows MANAGE_EXTERNAL_STORAGE allow`

> **Upgrading from TV Explorer 1.0:** uninstall the old TV Explorer app first. 1.0 was signed with a
> throwaway key, so Android won't install over it. From 1.1 on, every build uses the same key, so later
> updates install over the top.

## Connect Google Drive (one-time setup)

You sign in on your **phone** by scanning a QR code on the TV.
Google won't show its sign-in screen on many TVs (it fails with "Error 400: invalid_request").
Google's TV-specific sign-in can't read your existing Drive files, so it's no help here either.
After you sign in, Google sends the phone to a small page on this repo's GitHub Pages site
(`docs/oauth.html`), which passes the one-time approval to the TV over your Wi-Fi.

**1. Turn on GitHub Pages for this repo** (one time):
GitHub → this repo → **Settings → Pages**. Under "Build and deployment", set **Source: Deploy from a branch**,
**Branch: `claude/busy-heisenberg-vk1mse`** and folder **`/docs`**, then **Save**.
After a minute, https://blakew316.github.io/woodrufffiles/oauth.html should open and show "Sign-in problem".
That's expected: it means the page is live.

**2. In Google Cloud** (https://console.cloud.google.com/, signed in as the account whose Drive you want):
1. **APIs & Services → Library**: enable **Google Drive API**.
2. **OAuth consent screen** / "Google Auth Platform": set it to **External**, fill in an app name and your email,
   add your Gmail under **Audience → Test users**, and add the scope `.../auth/drive.readonly` under **Data access**.
3. **Credentials → Create credentials → OAuth client ID**, application type **Web application**:
   - **Authorized redirect URIs → Add URI**: `https://blakew316.github.io/woodrufffiles/oauth.html`
   - Create it, and keep the **Client ID** and **Client secret** handy (open the client again to copy them).
   - If you made an *Android* client earlier, you can delete it. It's no longer used.

**3. On the TV**, open **Google Drive** in Woodruff Films:
1. Scan the first QR code with your phone (on the same Wi-Fi as the TV). Paste the Client ID and Client secret, then tap **Save**.
2. The TV shows a second QR code. Scan it, pick your Google account and allow access.
   Google warns that the app isn't verified: tap **Continue**. That's expected for your own private project.
3. Your phone says "You're signed in", and the TV loads your Drive.

Notes:
- **7-day re-sign-in:** while the consent screen is in "Testing" mode, Google expires the sign-in after 7 days.
  The TV will show the sign-in QR again. To stop that, click **Publish app** on the consent screen.
  An unverified published app shows Google's warning screen and is capped at 100 users, which is fine for a household.
- **Where things are stored:** the client secret and sign-in tokens live only in the app's private storage on the TV.
  The sign-out button on the Drive screen revokes access, or you can revoke it at https://myaccount.google.com/permissions.
- **Fire TV works:** Google Play services is no longer needed, so Google Drive also works on Fire TV and other boxes.

## Connect Google Home cameras

Google only lets apps reach Nest cameras through its Device Access program (one-time US$5 fee, paid to Google).

1. In Google Cloud, in the project you use for Drive, open **APIs & Services → Library** and enable **Smart Device Management API**.
2. Open [console.nest.google.com/device-access](https://console.nest.google.com/device-access), accept the terms and pay the fee.
3. **Create project**: any name. For the OAuth client ID, paste the same Web client ID you use for Drive. Skip events.
4. Copy the **Project ID** into the TV (Command center → Enter Project ID) or the phone (Settings tab → Cameras).
5. On the TV, open **Command center** and scan the QR code. On Google's page, pick your home and switch on every camera and doorbell.

Limits: Google ends each live view after 5 minutes, and the app renews it every 4. Many TVs can decode only a few
videos at once, so the grid streams the first 4 cameras and the rest start when selected.

## Build locally

You need JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

## Security notes

- **Signing key in the repo:** `app/woodruff-shows.keystore` is committed so every build has the same signature (so updates install over each other).
  The catch is that anyone with this repo could sign an APK that Android accepts as an update to this app, so only install builds from this repository's releases.
  Before publishing anywhere, move the key to a GitHub secret.
- Google Drive access is read-only (`drive.readonly`). The OAuth client secret and tokens are kept in the app's private storage.
  The phone sign-in uses PKCE and a one-time `state`, and the relay page only forwards to private network addresses.
  The TV's sign-in server runs only while the Drive connect screen is open.

- The wireless transfer server is **off by default** and only runs while you have it on (a notification shows while it's running).
- Every request needs a random 12-character key. The key is in the QR code and URL shown on the TV, and stopping the server invalidates it.
- The server can only reach the shared storage volumes, never the app's private data.
  Paths are canonicalised, so `..` and symlink tricks are rejected.
- Traffic is plain HTTP on your local network, so anyone on the network who can see the URL can use it.
  Use it on a network you trust.
- The pairing key stays in the phone page's address so a Home Screen copy keeps working. Treat that link like a password.
- Lock-screen alerts go through the public ntfy.sh server. Each phone's topic is random, but anyone who learns it can read
  the announcements sent to that phone, so don't announce secrets.
- Archive extraction rejects "zip slip" entries that would write outside the target folder.

## Project layout

```
app/src/main/java/com/woodruff/tvexplorer/
  cloud/     Google Drive sign-in, API client and screen state
  data/      file listing, storage volumes, file operations, installed apps
  server/    wireless transfer HTTP server + foreground service
  player/    video/music player, photo viewer, Continue-watching history
  ui/        Compose for TV screens, dialogs and the main ViewModel
  util/      intents (open/install/uninstall), permissions, QR, formatting
app/src/main/assets/web/index.html   the browser UI for wireless transfer
```
