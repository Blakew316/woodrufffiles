# TV Explorer

A file manager built for Android TV and Google TV, made to be driven entirely with a remote.
It's a ground-up rewrite inspired by [AnExplorer](https://github.com/DWorkS/AnExplorer) (Apache 2.0).
None of its code is reused: the original no longer builds, because it depends on jcenter, Fabric and a 2018 Android toolchain.

## Features

- **Browse everything**: internal storage, SD cards and USB drives. The list refreshes when a drive is plugged in.
- **Library shortcuts**: Videos, Music, Pictures, Documents, APK files, Archives and Downloads, across all drives.
- **File operations**: copy, move, rename, delete, new folder, zip and unzip. Multi-select works with the remote.
  Large copies show progress and can be cancelled. Existing files are never overwritten (the copy is named `name (1).ext` instead).
- **Built-in player and viewer**: videos and music play in a Media3 (ExoPlayer) player, and photos open in a viewer with a slideshow.
  Many TVs ship without either app. "Open with…" still hands files to VLC, Kodi and so on.
- **APK sideloading**: open an `.apk` to install it. If needed, the app walks you through the "install unknown apps" setting.
- **App manager**: launch apps, back up an APK to `Download/TVExplorer/Apps`, open App info, or uninstall.
- **Wireless transfer**: start a small web server on the TV, then scan the QR code with your phone or open the URL on a computer.
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

1. Download **TVExplorer.apk** from the latest release:
   https://github.com/Blakew316/woodrufffiles/releases/latest/download/TVExplorer.apk
   It's signed with a debug key so it can be sideloaded.
2. Get it onto the TV with any of these:
   - type the link above into the **Downloader** app on the TV (easiest, no computer needed)
   - `adb connect <tv-ip>` then `adb install TVExplorer-release.apk`
   - copy it to a USB drive and open it with the TV's file app
   - use an app like "Send files to TV" or "Downloader"
3. On first launch, grant **All files access**. Some TV builds hide this settings screen. If yours does, the app shows the adb command that grants it:
   `adb shell appops set --uid com.woodruff.tvexplorer MANAGE_EXTERNAL_STORAGE allow`

## Build locally

You need JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

## Security notes

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
  data/      file listing, storage volumes, file operations, installed apps
  server/    wireless transfer HTTP server + foreground service
  player/    video/music player and photo viewer
  ui/        Compose for TV screens, dialogs and the main ViewModel
  util/      intents (open/install/uninstall), permissions, QR, formatting
app/src/main/assets/web/index.html   the browser UI for wireless transfer
```
