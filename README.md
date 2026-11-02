# Woodruff Family

A media hub and file manager built for Android TV and Google TV, made to be driven entirely with a remote.
It plays videos, music and photos from the TV, USB drives and Google Drive.
It's a ground-up rewrite inspired by [AnExplorer](https://github.com/DWorkS/AnExplorer) (Apache 2.0).
None of its code is reused: the original no longer builds, because it depends on jcenter, Fabric and a 2018 Android toolchain.

**See the app without a TV:** [UI gallery](https://blakew316.github.io/woodrufffiles/ui/). Every build redraws
each TV screen and phone-app tab, light and dark (`gallery` job in the build workflow).

## Features

- **Settings** (side menu): TV health first (below), then Google Drive keys, mail accounts, theme, pop-ups and phone
  pairing. Secrets are stored **encrypted** with a key in the Android Keystore. They persist across restarts and updates,
  and you can type them on the TV or paste them from your phone.
- **Mail**: iCloud Mail, Gmail, Yahoo or any IMAP account, using an app-specific password. There's an inbox and a reader,
  and new mail shows up as a notification. While Mail is on screen it stays signed in (until 3 idle minutes), so
  messages open at once.
- **Phone connection**: scan the QR code once (the pairing key persists). On iPhone, tap **Share → Add to Home Screen**
  and it opens like an app that stays paired, in one round trip. The icon is named after that TV (Settings → TVs &
  phones → *This TV's name*, e.g. *Den*): its picture says DEN where it says WOODRUFF FAMILY (same emblem, same
  colours), and so does the label under it, so with an icon for each TV the Home Screen says which is which. The app
  inside stays Woodruff Family. An icon added before a TV was named or renamed keeps its old picture and label until
  it's added again. It looks and moves like an iPhone app (pages slide in; **‹** or a swipe from the left goes back;
  buttons and rows answer the moment a finger lands; the tab bar's lens glides, and follows a finger dragged along the
  bar; lists glide when they change; it follows the iPhone's Reduce Motion), in light and dark, with five tabs:
  - **Remote**: laid out like the Siri Remote: a round clickpad (tap or swipe), Back, Home, Play/Pause, Mute and
    volume, plus **Netflix** and **YouTube** buttons that open those apps on the TV (over another app only with
    *Pop-ups over other apps* on). The TV's name at the top picks a TV when several are linked. When a text box is
    selected on that TV, the keyboard button lights up and a *Type into …* button appears; letters appear on the TV
    as you type and Enter presses Done. This works on a linked TV too (through the TV that serves the app): the
    Keyboard sheet names the TV it types into, and picking another TV starts again on that TV's own text box. In
    other apps, Play/Pause, volume and Home work; the arrows and OK only work in Woodruff Family (Android doesn't let
    one app press keys in another). In Woodruff they reach whatever is open on the TV, title pages, menus, the
    filters and confirmations included (Back closes them), and holding OK opens the menu for what's focused, as
    holding OK on the TV's remote does. While one of Android's own screens is up over Woodruff (an install or
    permission prompt), use the TV's remote: the phone's keys do nothing rather than press Woodruff behind it. If the
    TV can't be reached, the Remote says so (*Can't Reach Living Room · Check that it's on and this phone is on the
    same Wi-Fi.*) until it answers again, and no request waits forever.
  - **Watch**: what's in the Google Drive Movies and TV Shows folders the TVs play from: a billboard of featured
    titles, Continue Watching with progress (tap to resume on the TV), Recently Added, Movies and TV Shows rows with
    See All grids (filters and search), and a Search page across both libraries. Tap a title for its details and, for
    a show, its seasons and episodes. Everything plays on the TV named at the top of the screen; change it there, or
    with *On … ⌄* on a title's page. With more than one TV you can also start *Watch Together on All TVs*. Hold a
    tile for *Start Over*, *Play On…*, *Mark as Watched* and *Remove from Continue Watching*; marks and removals are
    the same ones the TV's own menus make. While the chosen TV plays, a bar above the tabs shows what's on, with
    −10 s, Play/Pause and +30 s (seeking only where what's playing supports it); tap it for the TV's screen and
    *Next Episode*. If Woodruff can't come to the front on that TV (another app is open and *Pop-ups over other apps*
    is off), the phone says *Ready on Den*: the video waits there and starts when Woodruff is opened on that TV
    within 10 minutes.
  - **Send**: one message box and a **To** row (everyone by default; choose TVs, phones and *Keep on Every TV's
    Home*), then **Photos & Files** (browse the TV's storage, with each drive's free space; play, set as background,
    save to iPhone, upload, and press and hold a folder to use it for the slideshow), **Open a Link** (YouTube, Apple
    Music or a video) and **Start Photo Slideshow**. A file too big for the room left on the TV is turned down on the
    phone before anything is sent, in plain words (*This video is 2.1 GB; Living Room has 800 MB free.*); the TV
    keeps 200 MB of its own storage free for itself and refuses an upload that won't fit.
  - **Memories** (the Family Memories page): the photos and videos in the TV's Family Memories, newest first, in a
    grid like the Photos app's. **+** adds photos and videos from the iPhone: each photo is shrunk to the size the TV
    shows it at and keeps when it was taken, and they go up one by one, each tile filling its ring (a video too big
    for the TV is turned down first, as in Photos & Files). Tap one to see it full screen (it grows out of its tile;
    swipe through, pinch or double-tap to zoom, drag down to put it back); **Show on TV** puts it on the TV, which
    follows at once as you swipe, however big Family Memories gets, next to Share and Delete (and *Set as TV
    Background* under **…**; press and hold a tile for the same). The slideshow button plays Family Memories on the
    TV.
  - **Settings**: this phone (name, lock-screen alerts and the week's *Recent Messages*), **TVs** (each TV with what
    it's playing and how it's doing; tap one for its page, see *TV health* below), **Connections** (Google Drive,
    posters, cameras, mail, and *Sign In* for this TV's Google sign-ins), weather on Home, **Away From Home** (the
    Tailscale link) and **Shortcuts** (the links for iPhone Shortcuts).
- **Library in the side menu**: **Movies** and **TV Shows** open the Drive folders of those names (created by you,
  anywhere in My Drive, top level preferred) and stream from them. Subtitle files, `.nfo` files and artwork kept beside
  the videos never show up as titles. **Family Memories** (Photos and Videos) shows what
  you've added from Google Photos or a phone (saved on the TV), with an **Add from Google Photos** button; what a phone
  adds or deletes shows up while you're looking, and a photo a phone shows on the TV changes in place, cross-fading. Google Drive and Google Photos
  settings live in Settings.
- **Filters**: Movies and TV Shows can be sorted (A–Z, recently added, newest, top rated) and narrowed by decade and
  genre, and their search field opens Search (below). Titles and years come from the file names ("The.Matrix.1999.
  1080p.mkv" shows as *The Matrix (1999)*); genres and ratings come from TMDB when a key is set. *Show → Unwatched*
  lists films without a ✓ and shows not started. Photos and Videos
  sort newest or oldest first and filter by year and month, using when each was taken: Google's capture time for
  Google Photos items, otherwise the photo's own data or a date in its name.
- **Live TV picture on the phone** (Settings → TVs → a TV → its picture): about once a second, only while it's open.
  It shows Woodruff's pop-ups (a title page, a menu, the filters) over the screen, dimmed behind as on the TV. Only
  Woodruff Family's own screens can be shown (video itself appears black); other apps would need a permission prompt
  on the TV every time.
- **Away from home with Tailscale**: install Tailscale on the TV and the iPhone (same account). The TV's Phone
  screen then shows an *Away from home* link and QR code (also in the phone app: Settings → *Away From Home*); open it once on the phone
  and add it to the Home Screen. Everything in the phone app then works from anywhere, encrypted by Tailscale, except
  signing a TV in to Google, which works on the home Wi-Fi only.
- **The phone connection runs by itself**: it starts when the TV turns on and after Woodruff updates. It waits for
  Wi-Fi or Ethernet for as long as it takes (a router still starting after a power cut, Wi-Fi that sleeps in standby),
  follows the TV to a new address, and comes back by itself if Android closes Woodruff to free memory or after a crash.
  On Android 12 and later that comeback may need *Pop-ups over other apps* (Settings → Advanced); without it, the phone
  connection returns the next time the TV turns on, Woodruff updates or someone opens Woodruff. Mail checks, update
  checks, the keys every TV shares and the doorbell relay run with it, so after a power cut nobody needs to open
  Woodruff on any TV, and phones stay paired even on a TV whose Keystore is slow to wake. While the TV has no network,
  its Phone screen says *Offline* and the QR code appears by itself once it's connected. To stop all of this starting
  by itself: Settings → TVs & phones → *Start when the TV turns on*.
- **Several TVs**: install Woodruff Family on each TV and give each a name (Settings → TVs & phones → This TV's name).
  On the TV you'll watch from (the hub), choose **Add another TV** (Settings → TVs & phones → Other TVs), pick the other
  TV from the list (by name) and type the 6-digit code it shows under **Link this TV to another**. The hub's Command
  center and the phone's Settings → TVs then list every TV with what's playing, and the phone can see and control any
  of them. Linked TVs share their keys (TMDB, Google's Client ID and secret, the cameras project; also the
  doorbell's subscription and the person/motion choice): enter them on one and every TV gets them (see
  [Keys are entered once](#keys-are-entered-once)). Woodruff's own player is
  always reported; to also see Netflix, YouTube and other apps, turn on **See what other apps are playing** on
  each TV (Notification access; Settings shows an adb command for TVs that hide that setting). If the router gives a
  TV a new address, the others find it again by themselves within a few minutes. The phone's TV list answers at once:
  a sleeping TV shows as it was last seen, then as off. A TV that was reset or reinstalled shows *Link again* instead
  of staying on the hub as a ghost: on that TV open Settings → TVs & phones → Other TVs → **Link this TV to another**,
  then choose *Link Again* on the phone or the hub TV and type the code; its old entry is replaced.
- **Notes** (side menu): pinned and recent notes, written on the TV.
- **Announcements**: each phone gets a name ("Kitchen iPad"). From the TV (Notifications → Announce) or a phone,
  pick who gets it. Phones that turned on *Lock-screen alerts* get a real notification through the free
  [ntfy](https://ntfy.sh) app. The others see it whenever Woodruff is open: each TV keeps every phone's messages (the
  last 20 of the last week) on its own storage, so an automatic update or a lost reply never loses one. Messages that
  arrive together show one after another, four or more as one banner (*4 new messages*), and the week's messages stay
  under Settings → This Phone → *Recent Messages*. The phone's *TV screen* switch shows it on
  every linked TV, over whatever is playing, with a soft chime; the TV's Announce dialog picks phones, *This TV* and each
  linked TV. A TV that's off is skipped without holding anyone up.
- **Screensaver and background**: any photo can be the app background, and a photo slideshow (with a clock) can run
  from Home, after a few minutes without a key pressed anywhere in Woodruff (it waits while a Google Photos pick or a
  copy is under way), or as the TV's own screensaver (Settings → Screensaver). Coming back from it,
  posters show at once.
- **Light on the TV**: ten minutes after Woodruff leaves the screen (another app, the TV's home or screensaver,
  standby), or at once if Android says memory is short, it gives back the memory it kept for scrolling back and the
  phone picture's buffers, and the camera tiles' graphics too if the screen you left shows no cameras, so the TV's
  other apps have more room. The screen you left still comes back exactly as it was.
- **Command center**: live Google Home / Nest cameras and doorbells (WebRTC, so the newest models work). Either
  cameras only or "all on one" next to the clock, notifications and notes. Select a camera for full screen with sound,
  Left/Right flips between cameras. Setup is [below](#connect-google-home-cameras).
- **Home dashboard**: one screen that shows everything at a glance. *Continue watching* shows the latest video
  large and the rest as square tiles, each with a play button, one click to resume; *TVs* switches between the family's linked TVs to show what's on each, with a live picture; *Cameras*
  plays the first four cameras live on their own (only while Woodruff is on screen; Settings → Appearance →
  *Cameras on Home* turns this off per TV), and opens one full screen; *At a glance* has the next calendar
  event, the latest notifications and a pinned note. Setup (iCloud, storage, apps, browse by type) lives in Settings.

  An iPhone **Shortcut** can POST to `/api/notify?k=…&tvs=all` (the URL is shown on that tab) for automations like "when
  I get home". `tvs` can also be `this` or TV names, comma-separated; links copied before this version reach only that TV.
- **Player controls**, laid out like tvOS: what's playing at the bottom left (the show and *S2, E5* small above the
  episode's name), three small round buttons tucked at the far right (Subtitles, Audio, and **…** for speed, the next
  episode, sleep timer and intro) and a thin scrub bar with the time played and the time left. Play, pause, Next and
  Previous are the remote's own buttons. The scrub bar has focus as they come up: Left/Right scrub with a
  picture of that spot (faster the longer it's held), OK or a pause plays from there, Back goes back to where it was;
  OK on the bar plays/pauses. Scrubs, the remote's ⏪/⏩ and Skip intro start on the first clean frame just after the
  spot, so the picture comes back sooner (Watch together seeks exactly). Back puts the controls away first, a second
  Back leaves.
- **Player options**: the **…** button in the player's controls (or the remote's Menu/Info button) sets a sleep
  timer (30/60/90 minutes or end of episode; then the slideshow takes over) and marks where a show's intro ends.
  After that, *Skip intro* appears during the intro of every episode of that show; OK skips it.
- **Subtitles, audio and speed**: Subtitles and Audio open a menu above their button (✓ marks what's on), speed is
  under **…**; the subtitle and audio choices are remembered per show. Audio lists every sound track in the film, as
  *English · 5.1 · Commentary*; a track this TV can't play is dimmed and marked *Can't play* (OK on it says why).
- **DTS and TrueHD sound**: sound the TV can't decode or pass through itself (DTS, DTS-HD MA, Dolby TrueHD, and Dolby
  Digital on TVs without Dolby) is decoded by FFmpeg, after the TV's own decoders and passthrough to a soundbar or
  receiver, which still come first. If a film's sound still can't play here (DTS:X, say), the player says so as it
  starts (*This TV can't play this film's sound (DTS:X), so it plays silently.*, or *Playing English · Stereo: this TV
  can't play the main sound (…)*) instead of playing silently or on the commentary without a word.
- **Subtitle files**: subtitle files beside a film or episode, on Google Drive or a USB drive, play with it:
  `Movie.srt`, `Movie.en.srt`, `Movie.eng.forced.srt`, `Movie.English.sdh.srt`, `.vtt` and `.ass`/`.ssa`, named after
  the video as Plex and Kodi expect. In a film's own folder any subtitle file counts (`English.srt`), except one named
  for another episode (`Show.S01E02.en.srt` stays with episode 2). On a USB drive, a `Subs` folder beside the video is
  read too. They appear in the player's Subtitles menu after the film's own tracks, by language (*English (SDH)*,
  *Spanish (forced)*), never by file name, and the choice is remembered for the show. English ones come on by
  themselves for foreign speech (full subtitles before SDH or signs-only ones). Woodruff learns them from the Drive
  folders it lists anyway (a library, a title page, a season) and keeps that list on the TV, so a film whose folder
  this TV has never opened plays without them until its title page or folder has been opened once. Subtitle files
  saved in Windows' Western encoding keep their accents, and a missing or broken one never stops the film.

  Text subtitles look like Apple TV's: white Inter SemiBold letters with a thin dark outline and no boxes. When the
  TV's own caption settings are on (Accessibility → Captions), they decide instead. Choose *Small*, *Standard* or
  *Large* from the Subtitles menu's last row (*Size: Standard*) or in Settings → Appearance → *Subtitle size*; the
  size is the same share of the screen on every film shape. Picture subtitles (Blu-ray PGS) keep their own look and
  size.
- **Voice and the screensaver**: "Hey Google, pause" / Alexa (pause, play, rewind) and Bluetooth buttons work in the
  player; other apps go quiet while a film plays; a paused film lets the TV's screensaver come on and comes back
  paused, even if an update installed meanwhile. The last 12 seconds stay in memory, and a press back is aimed from
  where the film really is, so going back 10 seconds is instant (TVs that give Woodruff little memory keep 5 seconds,
  so there it loads again).
- **Loading and Wi-Fi drops**: the player keeps up to 50 seconds of the film loaded ahead (as much as the TV's memory
  allows), topped up as it plays, so most Wi-Fi hiccups pass unnoticed. When the picture does pause to load for more
  than half a second, a ring turns in the middle of the screen (*Still loading…* after 10 seconds). If the connection
  drops, it says *Reconnecting…* and carries on the moment the Wi-Fi is back (or tries again every 10 seconds); after
  half a minute it suggests checking the TV's internet connection. The screen stays on for the first five minutes of
  trying; after that the TV may sleep as it would for a paused film. If Google has ended the Drive sign-in, it says so
  and how to sign in again. A film that can't play says why in plain words, and the next one starts.
- **Match frame rate** (Settings → Appearance, off by default): films play at their own frame rate, so slow pans
  don't stutter. The screen goes black for a second or two when a film starts and when you leave the player.
- **Morning briefing**: before 10am Home's greeting line becomes today's weather (from Open-Meteo, no account)
  and calendar; the date line shows the current temperature all day. Set the place in the phone app: Settings →
  *Weather on Home* (city or ZIP).
- **On this day**: photos taken on today's date in earlier years show up in *At a glance* (select to see them as a
  slideshow) and come first in the regular slideshow.
- **Family message board**: in the phone app's Send tab, **To** → *Keep on Every TV's Home* keeps a message at the top of Home
  on this TV and every linked TV until someone dismisses it on that TV. It also reaches a TV that was off when it was
  pinned (for up to a week); each TV shows a pin once and never brings back one dismissed on it.
- **TV health** (the first row in Settings): its value says *All good* or how many TVs need attention (in amber). It
  opens the house's TVs, each marked *All good*, *Needs attention*, *Off · seen 2 h ago* or *Link again*. Each TV opens
  its report: sign-ins (Google Drive, Photos, Calendar, cameras) and doorbell alerts, version and updates (including an
  update that didn't install and why), whether Woodruff may install updates, the last crash, why Android last closed
  Woodruff (Android 11 and newer), free space, and which keys are saved (never their values). Anything that needs
  attention is amber and says how to fix it. *Check for updates*, *Link again* and *Unlink* are at the bottom of a TV's
  report. On the phone it's Settings → TVs → tap a TV: a still frame of its screen (tap it for the live picture),
  Play/Pause for what it plays, *All Good* or what needs attention, and the same report, with *Check for Updates* and
  *Copy Health Report* (text, with no addresses).
- **Share to TV from iPhone**: phone app → Settings → *Shortcuts* copies a link for a "Send to TV" Shortcut in the
  share sheet (steps are on that screen). Links open on the TV (YouTube and Apple Music in their apps); text
  shows up as a message.
- **Phone remote extras**: volume down / mute / up buttons (the TV's own volume, whatever is on screen); hold an
  arrow to keep scrolling: it moves at a steady pace, never faster than the TV can draw, and stops the moment you let
  go. Play/Pause also works with Netflix, YouTube and other apps in front (most reliably with
  *See what other apps are playing* on).
- **Up next**: when an episode (or any queued video) ends, the last frame darkens and a picture from the next one
  comes up with its show, episode and name; a ring on **Play Now** counts down 10 seconds to it. OK plays it now,
  Right then OK (**Done**) or Back stops. Finishing an episode puts the next one in Continue watching (*Next · S2 E5*) and on the
  show's page; near a season's end the next season's first episode is queued.
- **Recently added**: Home's first panel shows the newest Movies and TV Shows (posters) after what's in progress,
  or on their own when nothing is; a show already in Continue watching isn't repeated there. A poster opens the
  title's page, and Back returns to the poster. Posters, Recently added and Search are ready right after start, from
  the library saved last time. Movies and TV Shows open at once from that saved copy. A library listed in the last
  5 minutes isn't listed again: switching between Movies and TV Shows, or coming back from a title, costs nothing.
  Older than that, it's refreshed in the background while it stays on screen, and **Refresh** in Filter lists it right
  away. Home keeps Recently added current by itself: coming back to Home re-lists a library last listed more than
  five minutes ago, and an idle Home checks once an hour. A library lists up to 20,000 Drive entries. Past that, a
  line under the header says only the first 20,000 show; keeping each film or show in its own folder lets every one
  show. All videos, Shared with me and other Drive folders list up to 2,000 entries, and their breadcrumb says so when
  that's reached.

  When new episodes arrive in a show you already have, the show comes back to the front of Recently added. On Home it
  gets a blue NEW badge; in TV Shows and Search it gets "New · S3 E2", "3 new episodes" or "New season" in blue.
  Opening it goes straight to the first new episode, with *Play S3 E2* ready. Watching one on any TV clears it
  everywhere.
- **Title pages**: a film or show opens its page with its length, age rating, cast and when it would end
  (*Ends at 11:34 PM*). A show offers *Resume S2 E4* or, after a finished episode, *Play S2 E5*, on that season; a film in
  a collection lists the others you have. Seasons and episodes are kept on the TV, so pages open complete. A show's
  page lists every episode with its picture, number and length (*EPISODE 3 · 42 MIN*), its real name and a few lines
  about it, from TMDB. Without a TMDB match, names come from the files (`Show.S01E03.The.Title.1080p.mkv` shows
  *The Title*). The line under the buttons names the episode Play will start (*S2 E4 · The Volcano · Ends at
  11:34 PM*), and the player, Up next and Continue watching use the real name too. Episodes put in Drive in the last
  30 days that no TV has watched or started are marked NEW. Offline, a season this TV has never listed says
  *Episodes will show when the TV is back online.* and loads by itself when the network returns.
- **Title menu**: hold OK (or press Menu) on any film or show (Home, Streaming's rows, grid and banner, a title page's
  rows, Search) for one short menu. It offers the title page's own main action (*Play* / *Resume S2 E4*), then
  *Start over*, *Episodes* / *Details* (Home's resume tiles), *Watch together* (linked TVs), *Mark as watched* (or
  unwatched), *Download* and *Remove from Continue watching*. Episodes on a show's page have the same kind of menu
  (*Resume from 40m*, *Play from the start*, *Watch together*, *Mark as watched*), and holding OK on a season's tab
  offers *Mark season as watched* (or unwatched). Play/Pause on a focused title plays it straight away.
- **Watch together**: *Watch together* in the title menu (with TVs linked) starts that Google Drive video on every
  linked TV from the same spot. The TV that started it leads: pausing, seeking and
  its position keep the others in step (re-synced when more than 2 seconds apart).
- **Search**: the first item in the side menu. The remote's search (or mic) button, on TVs that pass it to apps,
  opens it from any screen, title page or menu and starts listening where the TV can; pressed again while Search is
  open, it listens again. Streaming's search field (*Search Movies & TV Shows*) opens the same page. One search covers
  Movies and TV Shows, and a four-digit year ("2024") also finds titles released that year. Type on Woodruff's own
  keys, a real keyboard, the phone's keyboard (Remote → keyboard; Enter jumps to the first result), or by voice.
  Results are posters that open the title page; Back returns to the result, and Back again returns to the keys.
  Offline, it searches the saved library. If Google Drive isn't set up, its sign-in has ended or the Google keys are
  refused, Search says so and offers the one button that fixes it.
- **Cameras on Home** (Settings → Appearance): *All live*, *One at a time (lighter)* (one tile streams and
  moves on every minute, the tile you're on always plays) or *Off*. Tiles that aren't streaming show the last
  frame seen from that camera. TVs Android marks as low-memory (usually 1 GB) use one at a time automatically.
  Each camera has one live stream shared by Home, the grid, full screen and the doorbell, so moving between them (or a
  ring) shows the picture at once; a stream no screen shows closes after about 10 s, and at once when Woodruff leaves
  the screen or a video starts. A camera counts as live, and Home's tile says LIVE, only while its picture is moving.
  Until its first picture comes it says *Connecting…* (so do full screen and the doorbell's camera), and a tile that
  joins a camera already streaming keeps its last picture until the live one is drawn. After 3 s without a frame the
  picture holds under a calm *Reconnecting…*, and after 30 s it says *Can't reach this camera right now*. The camera
  reconnects by itself: after 15 s without a frame (20 s before the first), when WebRTC gives the connection up, or at
  once if the TV's network came back after the picture stopped. It waits 5 s longer after each failure, up to 2
  minutes, until its picture has run for a minute, so a camera that's off never floods Google with requests. WebRTC is
  loaded only once cameras are about to play: Home's live tiles, the Command center or a doorbell ring.
- **Person and motion alerts**: with doorbell alerts set up, a person (on by default) or motion (off by default)
  seen by any camera pops up on every TV (*Person seen* · the camera's name), at most once per camera every 2 minutes,
  and never after the doorbell rang for the same visit. Toggles are in the phone app under Settings → Connections →
  Cameras, and the choice is the same on every TV. Each TV keeps its camera list, so alerts name the right camera even
  right after a TV started by itself, and Home shows the cameras straight away at start-up.
- **Doorbell alerts**: when the Nest doorbell rings, every TV that's on shows it. Over Woodruff's own screens, its
  photo slideshow and (with *Pop-ups over other apps* allowed) Woodruff's screensaver, the doorbell's camera opens
  full screen with its sound and says when it rang (*RANG AT 7:42 PM*). Back returns to what was showing, exactly as
  it was; with no key pressed for 2 minutes the camera closes by itself. During a film in Woodruff's player a card at
  the top right says *Someone's at the door · OK to look* for 20 s: OK shows the camera live with sound (Left/Right
  for the others), and Back returns to the film. Over other apps it's a card for 25 s. One visitor rings once: a
  battery doorbell's follow-up message about the same visit doesn't ring again, and two doorbells ringing at once
  open one camera view (the second switches it), so one press of Back closes it. Each TV asks Google again as soon as
  a check comes back empty, so a ring shows within seconds.

  Google only announces rings through Cloud Pub/Sub: turn on Pub/Sub for the Device Access project, create a pull
  subscription to its topic in Google Cloud, and enter the subscription's full name in the phone app (Settings →
  Connections → Cameras → Doorbell alerts) on any TV; it's copied to every linked TV. Then connect the cameras again
  on each TV that has them, so its sign-in includes Pub/Sub. Each TV with its cameras connected makes a subscription
  of its own on the same topic and listens on it, so the doorbell rings there even when the other TVs are off. That
  subscription is named after the family's, ending in "-tv-" and part of the TV's id; it keeps messages 10 minutes
  and deletes itself after a day unused. TVs without the cameras connected hear the doorbell through the TV linked to
  them, while that TV is on. If Google won't let a TV make its own subscription (the project doesn't allow it), the
  TV the subscription was entered on listens on the family's one and passes rings on. Settings → Advanced →
  *Doorbell alerts* says how this TV hears it: *Watching*, *Through Living Room*, *Off*, or what Google refused. A TV
  that passes alerts to no other TV stops asking Google while it's in standby.
- **Notifications**: messages from the phone and new mail appear as a compact pop-up at the top right (who it's from,
  the title and the message), even over other apps once
  "Pop-ups over other apps" is allowed in Settings (also on Android 7 TVs, such as older Fire TV sticks). They're also
  kept in the Notifications screen. Announcements and the doorbell play a soft chime (Settings → Advanced → *Chime for
  announcements*; never for mail, never while the TV is asleep).
- **Typeface**: Inter, the closest open-license match to Apple's San Francisco font, with Apple-style tracking.

- **Google Drive**: stream videos and music (with seeking), view photos, or download files to the TV. Read-only access.
  Films play over one kept-open connection to Drive, so they start and seek quickly. The first and last few megabytes
  of each Drive film (its header and index) stay on the TV, up to 96 MB with the oldest making way, so resuming from
  Continue watching asks Drive for one thing instead of three and starts sooner; a film replaced in Drive is noticed
  and read afresh. Scrub pictures read only a little past each frame and stop once the scrub is played, so they don't
  slow the seek. You sign in on your phone, by scanning a QR code or from the phone app the TV serves; it needs a
  one-time setup, described [below](#connect-google-drive-one-time-setup).
- **Continue watching**: playback resumes where you stopped, and Home shows what you're partway through, one tile per
  show. It follows the family to every TV signed in to the same Google account: what's in progress (Drive videos), the
  ✓s and each show's Skip intro and languages, through one small hidden file per TV in Drive (Settings → Accounts →
  *Share progress with other TVs*, on by default; it needs the `drive.appdata` permission, see the Drive setup below).
  Moving to another TV, Resume starts where you stopped: Home, Movies, TV Shows and a title's page fetch the other TVs'
  progress as they open, and again when the network comes back. Starting something waits at most half a second for
  it, and a show another TV went on with plays its next episode.
  Videos from the TV's own storage show a still from the video, made once and kept on the TV.
- **iCloud**: iCloud Mail works through Mail (Apple supports IMAP with an app-specific password).
  iCloud Drive has no supported sign-in for Android apps, so files come from the iPhone through **Phone → Files → Upload → Browse**.

- **Browse everything**: internal storage, SD cards and USB drives. The list refreshes when a drive is plugged in.
- **Browse by type** (Settings → Storage & apps): Videos, Music, Pictures, Documents, APK files, Archives and Downloads, across all drives.
- **File operations**: copy, move, rename, delete, new folder, zip and unzip. Multi-select works with the remote.
  Large copies show progress and can be cancelled. Existing files are never overwritten (the copy is named `name (1).ext` instead).
- **Built-in player and viewer**: videos and music play in a Media3 (ExoPlayer) player, and photos open in a viewer with a slideshow.
  Many TVs ship without either app. "Open with…" still hands files to VLC, Kodi and so on. Sound formats the TV can't
  play are decoded with FFmpeg (Jellyfin's media3-ffmpeg-decoder, GPLv3); third-party licences ship in
  `app/src/main/assets/licenses/`.
- **APK sideloading**: open an `.apk` to install it. If needed, the app walks you through the "install unknown apps" setting.
- **App manager**: launch apps, back up an APK to `Download/Woodruff Family/Apps`, open App info, or uninstall.
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
| Any film or show | Hold OK / Menu | The title menu: Play or Resume, Start over, Watch together, Mark as watched… |
| Any film or show | Play/Pause | Play it straight away |
| Anywhere | Search (or mic) | Open Search and listen (on TVs that pass the button to apps) |
| Movies, TV Shows, Family Memories | Back | From far down a long list, glide back to the top first (the banner's Play, or the first poster or photo). Back again opens the side menu. A quick double Back goes straight to the side menu. Inside a show's folders or a sub-folder, Back goes up a folder. |
| Anywhere | Home, then Woodruff from the TV's app row | Back where you were: a film you were watching is still there, paused at its spot with the controls up, even if the TV closed Woodruff meanwhile to free memory; any screen is as you left it. |
| Home's side menu | Back | Leave Woodruff, keeping everything as it was, so it reopens at once (on Android 9–11 too). |

## Automatic updates

Woodruff checks this project's releases every hour (and each time it opens, at most every 10 minutes), downloads a newer version
and installs it with Android's package installer (which only accepts an update signed with the same key). A check that
finds nothing new costs almost nothing: Woodruff asks GitHub whether the list of releases changed since its last look.

- **Never under someone using it**: on Android 12 and newer, updates install by themselves as soon as nobody is using
  Woodruff: nothing playing in it, no key pressed anywhere in it for 2 minutes (title pages, menus, filters, search,
  the player, the photo viewer, the slideshow and the phone's remote all count), nothing under way that a restart
  would cut short (a copy to a USB drive, a Google Drive download, a Google Photos pick, a file coming from or going to
  the phone), and a paused film left alone for half an hour. With the TV's screen off nobody counts as watching,
  though a film that's playing is never interrupted.
- **Back where it was**: Woodruff then opens straight back into what was showing: its slideshow, the Command Center
  (with the same camera full screen), the same photo in the photo viewer, the film paused at its spot with the
  controls up (Back goes to the screen it was started from), or the screen that was up (Streaming in the same library
  with the same filter). Title pages and menus aren't put back; the screen under them is. A film paused under the TV's
  screensaver is there when the TV wakes. A Woodruff left for another app never pops up over it (a film left that way
  stays in Continue watching). If what was showing has gone meanwhile (a deleted film, an unplugged USB drive), the
  screen it was started from opens and says so. No notice appears: Settings → Updates says "Updated today
  at 3:12 AM" for a day. On Android 10 and newer, opening again by itself needs *Pop-ups over other apps* (Settings →
  Advanced); without it the TV stays on its launcher after an update. The phone connection comes back on its own.
- **TVs that ask first**: on Android 12 and newer the first update after installing from Downloader asks once, and
  some TVs may ask before every update; Android 11 and older always ask. Woodruff never pops that question up on its
  own: the update waits, and the next time someone opens Woodruff it's offered once, with *Install* already focused.
  It's offered when Woodruff is opened, or comes back after nobody was using it (its slideshow, the TV's screensaver,
  another app), never as someone comes back from a film, a photo or the doorbell's camera. *Later* holds it back for
  12 hours, and Settings → Updates installs it any time. Once Android's own *Update* screen has worked on a TV, later
  offers open it straight away, so it's one press. After installing, Woodruff opens again by itself. Settings →
  Updates says when a TV asks before each update. Keep updating this way rather than reinstalling from Downloader,
  which makes the next update ask once again.
- The TV asks once to let Woodruff Family *install unknown apps*. Some TVs (a Sony did) tell Woodruff it isn't
  allowed even while that setting shows *Allowed*. Woodruff then hands the update to Android's installer anyway,
  which asks if it needs to and otherwise gives its reason in plain words (Settings → Updates shows it as *last try*).
  A kids or restricted profile can't install apps from outside the Play Store at all: update from the main profile.
- Settings → Updates shows the version, checks now, turns automatic updates off, and under *This copy* shows when
  Woodruff was installed fresh, last updated, and by what.
- **Fast straight after an update**: each release carries a speed profile (`WoodruffFamily-api28.dm` and
  `WoodruffFamily-api31.dm`) that installs with it on Android 9 and newer, so Android prepares the app's busiest code
  while installing instead of over the following days. Settings → Advanced → *App code* shows how it's prepared.
- **Test TV** (Settings → Updates): turn it on for one TV. That TV installs each new version as soon as it's out.
  Every other TV only installs a version that was the newest for a whole day, however often new versions come: a run
  of releases only makes them wait longer, and a version that had to be replaced within its first day never reaches
  them. They go by release times, so this works best with the test TV in use. *Check for updates* skips the wait.
- **Undo a bad update**: Android won't install an older version over a newer one, so an earlier version is
  re-released with a new, higher number: GitHub → Actions → Build APK → *Run workflow*, with the commit to go
  back to. It's marked "rollback", so no TV waits a day for it.
- **A bad update repairs itself**: if a new version closes within 45 seconds of Woodruff opening (on any Android
  version; on Android 11 and newer also a crash in native code or a freeze, when the TV reports it), every start of
  Woodruff looks for a fixed version at once, then every few minutes. A start means the TV turning on, the phone
  connection coming back, or someone opening it. It takes any newer build without waiting for the test TV's day and
  installs it as soon as it's there, even while Woodruff is on screen (never over a film that's playing or a copy
  that's still running). What was on screen comes back afterwards. Publishing a rollback (above) is all it takes.
  After two such closes in a row, Woodruff opens to a plain recovery screen instead. It says what happened, looks for
  the fix, and offers *Install the fix* once it's ready (Android 12 and newer install it by themselves; Android 11 and
  older show Android's *Update* screen for one press). *Try opening again* opens Woodruff normally, and one more close
  brings the recovery screen straight back. *What happened* shows the crash summary. While the recovery screen is up,
  doorbell rings, phone messages and mail still pop up over it, as they do over other apps (with *Pop-ups over other
  apps* allowed; otherwise as Android notifications).
- **Crashes**: the next launch shows what went wrong in Notifications (the recovery screen says it instead, when it's
  up), and the phone app shows the full report under Settings → *Last Crash on This TV*, with Copy.

Each release is built with its build number as the version (release v2.0.N is version N), which is how the app
tells a newer release apart.

## How it moves

Everything moves the way tvOS does, and only by moving, sizing and fading layers: nothing is re-composed, laid out
or even drawn again on each frame of an animation, so the TV repaints just what moved (on Android, drawing anything
again repaints the whole screen, 20 to 40 ms on a TV's graphics chip), and a busy moment doesn't stall a motion that's
under way. What changes colour with the focus (a row's words, a button's fill) is drawn in both colours, cross-fading:

- **Lists** (Settings, the side menu, menus, Mail, Notifications, Apps, the filter panels) share one highlight that
  glides from the row you leave to the row you land on, following the list as it scrolls; holding an arrow keeps
  it gliding. The words on a row change colour as the highlight arrives.
- **Posters** grow and lift on focus; poster rows keep the focused poster at the row's leading edge and glide the
  row under it, like the Apple TV app. Lists and grids keep the focus at a steady spot as they scroll.
- **The backdrop and the banner** wait for you to stop: Streaming's large blurred backdrop changes only once no key
  has been pressed for 0.75 s, and never while its previous fade is still running. The featured banner moves on only
  after 12 s with no key presses and the banner fully in view. So moving across posters never starts a full-screen
  fade under the focus (`ui/motion/BrowseSettle.kt`).
- **Pop-ups** (every dialog, the doorbell's camera) zoom in from just larger and fade; the filter panels slide in
  from the right; a title's page and search grow in. Android runs these window animations itself, so they stay
  smooth even when the app is busy. The player, photos and the slideshow open and close the same way.
- **Screens** chosen in the side menu cross-fade with a slight scale; the menu glides in and out; Settings pages
  slide like iOS; notices and messages glide in.
- **Over a film**, the player's cards (Skip intro, Next episode, Up next, the doorbell's, its messages and menus) glide up into place and back down instead of popping; a notice over another app drops in from the
  top edge. Subtitles make way: when the controls, Skip intro / Next episode or a player message come up at the
  bottom, subtitles glide up to sit 12 dp above them (300 ms on the window curve) and back down when they go
  (180 ms); picture subtitles move too, and subtitles fade out during Up next. The loading ring zooms in like a pop-up
  and turns by its rotation alone. A camera opened full screen in the Command center grows in over the grid and its picture clears from
  black (a live picture can't fade itself); closing reverses it.

Every spring and duration is in `ui/motion/TvMotion.kt`; the gliding highlight is `ui/motion/FocusPlatter.kt`.

**TVs with Android's animations turned off** (Developer options → *Window / Transition animation scale* and
*Animator duration scale* set to *Animation off*, or Accessibility → *Remove animations*): the app keeps its own
motion anyway (`ui/motion/AppMotion.kt`), so the highlight, rows and cross-fades still glide, subtitles still make way
(on the display clock, `ViewGlide`) and the loading ring keeps turning; dialogs then glide in
from inside. Windows (dialogs leaving, the player and doorbell screens opening) can only glide with the TV's window
and transition animation scales on, so set those to 1x. Settings → Appearance → *Reduce motion* turns the app's
motion off: subtitles then move at once and the loading ring stands still.

## Smoothness report

To find out why moving around isn't smooth on a particular TV:

- Settings → Advanced → *Smoothness* on the TV sums up, screen by screen (title pages, menus, the filters and search
  each counted on their own), the frames drawn **while navigating** (within a second of a remote press, on the TV's
  remote or the phone's): how many were slow (over 25 ms), the stutters (over 50 ms) and what they
  were mostly spent on. Camera video and animations running by themselves are counted apart, so they neither
  water it down nor add to it.
- **OK** on that row, or **Record 3 Minutes** in the phone app (Settings → TVs → *Smoothness Report*), starts
  counting again and records the next 3 minutes in detail: whenever one piece of main-thread work (a message, or
  a remote key being handled) runs over 12 ms, its stack is sampled every 5 ms; long work is added up by kind, and
  garbage collection is counted. Nothing of this runs outside a recording.
- The phone page shows the whole report as text, with **Copy Report**: this TV (model, Android version, CPU,
  memory, display modes, animation scales, its graphics driver and whether it can repaint just what changed,
  whether the app's code is compiled), each screen's navigating and idle frames (late, slow, stutters,
  p50/p90/p99, and where late frames spent their time), how long remote keys take, and the recording's hot spots. Release builds keep class and method names and line numbers
  (`-dontobfuscate`), so the stack frames are readable. Also at `GET /api/diagnostics` (plain text) and
  `POST /api/diagnostics/record`, with the pairing key like the other `/api` routes.
- When cameras streamed since Woodruff started, the report ends with **Camera starts**: this run's camera starts,
  stalls and reconnects, and the last 8 starts step by step, or why one had no picture. The steps are finding this
  TV's network addresses (and why that wait ended: a public address for every part of the offer, half a second after
  a local one, all answered, or the 3 s limit), Google's answer, the link to the camera, and its first picture.

## Install on your TV

1. Download **WoodruffFamily.apk** from the latest release:
   https://github.com/Blakew316/woodrufffiles/releases/latest/download/WoodruffFamily.apk
   (The older `WoodruffFilms.apk`, `WoodruffPictures.apk` and `WoodruffShows.apk` links still work and get the same
   latest build.)
   It's signed with a debug key so it can be sideloaded.
2. Get it onto the TV with any of these:
   - type the link above into the **Downloader** app on the TV (easiest, no computer needed)
   - `adb connect <tv-ip>` then `adb install WoodruffFamily.apk`
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
Once a TV has the keys, it can also be signed in from the phone app it serves (see
[Keys are entered once](#keys-are-entered-once)).

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
- **7-day re-sign-in:** while the consent screen is in "Testing" mode, Google ends the sign-in after 7 days.
  The TV then says so once, with a quiet notice ("Sign in to Google Drive again"). Settings → Accounts shows
  **Sign-in ended** in amber, and Streaming shows the code again under **Sign in to Google Drive again**; your library,
  Continue watching and ✓s are kept. A slow or missing network never asks you to sign in again: that only happens when
  Google has really ended the sign-in. To stop the weekly limit, click **Publish app** on the consent screen.
  An unverified published app shows Google's warning screen and is capped at 100 users, which is fine for a household.
- **Offline:** a TV that wakes before its Wi-Fi is back, or can't reach Google for a while, keeps showing what it
  saved. After a few seconds, Movies and TV Shows say **Offline** (or **Can't reach Google**) at the end of their search
  field. Home's calendar and cameras show their last state (cameras with an OFFLINE badge). Everything catches up by
  itself when the connection returns, saying **Back online** for a moment. With nothing saved yet, Streaming shows a
  calm page with **Try again**.
- **Keys not accepted:** if Google stops accepting the Client ID or secret (changed or deleted in Google Cloud),
  Streaming shows **Check the Google keys** with **Open Keys**, and Settings → Accounts says **Keys not accepted**. Fix
  the keys in the phone app (Settings → Connections → Google Drive) or on the TV, then sign in again. If the Google
  Drive API isn't turned on for the keys, Streaming says so and where to turn it on.
- **Where things are stored:** the client secret and sign-in tokens live only in the app's private storage on the TV.
  The sign-out button on the Drive screen revokes access, or you can revoke it at https://myaccount.google.com/permissions.
- **Fire TV works:** Google Play services is no longer needed, so Google Drive also works on Fire TV and other boxes.
- **Sharing Continue watching between TVs:** add `.../auth/drive.appdata` under **Data access**
  (Google Auth Platform → Data access → Add or remove scopes), then on each TV that signed in before, open
  Settings → Accounts → **Sign in to Google Drive again** and scan the code with your phone, leaving every box ticked.
  Use the same Google account on every TV. The library stays as it is.

## Keys are entered once

The keys (**TMDB key**, **Google Client ID and secret**, **Cameras project ID**) are entered once, on any TV or from
the phone, and every linked TV gets them (see *Several TVs*):

- **They stay through updates.** Each TV keeps its keys encrypted in its own storage, and updating Woodruff (by
  itself, or from Downloader without uninstalling) never touches them. Only uninstalling Woodruff, or *Clear data* /
  *Clear storage* in Android's settings, erases them. If a TV's Keystore can't find their key for a moment (some TVs,
  just after turning on), nothing is lost: they read as unset until it's back, and are only reset if it stays missing
  (three times, at least 5 minutes apart).
- **A new or reset TV gets them by itself:** link it to the hub (Settings → TVs & phones → Other TVs → Add another TV)
  and the keys arrive within a few seconds. A TV that was off gets them within 5 minutes of coming back (the hub checks
  its linked TVs every 5 minutes while its phone connection runs).
- **The newest wins:** a key changed on any TV replaces the older one everywhere; removing the TMDB key, the Google
  keys or the cameras project on one TV removes it from all of them. Google keys that differ between TVs from before
  this version are left as they are (replacing them would sign that TV out); entering them again shares them.
- **Sign-ins are once per TV, and the phone can do them:** Drive, Photos, Calendar and the cameras are each approved
  on each TV, by scanning a QR code on the TV with the phone, or from that TV's own phone app instead: Settings →
  Connections → Google Drive → *Sign In* (or Cameras → *Connect Your Cameras*), or Settings → TVs → that TV. The TV
  finishes by itself with no screen open; this works on the home Wi-Fi only. Sign-ins stay through updates too, and a
  TV that's only offline keeps them and never asks for them again. When Google really ends a sign-in, the family's
  phones are told once: a banner in the app that opens the TV's sign-in, and a Lock Screen alert (ntfy) for phones
  that have it on. The hub also tells its phones about linked TVs, and the same sign-in ending on several TVs within
  10 minutes is one message. If a TV asks to sign in to Google again every week, that's Google's 7-day limit for apps
  in "Testing" mode: click **Publish app** on the consent screen (see the Drive setup).
- **Not built into the APK:** releases are public downloads, so anything built into the APK could be read by anyone.

TVs share keys from version 2.0.158 (2.0.157 shared only the TMDB key).

## Movie and show posters

Movies and TV Shows use poster tiles. Artwork follows the Plex/Kodi convention, no API key needed:
- A movie or show **folder**: put `poster.jpg` (or `folder.jpg` / `cover.jpg`) inside it.
- A **loose movie file**: put an image with the same name next to it, e.g. `Inception.mp4` + `Inception.jpg`.
Those images are used as posters and hidden from the grid.

**Automatic posters (TMDB):** add a free API key from [themoviedb.org](https://www.themoviedb.org/settings/api)
(the API Key or the Read Access Token) in **Settings → Keys & sign-out → TMDB key**, or in the phone's Settings tab. Movies,
shows and seasons without their own art then get official posters, matched from names like `Inception (2010).mkv`,
`The.Office.S01.1080p` or `Season 2`. Lookups are cached on the TV. A new key only looks posters and details up
again, without listing Drive again; entering the same key again changes nothing. Your own art always wins. Without
either, the video's Drive preview frame (or a title card for folders) is shown. Movies and TV Shows also need the key
for their Netflix-style page: the featured banner, *Top rated* and the genre rows all come from TMDB.
The key is checked with TMDB when it's entered, on the TV or the phone (ten seconds at most). A key TMDB doesn't
accept isn't saved, and you're told why. If TMDB can't be reached just then, the key is saved and checked when posters
next load; Settings → Keys & sign-out shows **Not accepted** if TMDB turns it down then.

**One key for every TV:** enter it once, on any TV or from the phone, and it's copied to every linked TV. See
[Keys are entered once](#keys-are-entered-once).

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
4. Copy the **Project ID** into the TV (Command center → Enter Project ID) or the phone (Settings → Connections →
   Cameras).
5. On the TV, open **Command center** and scan the QR code (or, in the phone app that TV serves, Settings →
   Connections → Cameras → *Connect Your Cameras*). On Google's page, pick your home and switch on every camera and doorbell.

Limits: Google ends each live view after 5 minutes, and the app renews it every 4 (alongside the frame watch described
under *Cameras on Home*). Many TVs can decode only a few
videos at once, so the grid streams the first 4 cameras and the rest start when selected.

## Build locally

You need JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

The phone app has Playwright checks that run against a mock TV, for example for the Watch tab:

```sh
MOCK_LOG=/tmp/mock.log python3 tools/gallery/mock_server.py app/src/main/assets/web &
node tools/gallery/phone_watch_check.mjs
```

The other `tools/gallery/phone_*_check.mjs` scripts run the same way. `phone.mjs` and `mock_server.py` read
`MOCK_PORT` to use a port other than 8765.

## Security notes

- **Signing key in the repo:** `app/woodruff-shows.keystore` is committed so every build has the same signature (so updates install over each other).
  The catch is that anyone with this repo could sign an APK that Android accepts as an update to this app, so only install builds from this repository's releases.
  Before publishing anywhere, move the key to a GitHub secret.
- Google Drive access to the family's files is read-only (`drive.readonly`): the app can't change, add or delete any of
  them. The only place it writes is its own hidden app folder (`drive.appdata`, which doesn't show in Drive), for sharing
  Continue watching between TVs. After each TV Shows refresh, Woodruff asks Drive for the videos created in the last
  30 days (names, folders and dates only) and looks up the folders above each to find its show and season; the result
  is saved on the TV and deleted at sign-out. The OAuth client secret and tokens are kept in the app's private storage.
  The phone sign-in uses PKCE and a one-time `state`, and the relay page only forwards to private network addresses.
  The TV takes an approval only for a sign-in it started itself, from its own connect screen or from its phone app.

- The phone connection (the TV's small web server) starts by itself when the TV turns on, unless Settings → TVs &
  phones → *Start when the TV turns on* is off; a notification shows while it's running.
- Every request needs a random 12-character key. The key is in the QR code and URL shown on the TV, and it's kept, so
  phones stay paired; Settings → Advanced → *Reset phone pairing* replaces it, and every phone scans the new code.
- The server can only reach the shared storage volumes, never the app's private data.
  Paths are canonicalised, so `..` and symlink tricks are rejected.
- Traffic is plain HTTP on your local network, so anyone on the network who can see the URL can use it.
  Use it on a network you trust.
- The pairing key stays in the phone page's address so a Home Screen copy keeps working; the TV answers that address
  directly, with a cookie, instead of redirecting first. Treat that link like a password. Every route behind the
  phone app's Watch tab, TV pages and Sign In needs that TV's pairing key, like the rest of the phone app. Posters
  stored in Google Drive are fetched by the TV itself, only for files it has listed and only from Google's servers, so
  the Google sign-in never reaches the phone.
- Lock-screen alerts go through the public ntfy.sh server. Each phone's topic is random, but anyone who learns it can read
  the announcements sent to that phone, so don't announce secrets. Nothing Woodruff pushes through ntfy carries a link
  or a key.
- A linked TV gets its own link key, not the phone pairing key: it can only read status and the TV's health report
  (keys only marked saved or not, never their values), see the screen, press remote keys, type into the TV's open text
  box (the same as pressing its keys; it's how the phone's keyboard reaches a linked TV), pass on the phone's playback
  controls (Play/Pause, −10 s, +30 s, Next: no more than its remote keys could do), ask it to check for an update,
  show messages and doorbell alerts, and read or update the shared settings (TMDB, Google's Client ID and secret, the
  cameras project, the doorbell's Pub/Sub subscription name and the person/motion choice). It can never touch files,
  mail, sign-ins (it can't start one) or any other setting. The subscription name isn't a credential: using it needs a
  Google sign-in with Pub/Sub access. **Stop other TVs seeing this one** in Settings revokes every link.
  Linking codes last 10 minutes, work once, and die after 5 wrong tries.
- A linked TV turns a hub away only when its own saved link key says no: if it can't read its keys for a moment after
  power-on, it never makes a new link key, so hubs don't show *Link again* for a TV that was never reset. A TV's id and
  name are kept in plain app settings (they aren't secrets), so a Keystore that loses its key never makes a TV look
  like a new one. When you link a TV, the list of TVs found shows their names only.
- Crash reports that leave a TV (in its health report, on the phone's *Last Crash* page and in *Copy Crash Report*)
  have addresses, keys, tokens and email addresses taken out, and *Copy Health Report* carries no addresses.
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
