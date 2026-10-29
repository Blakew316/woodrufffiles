# Woodruff Family

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
  and new mail shows up as a notification. While Mail is on screen it stays signed in (until 3 idle minutes), so
  messages open at once.
- **Phone connection**: scan the QR code once (the pairing key persists). On iPhone, tap **Share → Add to Home Screen**
  and it opens like an app that stays paired. It looks and moves like an iPhone app (pages slide in; **‹** or a swipe
  from the left goes back), in light and dark, with four tabs:
  - **Remote**: laid out like the Siri Remote: a round clickpad (tap or swipe), Back, Home, Play/Pause, Mute and
    volume, plus **Netflix** and **YouTube** buttons that open those apps on the TV (over another app only with
    *Pop-ups over other apps* on). The TV's name at the top picks a TV when several are linked. When a text box is
    selected on the TV, the keyboard button lights up and a *Type into …* button appears; letters appear on the TV
    as you type and Enter presses Done. In other apps, Play/Pause, volume and Home work; the arrows and OK only work
    in Woodruff Family (Android doesn't let one app press keys in another).
  - **Send**: one message box and a **To** row (everyone by default; choose TVs, phones and *Keep on Every TV's
    Home*), then **Photos & Files** (browse the TV's storage, play, set as background, save to iPhone, upload, and
    press and hold a folder to use it for the slideshow), **Open a Link** (YouTube, Apple Music or a video) and
    **Start Photo Slideshow**.
  - **Notes**: the same notes as the TV's Notes screen, with search and pinning (press and hold, or **…**). A note
    opens as its own page and is saved when you go back; tap the pencil to write one.
  - **Settings**: this phone (name, lock-screen alerts), **TVs** (what's playing on each TV; tap one for a live
    picture of its screen), **Connections** (Google Drive, posters, cameras, mail), weather on Home, **Away From
    Home** (the Tailscale link) and **Shortcuts** (the links for iPhone Shortcuts).
- **Library in the side menu**: **Movies** and **TV Shows** open the Drive folders of those names (created by you,
  anywhere in My Drive, top level preferred) and stream from them. **Photos** and **Videos** show what you've added from
  Google Photos (saved on the TV), each with an **Add from Google Photos** button. Google Drive and Google Photos
  settings live in Settings.
- **Filters**: Movies and TV Shows can be sorted (A–Z, recently added, newest, top rated), searched (type on the
  TV or the phone), and narrowed by decade and genre. Titles and years come from the file names ("The.Matrix.1999.
  1080p.mkv" shows as *The Matrix (1999)*); genres and ratings come from TMDB when a key is set. *Show → Unwatched*
  lists films without a ✓ and shows not started. Photos and Videos
  sort newest or oldest first and filter by year and month, using when each was taken: Google's capture time for
  Google Photos items, otherwise the photo's own data or a date in its name.
- **Live TV picture on the phone** (Settings → TVs → tap a TV): about once a second, only while it's open. Only Woodruff
  Family's own screens can be shown (video itself appears black); other apps would need a permission prompt on the
  TV every time.
- **Away from home with Tailscale**: install Tailscale on the TV and the iPhone (same account). The TV's Phone
  screen then shows an *Away from home* link and QR code (also in the phone app: Settings → *Away From Home*); open it once on the phone
  and add it to the Home Screen. Everything in the phone app then works from anywhere, encrypted by Tailscale.
  The phone connection starts by itself when the TV turns on and after Woodruff updates (it waits for Wi-Fi first),
  so nobody needs to open Woodruff on the TV after a restart. Settings → Phone connection → *Start when the TV turns on*.
- **Several TVs**: install Woodruff Family on each TV and give each a name (Settings → TVs → This TV's name).
  On the TV you'll watch from (the hub), choose **Add another TV**, pick the other TV from the list and type the
  6-digit code it shows under **Link this TV to another**. The hub's Command center and the phone's Settings → TVs then
  list every TV with what's playing, and the phone can see and control any of them. Woodruff's own player is
  always reported; to also see Netflix, YouTube and other apps, turn on **See what other apps are playing** on
  each TV (Notification access; Settings shows an adb command for TVs that hide that setting). If the router gives a
  TV a new address, the others find it again by themselves within a few minutes. The phone's TV list answers at once:
  a sleeping TV shows as it was last seen, then as off.
- **Notes** (side menu): pinned and recent notes, editable from the TV or any paired phone.
- **Announcements**: each phone gets a name ("Mom's iPhone"). From the TV (Notifications → Announce) or a phone,
  pick who gets it. Phones that turned on *Lock-screen alerts* get a real notification through the free
  [ntfy](https://ntfy.sh) app. The others see it whenever Woodruff is open. The phone's *TV screen* switch shows it on
  every linked TV, over whatever is playing, with a soft chime; the TV's Announce dialog picks phones, *This TV* and each
  linked TV. A TV that's off is skipped without holding anyone up.
- **Screensaver and background**: any photo can be the app background, and a photo slideshow (with a clock) can run from
  Home, after a few idle minutes, or as the TV's own screensaver (Settings → Screensaver & background). Coming back from it, posters show at once.
- **Command center**: live Google Home / Nest cameras and doorbells (WebRTC, so the newest models work). Either
  cameras only or "all on one" next to the clock, notifications and notes. Select a camera for full screen with sound,
  Left/Right flips between cameras. Setup is [below](#connect-google-home-cameras).
- **Home dashboard**: one screen that shows everything at a glance. *Continue watching* shows the latest video
  large and the rest as square tiles, each with a play button, one click to resume; *TVs* switches between the family's linked TVs to show what's on each, with a live picture; *Cameras*
  plays the first four cameras live on their own (only while Woodruff is on screen; Settings → *Live cameras on Home*
  turns this off per TV), and opens one full screen; *At a glance* has the next calendar
  event, the latest notifications and a pinned note. Setup (iCloud, storage, apps, browse by type) lives in Settings.

  An iPhone **Shortcut** can POST to `/api/notify?k=…&tvs=all` (the URL is shown on that tab) for automations like "when
  I get home". `tvs` can also be `this` or TV names, comma-separated; links copied before this version reach only that TV.
- **Player options**: the clock button in the player's controls (or the remote's Menu/Info button) sets a sleep
  timer (30/60/90 minutes or end of episode; then the slideshow takes over) and marks where a show's intro ends.
  After that, *Skip intro* appears during the intro of every episode of that show; OK skips it.
- **Subtitles and audio**: the subtitles button and the gear menu pick tracks; the choice is remembered per show.
- **Voice and the screensaver**: "Hey Google, pause" / Alexa (pause, play, rewind) and Bluetooth buttons work in the
  player; other apps go quiet while a film plays; a paused film lets the TV's screensaver come on and comes back paused.
  The last few seconds stay buffered, so a short rewind is instant.
- **Match frame rate** (Settings → Appearance, off by default): films play at their own frame rate, so slow pans
  don't stutter. The screen goes black for a second or two when a film starts and when you leave the player.
- **Morning briefing**: before 10am Home's greeting line becomes today's weather (from Open-Meteo, no account)
  and calendar; the date line shows the current temperature all day. Set the place in the phone app: Settings →
  *Weather on Home* (city or ZIP).
- **On this day**: photos taken on today's date in earlier years show up in *At a glance* (select to see them as a
  slideshow) and come first in the regular slideshow.
- **Family message board**: in the phone app's Send tab, **To** → *Keep on Every TV's Home* keeps a message at the top of Home
  on this TV and every linked TV until someone dismisses it on that TV.
- **Status** (top of Settings): version, phone connection, Google Drive, cameras, doorbell alerts, updates, and
  every linked TV (online or not, and its version), each with the reason when something isn't working.
- **Share to TV from iPhone**: phone app → Settings → *Shortcuts* copies a link for a "Send to TV" Shortcut in the
  share sheet (steps are on that screen). Links open on the TV (YouTube and Apple Music in their apps); text
  shows up as a message.
- **Phone remote extras**: volume down / mute / up buttons (the TV's own volume, whatever is on screen); hold an
  arrow to keep scrolling. Play/Pause also works with Netflix, YouTube and other apps in front (most reliably with
  *See what other apps are playing* on).
- **Up next**: when an episode (or any queued video) ends, a card counts down 10 seconds to the next one; OK
  plays it now, Back stops. Finishing an episode puts the next one in Continue watching (*Next · S2 E5*) and on the
  show's page; near a season's end the next season's first episode is queued.
- **Recently added**: Home's first panel shows the newest Movies and TV Shows (posters) after what's in progress,
  or on their own when nothing is. Posters, Recently added and voice search are ready right after start, from the
  library saved last time.
- **Title pages**: a film or show opens its page with its length, age rating, cast and when it would end
  (*Ends at 11:34 PM*). A show offers *Resume S2 E4* or, after a finished episode, *Play S2 E5*, on that season; a film in
  a collection lists the others you have. Seasons and episodes are kept on the TV, so pages open complete.
- **Mark as watched / unwatched**: hold OK (or press Menu) on a poster, an episode or a season's tab, or on a
  Continue-watching tile on Home.
- **Watch together**: hold OK (or Menu) on a Continue-watching tile → *Watch together on all TVs* starts that
  Google Drive video on every linked TV from the same spot. The TV that started it leads: pausing, seeking and
  its position keep the others in step (re-synced when more than 2 seconds apart).
- **Search**: *Search* in the side menu (or the remote's search button, where the TV passes it to apps) listens for
  a title, or lets you type one, and finds it in Movies and TV Shows.
- **Cameras on Home** (Settings → Command center): *All live*, *One at a time (lighter)* (one tile streams and
  moves on every minute, the tile you're on always plays) or *Off*. Tiles that aren't streaming show the last
  frame seen from that camera. TVs Android marks as low-memory (usually 1 GB) use one at a time automatically.
  Each camera has one live stream shared by Home, the grid, full screen and the doorbell, so moving between them (or a
  ring) shows the picture at once; a stream no screen shows closes after about 10 s, and at once when Woodruff leaves
  the screen or a video starts.
- **Person and motion alerts**: with doorbell alerts set up, a person (on by default) or motion (off by default)
  seen by any camera pops up on every TV, at most once per camera every 2 minutes. Toggles are in the phone app
  under Settings → Cameras.
- **Doorbell alerts**: when the Nest doorbell rings, every linked TV shows a notice (over other apps too) and opens
  the doorbell full screen if Woodruff is on screen. During a film in Woodruff's player a card says *Someone's at the
  door · OK to look* for 20 s: OK shows the camera live with sound (Left/Right for the others), Back returns to the film. Google only announces rings through Cloud Pub/Sub: turn on
  Pub/Sub for the Device Access project, create a pull subscription to its topic in Google Cloud, enter the
  subscription's full name in the phone app (Settings → Connections → Cameras → Doorbell alerts) for one TV, then connect the
  cameras again on that TV so the sign-in includes Pub/Sub.
- **Notifications**: messages from the phone and new mail appear as a pop-up, even over other apps once
  "Pop-ups over other apps" is allowed in Settings (also on Android 7 TVs, such as older Fire TV sticks). They're also
  kept in the Notifications screen. Announcements and the doorbell play a soft chime (Settings → Advanced → *Chime for
  announcements*; never for mail, never while the TV is asleep), and a doorbell pop-up over another app stays 25 s.
- **Typeface**: Inter, the closest open-license match to Apple's San Francisco font, with Apple-style tracking.

- **Google Drive**: stream videos and music (with seeking), view photos, or download files to the TV. Read-only access.
  Films play over one kept-open connection to Drive, so they start and seek quickly.
  You sign in on your phone by scanning a QR code; it needs a one-time setup, described [below](#connect-google-drive-one-time-setup).
- **Continue watching**: playback resumes where you stopped, and Home shows what you're partway through, one tile per
  show. It follows the family to every TV signed in to the same Google account: what's in progress (Drive videos), the
  ✓s and each show's Skip intro and languages, through one small hidden file per TV in Drive (Settings → Accounts →
  *Share progress with other TVs*, on by default; it needs the `drive.appdata` permission, see the Drive setup below).
  Videos from the TV's own storage show a still from the video, made once and kept on the TV.
- **iCloud**: iCloud Mail works through Mail (Apple supports IMAP with an app-specific password).
  iCloud Drive has no supported sign-in for Android apps, so files come from the iPhone through **Phone → Files → Upload → Browse**.

- **Browse everything**: internal storage, SD cards and USB drives. The list refreshes when a drive is plugged in.
- **Browse by type** (Settings → Storage & apps): Videos, Music, Pictures, Documents, APK files, Archives and Downloads, across all drives.
- **File operations**: copy, move, rename, delete, new folder, zip and unzip. Multi-select works with the remote.
  Large copies show progress and can be cancelled. Existing files are never overwritten (the copy is named `name (1).ext` instead).
- **Built-in player and viewer**: videos and music play in a Media3 (ExoPlayer) player, and photos open in a viewer with a slideshow.
  Many TVs ship without either app. "Open with…" still hands files to VLC, Kodi and so on.
- **APK sideloading**: open an `.apk` to install it. If needed, the app walks you through the "install unknown apps" setting.
- **App manager**: launch apps, back up an APK to `Download/TVExplorer/Apps`, open App info, or uninstall.
- **Send files**: start a small web server on the TV, then scan the QR code with your phone or open the URL on a computer.
  You can drag and drop uploads (streamed straight to disk, so they can go to a USB drive), download, create folders and delete.
- **Light or dark mode**: switch it at the bottom of the side menu (Display → Light mode); the choice is remembered.
- **TV-first navigation**: a slim icon menu on the left that slides open over the screen when you move onto it (so
  screens like the Home dashboard use nearly the full width), large focus targets, and Back always does the expected thing.
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

## Automatic updates

Woodruff checks this project's releases every hour (and each time it opens, at most every 10 minutes), downloads a newer version
and installs it with Android's package installer (which only accepts an update signed with the same key).

- Android 12 and newer: the first update after installing from Downloader asks once ("Update"); after that,
  updates install by themselves as soon as nobody is using Woodruff: nothing playing in it, and either it isn't
  on screen or no button has been pressed for 2 minutes. If it was on screen, it opens again by itself, and the
  phone connection comes back on its own. Keep updating this way rather than reinstalling from Downloader, which
  makes the next update ask once again.
- Older Android (11 and below) doesn't let apps update themselves silently: Android's *Update* screen opens by
  itself when Woodruff starts with an update ready, so it's one button press.
- The TV asks once to let Woodruff Family *install unknown apps*.
- Settings → Updates shows the version, checks now, and turns automatic updates off.
- **Fast straight after an update**: each release carries a speed profile (`WoodruffFamily-api28.dm` and
  `WoodruffFamily-api31.dm`) that installs with it on Android 9 and newer, so Android prepares the app's busiest code
  while installing instead of over the following days. Settings → Advanced → *App code* shows how it's prepared.
- **Test TV** (Settings → Updates): turn it on for one TV. That TV installs each new version as soon as it's
  out; every other TV waits until the newest version has been out for a day with nothing newer after it, so a
  bad build shows up on the test TV first. *Check for updates* skips the wait.
- **Undo a bad update**: Android won't install an older version over a newer one, so an earlier version is
  re-released with a new, higher number: GitHub → Actions → Build APK → *Run workflow*, with the commit to go
  back to. It's marked "rollback" and every TV installs it right away.
- **Crashes**: the next launch shows what went wrong in Notifications, and the phone app shows the full report
  under Settings → *Last crash on this TV*, with Copy.

Each release is built with its build number as the version (release v2.0.N is version N), which is how the app
tells a newer release apart.

## Install on your TV

1. Download **WoodruffFamily.apk** from the latest release:
   https://github.com/Blakew316/woodrufffiles/releases/latest/download/WoodruffFamily.apk
   (The older WoodruffFilms.apk / WoodruffPictures.apk / WoodruffShows.apk links still work.)
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
   add your Gmail under **Audience → Test users**, and add the scopes `.../auth/drive.readonly` and
   `.../auth/drive.appdata` under **Data access** (the second lets the TVs share Continue watching; see below).
3. **Credentials → Create credentials → OAuth client ID**, application type **Web application**:
   - **Authorized redirect URIs → Add URI**: `https://blakew316.github.io/woodrufffiles/oauth.html`
   - Create it, and keep the **Client ID** and **Client secret** handy (open the client again to copy them).
   - If you made an *Android* client earlier, you can delete it. It's no longer used.

**3. On the TV**, open **Google Drive** in Woodruff Family:
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
- **Sharing Continue watching between TVs:** add `.../auth/drive.appdata` under **Data access**
  (Google Auth Platform → Data access → Add or remove scopes), then on each TV that signed in before, open
  Settings → Accounts → **Sign in to Google Drive again** and scan the code with your phone, leaving every box ticked.
  Use the same Google account on every TV. The library stays as it is.

## Movie and show posters

Movies and TV Shows use poster tiles. Artwork follows the Plex/Kodi convention, no API key needed:
- A movie or show **folder**: put `poster.jpg` (or `folder.jpg` / `cover.jpg`) inside it.
- A **loose movie file**: put an image with the same name next to it, e.g. `Inception.mp4` + `Inception.jpg`.
Those images are used as posters and hidden from the grid.

**Automatic posters (TMDB):** add a free API key from [themoviedb.org](https://www.themoviedb.org/settings/api)
(the API Key or the Read Access Token) in **Settings → Movie & show posters**, or in the phone's Settings tab. Movies,
shows and seasons without their own art then get official posters, matched from names like `Inception (2010).mkv`,
`The.Office.S01.1080p` or `Season 2`. Lookups are cached on the TV. Your own art always wins. Without either, the
video's Drive preview frame (or a title card for folders) is shown.

*This product uses the TMDB API but is not endorsed or certified by TMDB.*

## Connect Google Calendar (Today's Activities)

1. In Google Cloud (the Drive project), **APIs & Services → Library**: enable **Google Calendar API**.
2. On the TV: **Settings → Google Calendar → Today's Activities**, scan the QR code and sign in.
Today's events from every calendar that's switched on in Google Calendar appear on Home under the greeting and in the
Command center, refreshed every 10 minutes and at midnight. Read-only.

## Connect Google Photos (slideshow)

1. In Google Cloud (the Drive project), **APIs & Services → Library**: enable **Google Photos Picker API**.
2. On the TV: **Settings → Google Photos** (or the Home tile). Scan the QR code and sign in, then scan the next
   QR code, pick photos in Google's picker on your phone and tap **Done**.
3. The TV downloads them to `Pictures/Google Photos` (videos to `Movies/Google Photos`) and the slideshow switches
   to that folder.

**Show one album made for the TV.** Google's picker has no list of albums, but searching an album's name in it shows
that album. So make an album in Google Photos (say "Living room TV"), then on the TV open Google Photos, scan the
code, type the album's name in the picker's search bar, select its photos and tap **Done**.

- **A new pick replaces the earlier ones**: the TV keeps exactly what you picked, so its storage doesn't fill up
  pick after pick. Photos it already has aren't downloaded again, so picking the album again after adding a few
  photos only fetches the new ones (and removes the ones you took out of it). **Add instead** (shown before you
  pick) keeps what's there and adds to it. If any photo fails to download, nothing is removed; pick again.
- Photos are saved at the size the TV shows them: 1920×1080 (about a quarter of a 4K copy), 3840×2160 only on the
  few boxes that draw their screen in 4K. Videos are saved as they are.
- The window shows how many photos and videos are on the TV and how much space they take, with **Remove all** to
  delete them from the TV (your Google Photos library isn't touched).

Google only lets apps see the photos you pick each time (no browsing the whole library or its albums since 2025),
which is why it's a pick-and-download flow.

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
- Google Drive access to the family's files is read-only (`drive.readonly`): the app can't change, add or delete any of
  them. The only place it writes is its own hidden app folder (`drive.appdata`, which doesn't show in Drive), for sharing
  Continue watching between TVs. The OAuth client secret and tokens are kept in the app's private storage.
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
- A linked TV gets its own link key, not the phone pairing key: it can only read status, see the screen, press
  remote keys and show messages, never touch files, settings or mail. **Stop other TVs seeing this one** in Settings revokes every link.
  Linking codes last 10 minutes, work once, and die after 5 wrong tries.
- To find a linked TV at a new address, a TV asks the device on your network that now uses that TV's name, with the
  link key, and uses it only if it answers as the same TV. Like the rest of this traffic, it's plain HTTP on your network.
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
