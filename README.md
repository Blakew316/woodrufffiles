# Woodruff Shows

A media hub and file manager built for Android TV and Google TV, made to be driven entirely with a remote.
It plays videos, music and photos from the TV, USB drives and Google Drive.
It's a ground-up rewrite inspired by [AnExplorer](https://github.com/DWorkS/AnExplorer) (Apache 2.0).
None of its code is reused: the original no longer builds, because it depends on jcenter, Fabric and a 2018 Android toolchain.

## Features

- **Google Drive**: stream videos and music (with seeking), view photos, or download files to the TV. Read-only access.
  It needs a one-time setup, described [below](#connect-google-drive-one-time-setup).
- **Continue watching**: playback resumes where you stopped, and Home shows what you're partway through.
- **iCloud**: Apple has no sign-in that Android apps can use. Use **Send files** instead: on an iPhone,
  the upload button picks straight from iCloud Drive and the Photo Library.

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

1. Download **WoodruffShows.apk** from the latest release:
   https://github.com/Blakew316/woodrufffiles/releases/latest/download/WoodruffShows.apk
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

Google only lets an app read your Drive if the app is registered to a Google Cloud project you control.
This is free and takes about 10 minutes:

1. Go to https://console.cloud.google.com/, sign in with the Google account whose Drive you want, and create a project (any name).
2. **APIs & Services → Library**: search for **Google Drive API** and click **Enable**.
3. **APIs & Services → OAuth consent screen** (also called "Google Auth Platform"):
   choose **External**, fill in an app name (e.g. Woodruff Shows) and your email, and save.
   Under **Audience → Test users**, add your Gmail address.
   Under **Data access**, add the scope `.../auth/drive.readonly`.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID** and choose **Android**:
   - Package name: `com.woodruff.shows`
   - SHA-1 certificate fingerprint: `A0:BD:E5:B0:AC:15:50:55:5B:AA:CB:7A:FF:32:1F:30:5E:CE:65:9C`
5. On the TV, open **Google Drive** in Woodruff Shows and choose **Connect Google Drive**.
   Pick your account and accept. Google will warn that the app isn't verified: choose **Continue**.
   This is expected for a personal app in "Testing" mode.

Notes:
- The TV needs Google Play services and a Google account signed in (every Google TV / Android TV with the Play Store has these).
  Fire TV and other devices without Play services can't use Google Drive.
- While the consent screen is in "Testing" mode, Google expires the approval after 7 days, and Drive will ask you to approve again.
  To remove that limit, click **Publish app** on the consent screen. An unverified published app shows Google's warning screen and is capped at 100 users, which is fine for a household.
- To revoke access, go to https://myaccount.google.com/permissions.


## Build locally

You need JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

## Security notes

- **Signing key in the repo:** `app/woodruff-shows.keystore` is committed so every build has the same signature (Google sign-in needs a fixed SHA-1).
  The catch is that anyone with this repo could sign an APK that Android accepts as an update to this app, so only install builds from this repository's releases.
  Before publishing anywhere, move the key to a GitHub secret.
- Google Drive access is read-only (`drive.readonly`). The token is managed by Google Play services and never stored by the app.

- The wireless transfer server is **off by default** and only runs while you have it on (a notification shows while it's running).
- Every request needs a random 12-character key. The key is in the QR code and URL shown on the TV, and stopping the server invalidates it.
- The server can only reach the shared storage volumes, never the app's private data.
  Paths are canonicalised, so `..` and symlink tricks are rejected.
- Traffic is plain HTTP on your local network, so anyone on the network who can see the URL can use it.
  Use it on a network you trust.
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
