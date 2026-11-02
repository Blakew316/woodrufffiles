"""Stand-in for the TV's phone-connection server, with sample data, so the phone app can be
screenshotted in CI for the UI gallery. Serves app/src/main/assets/web (pass that folder).

It keeps changes in memory (settings, mail accounts, Family Memories) like the TV would, serves a few nested
folders and two storages, and, with MOCK_LOG=<file>, appends every API request to that file as one
JSON line ({"method", "path", "query", "body"}) so tests can check what the page sent.
Family Memories: sample photos and videos, each picture drawn here (a landscape, in SVG); what the phone adds is
kept and served back as it was sent, and GET /__mock/memories lists what was added (name, taken, bytes and, for a
JPEG, its size) so tests can check what the page sent.
Test-only controls, never used by the gallery: POST /__mock/inbox {title, body, from} queues an
announcement for the phone, POST /__mock/crash {text} sets the "last crash", POST /__mock/field {field}
sets (or clears, with null) the TV text box the keyboard types into, and POST /__mock/set {settings, tvs, phones}
changes the sample settings (merged), the linked TVs or the phones (replaced); POST /__mock/reset puts the TVs back.
Remote keys are checked like the TV does; the Den TV has no Netflix, so a test can see that error.
TV health: four TVs, as in the TV's own health gallery: Living Room (this TV, all good), Den (needs attention: its
Drive sign-in ended and the last update didn't install), Kitchen (off for two hours) and Bedroom (reset: it turns down
its link). /api/tvs/health answers each like a TV would (409 with the last report for Kitchen and Bedroom);
/api/tvs/relink takes the code 123456 (then Bedroom is linked again and all good).
GET /api/diagnostics answers with a sample Smoothness Report (and X-Recording-Left, like the TV); POST
/api/diagnostics/record starts a 3-minute "recording" here, so the phone page's countdown can be seen."""
import json, math, os, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

WEB = sys.argv[1]
LOG = os.environ.get('MOCK_LOG')
ROOT = '/storage/emulated/0'
USB = '/storage/1A2B-3C4D'


def entry(folder, name, is_dir=False, size=0, modified=1761700000000):
    return {'name': name, 'path': folder + '/' + name, 'dir': is_dir, 'size': size, 'modified': modified}


def folder(path, items):
    return [entry(path, n, d, s) for n, d, s in items]


TREE = {
    ROOT: folder(ROOT, [
        ('DCIM', True, 0), ('Movies', True, 0), ('Pictures', True, 0), ('Music', True, 0), ('Download', True, 0),
        ('Documents', True, 0), ('Projects', True, 0),
        ('Family vacation.mp4', False, 2_340_000_000), ('Beach sunset.jpg', False, 4_200_000),
        ('Road trip playlist.m4a', False, 9_800_000), ('Recipes.pdf', False, 1_200_000),
        ('Backup.zip', False, 640_000_000), ('Woodruff Family.apk', False, 22_000_000), ('notes.dat', False, 4_096),
    ]),
    ROOT + '/DCIM': folder(ROOT + '/DCIM', [('Camera', True, 0), ('Screenshots', True, 0)]),
    ROOT + '/DCIM/Camera': folder(ROOT + '/DCIM/Camera', [
        ('IMG_%04d.jpg' % (2100 + i), False, 2_400_000 + i * 131_000) for i in range(24)
    ] + [('VID_2026_0712.mp4', False, 412_000_000)]),
    ROOT + '/DCIM/Screenshots': [],
    ROOT + '/Movies': folder(ROOT + '/Movies', [
        ('Midnight Harbor (2024).mkv', False, 6_100_000_000), ('Paper Planes (2019).mp4', False, 2_900_000_000),
    ]),
    ROOT + '/Music': folder(ROOT + '/Music', [('Road trip', True, 0), ('Lullabies.m4a', False, 7_300_000)]),
    USB: folder(USB, [('Home movies', True, 0), ('Wedding.mov', False, 3_800_000_000), ('Taxes 2025.pdf', False, 880_000)]),
    USB + '/Home movies': folder(USB + '/Home movies', [('1998 Christmas.mp4', False, 1_100_000_000)]),
}
ROOTS = [{'name': 'Internal storage', 'path': ROOT}, {'name': 'USB drive', 'path': USB}]

PHOTOS = ROOT + '/Pictures/Google Photos'
VIDEOS = ROOT + '/Movies/Google Photos'
DAY = 86_400_000


def _memories():
    """30 photos and 4 videos, a few days apart, newest first (as the TV lists them)."""
    newest = int(time.mktime((2026, 10, 26, 16, 42, 0, 0, 0, -1)) * 1000)
    out = []
    for i in range(34):
        video = i % 9 == 4
        name = ('VID_%04d.mov' if video else 'IMG_%04d.jpg') % (4120 - i)
        out.append({'path': (VIDEOS if video else PHOTOS) + '/' + name, 'name': name, 'video': video,
                    'taken': newest - i * 3 * DAY - (i % 5) * 3_600_000, 'size': 840_000 + i * 17_000, 'v': 1761500000000 + i})
    return out


MEMORIES = _memories()
ADDED = {}      # path -> (bytes, content type): what the phone sent, served back as it was
MEM_LOG = []    # what the phone added, for tests
PALETTES = [('#ffb36b', '#ff6f61', '#2f4858', '#4f6d7a'), ('#9be7ff', '#4ea8de', '#2d6a4f', '#52b788'),
            ('#ffd6a5', '#f08080', '#6d597a', '#b56576'), ('#caf0f8', '#90e0ef', '#0077b6', '#023e8a'),
            ('#fde68a', '#f59e0b', '#7c2d12', '#b45309'), ('#e0c3fc', '#8ec5fc', '#3a506b', '#5bc0be')]


def landscape(i, w, h):
    """A sample photo: sky, sun and two hills, in one of a few palettes; portrait for every third one."""
    a, b, c, d = PALETTES[i % len(PALETTES)]
    sx, sy, r = w * (0.25 + (i * 37 % 50) / 100), h * 0.32, min(w, h) * 0.09
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">'
            '<defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient></defs>'
            '<rect width="%d" height="%d" fill="url(#s)"/><circle cx="%.0f" cy="%.0f" r="%.0f" fill="#fff8e7" opacity=".9"/>'
            '<path d="M0 %.0f Q %.0f %.0f %.0f %.0f T %d %.0f V %d H 0 Z" fill="%s"/>'
            '<path d="M0 %.0f Q %.0f %.0f %.0f %.0f T %d %.0f V %d H 0 Z" fill="%s"/></svg>') % (
        w, h, w, h, a, b, w, h, sx, sy, r,
        h * 0.62, w * 0.25, h * 0.5, w * 0.5, h * 0.64, w, h * 0.6, h, d,
        h * 0.78, w * 0.3, h * 0.66, w * 0.6, h * 0.8, w, h * 0.74, h, c)


def jpeg_size(b):
    """A JPEG's width and height (from its frame header); None for anything else."""
    if b[:2] != b'\xff\xd8':
        return None
    i = 2
    while i + 9 < len(b) and b[i] == 0xFF:
        if b[i + 1] in (0xC0, 0xC1, 0xC2):
            return [int.from_bytes(b[i + 7:i + 9], 'big'), int.from_bytes(b[i + 5:i + 7], 'big')]
        i += 2 + int.from_bytes(b[i + 2:i + 4], 'big')
    return None


def memories_json():
    return {'items': MEMORIES, 'folder': PHOTOS, 'fit': [1920, 1080], 'slideshow': True}
PHONES = [
    {'id': 'p-blake', 'name': "Blake's iPhone", 'alerts': True},
    {'id': 'p-mom', 'name': "Mom's iPhone", 'alerts': True},
    {'id': 'p-kids', 'name': 'Kids iPad', 'alerts': False},
]
SETTINGS = {
    'notifyUrl': 'http://192.168.1.20:8080/api/notify?k=…&tvs=all',
    'shareUrl': 'http://192.168.1.20:8080/api/share?k=…',
    'google': {'clientId': '1234-example.apps.googleusercontent.com', 'hasSecret': True, 'connected': True,
               'redirectUri': 'https://blakew316.github.io/woodrufffiles/oauth.html'},
    'nest': {'project': '1a2b3c4d-5e6f-7a8b-9c0d', 'connected': True, 'doorbell': '', 'doorbellStatus': '',
             'person': True, 'motion': False},
    'tmdb': True,
    'weather': 'Austin, TX',
    'version': '2.0.128',
    'crash': '',
    'awayUrl': 'http://100.101.102.103:8080/?k=…',
    'mail': [{'id': 'm1', 'email': 'family@icloud.com', 'provider': 'iCloud Mail'}],
    'providers': [
        {'id': 'ICLOUD', 'label': 'iCloud Mail', 'help': 'Create an app-specific password at account.apple.com.', 'custom': False},
        {'id': 'GMAIL', 'label': 'Gmail', 'help': 'Turn on 2-Step Verification, then create an app password at myaccount.google.com.', 'custom': False},
        {'id': 'CUSTOM', 'label': 'Other (IMAP)', 'help': 'Use the IMAP server and password from your mail provider.', 'custom': True},
    ],
}
FIELD = {'field': {'id': 1, 'label': 'Search file names', 'secret': False, 'multiline': False, 'text': 'vacation'}}
INBOX = []
MIN, HOUR = 60_000, 3_600_000
TV_STATE = {'relinked': False, 'playing': {'this': True, 'b': True}}
TVS_SET = {'tvs': None}   # /__mock/set {tvs}: replaces the list until /__mock/reset


def _now():
    return int(time.time() * 1000)


def _health(status, count=0, first=None):
    return {'status': status, 'count': count, 'first': first}


def tvs_json():
    """The four TVs, as /api/tvs answers (seen times are relative to now)."""
    if TVS_SET['tvs'] is not None:
        return {'tvs': TVS_SET['tvs']}
    now, p = _now(), TV_STATE['playing']
    bedroom_ok = TV_STATE['relinked']
    return {'tvs': [
        {'id': 'this', 'name': 'Living Room', 'online': True, 'onScreen': True, 'version': '2.0.171', 'seen': None, 'link': 'ok',
         'health': _health('good'),
         'playing': {'title': 'Midnight Harbor', 'app': 'Woodruff Family', 'playing': p['this'], 'position': 2710000, 'duration': 6840000,
                     'can': ['playpause', 'back10', 'fwd30', 'next']}},
        {'id': 'b', 'name': 'Den', 'online': True, 'onScreen': False, 'version': '2.0.171', 'seen': now, 'link': 'ok',
         'health': _health('attention', 2, 'Google Drive’s sign-in ended'),
         'playing': {'title': 'Backyard Science · S2 E4', 'app': 'YouTube', 'playing': p['b'], 'position': 410000, 'duration': 1320000, 'can': ['playpause']}},
        {'id': 'c', 'name': 'Kitchen', 'online': False, 'onScreen': False, 'version': '2.0.171', 'seen': now - 2 * HOUR, 'link': 'ok',
         'health': _health('unknown'), 'playing': None},
        {'id': 'd', 'name': 'Bedroom', 'online': bedroom_ok, 'onScreen': False, 'version': '2.0.171', 'seen': now if bedroom_ok else now - 26 * HOUR,
         'link': 'ok' if bedroom_ok else 'refused', 'health': _health('good' if bedroom_ok else 'unknown'), 'playing': None},
    ]}


def _clock(ms):
    return time.strftime('%I:%M %p', time.localtime(ms / 1000)).lstrip('0')


def _date(ms):
    return time.strftime('%b ', time.localtime(ms / 1000)) + str(time.localtime(ms / 1000).tm_mday)


def _moment(ms):
    """As the TV writes a moment: today the time, then "yesterday", then the date."""
    a, b = time.localtime(ms / 1000), time.localtime()
    if (a.tm_year, a.tm_yday) == (b.tm_year, b.tm_yday):
        return _clock(ms)
    if (_now() - ms) < 2 * 24 * HOUR and a.tm_yday == b.tm_yday - 1:
        return 'yesterday'
    return _date(ms)


SIGNIN_WORDS = {'signedIn': ('Signed in', 'Connected'), 'offline': ('Offline', 'Offline'), 'unreachable': ('Can’t reach Google', 'Can’t reach Google'),
                'expired': ('Sign-in ended', 'Sign-in ended'), 'keysRejected': ('Keys not accepted', 'Keys not accepted'),
                'notSignedIn': ('Not signed in', 'Not connected'), 'notSetUp': ('Not set up', 'Not connected')}
CRASH_REPORT = ('Version 2.0.170 · {when} · thread main\njava.lang.IllegalStateException: The player was released\n'
                '\tat androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:2861)\n'
                '\tat com.woodruff.tvexplorer.player.PlayerActivity.onStop(PlayerActivity.kt:410)\n'
                '\tat android.app.Activity.performStop(Activity.java:8501)\n\tat android.app.ActivityThread.callActivityOnStop(ActivityThread.java:5208)')


def health_report(name, at, attention=(), states=None, doorbell=('watching', 'Watching', 'Doorbell presses pop up on this TV, even when the other TVs are off.'),
                  updates=('upToDate', 'Up to date', False), last_try=None, crash_at=None, closed=('Nothing unusual', False, None), android=31):
    """A TV's /api/health report, every word as the TV's own builder writes it."""
    states = states or {}
    sign_ins = {}
    for g in ('drive', 'photos', 'calendar', 'cameras'):
        st = states.get(g, 'signedIn')
        sign_ins[g] = {'state': st, 'text': SIGNIN_WORDS[st][0 if g == 'drive' else 1], 'attention': st in ('expired', 'keysRejected')}
        if st == 'expired':
            sign_ins[g]['since'] = at - 24 * HOUR
    sign_ins['doorbell'] = {'state': doorbell[0], 'text': doorbell[1], 'attention': doorbell[0] == 'failing', 'detail': doorbell[2]}
    items = [dict(zip(('id', 'words', 'detail', 'fix'), a[:4]), **({'grant': a[4]} if len(a) > 4 else {})) for a in attention]
    words = [i['words'] for i in items]
    frag = [w if w.split(' ')[0] in ('Google', 'Woodruff', 'Android') else w[0].lower() + w[1:] for w in words]
    sentence = 'Everything’s working.' if not frag else (frag[0] if len(frag) == 1 else ', '.join(frag[:-1]) + ', and ' + frag[-1])
    sentence = sentence[0].upper() + sentence[1:] + ('' if not frag else '.')
    crash = None
    if crash_at:
        crash = {'at': crash_at, 'text': ('Yesterday, ' + _clock(crash_at)) if _moment(crash_at) == 'yesterday' else
                 (_clock(crash_at) if _moment(crash_at) == _clock(crash_at) else _date(crash_at) + ', ' + _clock(crash_at)),
                 'attention': _now() - crash_at < 7 * 24 * HOUR, 'summary': 'IllegalStateException in the player',
                 'lines': 'Version 2.0.170 · ' + _date(crash_at) + ', ' + _clock(crash_at) + ' · thread main\njava.lang.IllegalStateException: The player was released\nat androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:2861)',
                 'report': CRASH_REPORT.format(when=_date(crash_at) + ', ' + _clock(crash_at))}
    return {
        'id': name.lower().replace(' ', '-'), 'name': name, 'at': at, 'android': android, 'version': '2.0.171',
        'status': 'attention' if items else 'good', 'sentence': sentence, 'attention': items,
        'installedBy': {'summary': 'Installed fresh Oct 12, 9:10 AM · updated Nov 1, 3:02 AM by Woodruff itself', 'by': 'Woodruff itself',
                        'firstInstall': 1760278200000, 'lastUpdate': 1761984120000},
        'updates': {'step': updates[0], 'text': updates[1], 'attention': updates[2], 'auto': True, 'testTv': False},
        'lastUpdateTry': last_try,
        'installPermission': {'allowed': True, 'text': 'Allowed', 'attention': False},
        'signIns': sign_ins,
        'lastCrash': crash,
        'closedByAndroid': {'text': closed[0], 'attention': closed[1], 'detail': closed[2]} if android >= 30 else None,
        'freeSpace': {'free': 13314398618, 'text': '12.4 GB free', 'attention': False},
        'keys': {'text': 'All saved', 'googleClientId': True, 'googleClientSecret': True, 'camerasProject': True, 'tmdb': True},
        'uptime': 2 * 24 * HOUR + 3 * HOUR,
    }


THROUGH = ('through', 'Through Living Room', 'This TV hears the doorbell from Living Room while that TV is on. Connect the cameras on this TV to hear it on its own.')


def tv_health(tv_id):
    """(status, body) for /api/tvs/health?id=…, as the hub answers."""
    now = _now()
    if tv_id == 'this':
        low = now - 11 * HOUR
        return 200, health_report('Living Room', now, closed=('Low memory · ' + _moment(low), False, _date(low) + ', ' + _clock(low)))
    if tv_id == 'b':
        r = health_report('Den', now - 3 * MIN, attention=[
            ('signin.drive', 'Google Drive’s sign-in ended', 'Google ended this sign-in. On Den: open Streaming and scan the code with your phone.', 'signin', 'drive'),
            ('update.failed', 'The last update didn’t install', 'The package installer said no.', 'update'),
        ], states={'drive': 'expired', 'calendar': 'notSignedIn', 'cameras': 'unreachable'}, doorbell=THROUGH,
            updates=('ready', '2.0.172 ready', False), last_try={'text': 'Didn’t install', 'reason': 'The package installer said no.'},
            crash_at=now - 12 * 24 * HOUR)
        r['via'] = 'Living Room'
        return 200, r
    if tv_id == 'c':
        last = health_report('Kitchen', now - 2 * HOUR, doorbell=THROUGH)
        return 409, {'status': 'off', 'message': 'Off or asleep. These are the last things it reported.', 'version': '2.0.171',
                     'seen': now - 2 * HOUR, 'last': last, 'lastAt': now - 2 * HOUR}
    if tv_id == 'd':
        if TV_STATE['relinked']:
            r = health_report('Bedroom', now, doorbell=THROUGH)
            r['via'] = 'Living Room'
            return 200, r
        last = health_report('Bedroom', now - 26 * HOUR, doorbell=THROUGH)
        return 409, {'status': 'refused', 'message': '', 'version': '2.0.171', 'seen': now - 26 * HOUR, 'last': last, 'lastAt': now - 26 * HOUR}
    return 400, 'Unknown TV'

# The phone's Smoothness Report (Settings › TVs): what a TV sends after a 3-minute recording, made by the
# app's own report code from realistic numbers. {recording} is where it stands: see diagnostics().
SMOOTHNESS = """Woodruff Family · smoothness report
Made Oct 29, 2:05 PM
App 2.0.143 (build 143) · up 38 min
Sony BRAVIA 4K VH2 (BRAVIA_VH2)
Android 12 (API 31)
CPU: 4 cores, 4 × 1.80 GHz · MediaTek MT5895
RAM: 2.8 GB, 1.1 GB free · low-RAM: no
Java heap: 61 MB used of 256 MB
Display: 3840×2160 @ 60 Hz (app draws 1920×1080)
 also: 3840×2160 @ 59.94, 50, 30, 25, 24, 23.98 Hz
       1920×1080 @ 60, 50 Hz
Animation scales: window 1×, transition 1×, animator 1×
Graphics: ARM · repaints only what changed
App code: compiled for speed

{recording}
Navigating: drawn within 1 s of a remote key.
Idle: the rest (video, animations alone).
Late: past its deadline (set by Android).
Slow: over 25 ms. Stutter: over 50 ms.
Late frames, average ms in each phase:
 wait   waiting to start (main thread busy)
 input  input batched into the frame
 anim   animation and composition
 layout measure and layout
 draw   recording the drawing
 sync   uploading pictures
 gfx    issuing commands, swapping buffers
 RT     queued behind the render thread
 GPU    the graphics chip's own time

== Home ==
Navigating: 2,140 frames
 late 28% · slow 18% · 45 stutters
 ms p50 13 · p90 33 · p99 85 · max 106
 late: wait 12.8 · input 0.0 · anim 9.6
  layout 1.6 · draw 3.2 · sync 1.1
  gfx 2.1 · RT 1.6 · GPU 8.0
Idle: 4,410 frames
 late 2% · slow 1% · no stutters
 ms p50 9 · p90 13 · p99 29 · max 44
 late: wait 2.9 · input 0.0 · anim 4.8
  layout 0.0 · draw 3.6 · sync 5.8
  gfx 4.8 · RT 7.0 · GPU 9.7

== Movies ==
Navigating: 1,630 frames
 late 27% · slow 16% · 24 stutters
 ms p50 13 · p90 31 · p99 67 · max 100
 late: wait 12.1 · input 0.0 · anim 9.1
  layout 1.5 · draw 3.0 · sync 1.0
  gfx 2.0 · RT 1.5 · GPU 7.6
Idle: 220 frames
 late 0% · slow 0% · no stutters
 ms p50 9 · p90 13 · p99 13 · max 29
 late: wait 2.9 · input 0.0 · anim 4.8
  layout 0.0 · draw 3.6 · sync 5.8
  gfx 4.8 · RT 7.0 · GPU 9.7

== Settings ==
Navigating: 610 frames
 late 16% · slow 10% · 12 stutters
 ms p50 12 · p90 26 · p99 82 · max 105
 late: wait 2.9 · input 0.0 · anim 10.3
  layout 1.7 · draw 3.4 · sync 1.1
  gfx 2.3 · RT 12.6 · GPU 8.6
Idle: 40 frames
 late 0% · slow 0% · no stutters
 ms p50 9 · p90 12 · p99 13 · max 13
 late: none

== Command_center ==
Navigating: 320 frames
 late 5% · slow 3% · 2 stutters
 ms p50 10 · p90 15 · p99 35 · max 67
 late: wait 2.7 · input 0.0 · anim 9.7
  layout 1.6 · draw 3.2 · sync 1.1
  gfx 2.1 · RT 11.8 · GPU 8.1
Idle: 5,100 frames
 late 2% · slow 1% · no stutters
 ms p50 9 · p90 13 · p99 32 · max 44
 late: wait 3.1 · input 0.0 · anim 5.1
  layout 0.0 · draw 3.8 · sync 6.1
  gfx 5.1 · RT 7.4 · GPU 10.2

== Remote keys ==
213 presses (and held repeats) handled
 ms p50 5 · p90 37 · p99 98 · max 107
 over 16 ms: 42 · over 50 ms: 7
 (A key's time shows as "wait" in the next frame.)

== Hot spots ==
Main thread busy over 12 ms, sampled every
5 ms: 168 samples ≈ 840 ms.
By Woodruff code (with the 2 calls above it):
1. 58 samples ≈ 290 ms
   AndroidParagraph.<init>:140
   ParagraphLayoutCache.layoutText:381
   LibraryRowsKt.PosterCard:412
2. 41 samples ≈ 205 ms
   ComposerImpl.startGroup (Composer.kt:1500)
   ComposerImpl.startRestartGroup (Composer.kt:2210)
   HomeScreenKt.CameraTile:733
3. 33 samples ≈ 165 ms
   BitmapFactory.decodeStreamInternal:790
   BitmapFactory.decodeStream:765
   BackdropKt.loadBackdrop:96
4. 22 samples ≈ 110 ms
   FocusOwnerImpl.moveFocus:302
   AndroidComposeView.dispatchKeyEvent:1210
   MainActivity.dispatchKeyEvent:122
5. 14 samples ≈ 70 ms
   (no Woodruff code on the stack)
By top of stack:
1. 58 samples ≈ 290 ms
   TextLayout.<init>:238
   AndroidParagraph.<init>:140
   ParagraphLayoutCache.layoutText:381
2. 41 samples ≈ 205 ms
   ComposerImpl.startGroup (Composer.kt:1500)
   ComposerImpl.startRestartGroup (Composer.kt:2210)
   HomeScreenKt.CameraTile:733
3. 33 samples ≈ 165 ms
   BitmapFactory.nativeDecodeStream (native)
   BitmapFactory.decodeStreamInternal:790
   BitmapFactory.decodeStream:765
4. 22 samples ≈ 110 ms
   FocusOwnerImpl.moveFocus:302
   AndroidComposeView.dispatchKeyEvent:1210
   MainActivity.dispatchKeyEvent:122
5. 14 samples ≈ 70 ms
   VMRuntime.trackExternalAllocation (native)
   Bitmap.nativeCreate (native)
   Bitmap.createBitmap:1113

== Long main-thread work (over 12 ms) ==
1. 41 times, 1,394 ms, longest 54 ms
   Choreographer$FrameHandler: Choreographer$FrameDisplayEventReceiver
2. 12 times, 420 ms, longest 57 ms
   Remote key (MainActivity.dispatchKeyEvent)
3. 5 times, 95 ms, longest 25 ms
   Handler: AndroidUiDispatcher$dispatchCallback$1
4. 3 times, 87 ms, longest 38 ms
   Handler: DispatchedContinuation[Dispatchers.Main, Continuation at HomeScreenKt$HomeScreen$2$1.invokeSuspend(HomeScreen.kt:214)]

== Garbage collection (during the recording) ==
14 collections, 212 ms
2 blocking, 61 ms
"""
RECORD_S = 180
DIAG = {'started': 0.0}


def diagnostics():
    """The report as it stands, and the seconds the mock recording still runs (0 when none is)."""
    left, now = 0, time.time()
    if DIAG['started']:
        ran = now - DIAG['started']
        left = max(0, math.ceil(RECORD_S - ran))  # rounded up, like the TV's countdown
    if left:
        r = int(ran)
        rec = 'Recording: running, %d:%02d left\nFrames below: since it started (%d:%02d).' % (left // 60, left % 60, r // 60, r % 60)
    else:
        ago = now - DIAG['started'] - RECORD_S if DIAG['started'] else 6 * 60 + 12
        rec = 'Recording: finished %s ago (3:00 long)\nFrames below: during that recording.' % (
            'under a minute' if ago < 60 else '%d min' % (ago // 60))
    return SMOOTHNESS.replace('{recording}', rec), left


KEYS = {'up', 'down', 'left', 'right', 'ok', 'back', 'home', 'playpause', 'mute', 'volup', 'voldown', 'netflix', 'youtube'}
KEYS.add('menu')   # holding OK on the phone's remote: the TV's menu for what's focused
TYPES = {'.png': 'image/png', '.webmanifest': 'application/manifest+json'}


def log(method, url, body):
    if not LOG:
        return
    u = urlparse(url)
    try:
        data = json.loads(body) if body else None
    except ValueError:
        data = '<%d bytes>' % len(body)
    with open(LOG, 'a') as f:
        f.write(json.dumps({'t': time.time(), 'method': method, 'path': u.path, 'query': parse_qs(u.query), 'body': data}) + '\n')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, body, mime='application/json', status=200, headers=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body)
        if isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header('Content-Type', mime)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def screen(self):
        # The TV tab shows the gallery's own TV Home screenshot as "what's on the TV".
        shot = os.path.join(os.environ.get('GALLERY_RAW', '../../build/gallery'), 'tv-home-dark.png')
        if os.path.exists(shot):
            return self.send(open(shot, 'rb').read(), 'image/png')
        return self.send({'message': 'Woodruff Family isn\'t on the TV screen right now.'}, status=409)

    def do_GET(self):
        u = urlparse(self.path)
        path, query = u.path, parse_qs(u.query)
        if path.startswith('/api/') and path not in ('/api/screen', '/api/tvs/screen', '/api/type', '/api/inbox', '/api/tvs'):
            log('GET', self.path, b'')
        if path == '/':
            # Read on every request, so an edited page shows up without restarting the server.
            page = (open(os.path.join(WEB, 'index.html'), encoding='utf-8').read().replace('__TVX_KEY__', 'gallery')
                    .replace('__TVX_HOME_NAME__', 'Living Room').replace('__TVX_HOME_TAG__', 'gallery'))
            return self.send(page, 'text/html; charset=utf-8')
        ext = os.path.splitext(path)[1]
        if ext in TYPES and '/' not in path[1:]:
            return self.send(open(os.path.join(WEB, path[1:]), 'rb').read(), TYPES[ext])
        if path == '/api/screen':
            return self.screen()
        if path == '/api/tvs/screen':
            tv = query.get('id', [''])[0]
            if tv in ('c', 'd'):
                return self.send({'message': "%s isn't showing Woodruff Family, or is off." % ('Kitchen' if tv == 'c' else 'Bedroom')}, status=409)
            return self.screen()
        if path == '/api/tvs/health':
            status, body = tv_health(query.get('id', [''])[0])
            return self.send(body, 'application/json' if isinstance(body, dict) else 'text/plain', status)
        if path == '/api/list':
            where = query.get('path', [ROOT])[0].rstrip('/') or ROOT
            if where not in TREE:
                return self.send('Can\'t read folder', 'text/plain', 500)
            parent = where.rsplit('/', 1)[0]
            in_root = any(where != r['path'] and where.startswith(r['path'] + '/') for r in ROOTS)
            return self.send({'path': where, 'parent': parent if in_root else None, 'files': TREE[where]})
        if path == '/api/download':
            name = query.get('path', ['file'])[0].rsplit('/', 1)[-1]
            return self.send(b'sample file', 'application/octet-stream', headers={'Content-Disposition': 'attachment; filename="%s"' % name})
        if path == '/api/diagnostics':
            report, left = diagnostics()
            return self.send(report, 'text/plain; charset=utf-8', headers={'X-Recording-Left': str(left), 'Cache-Control': 'no-store'})
        if path == '/api/inbox':
            messages = INBOX[:]
            del INBOX[:]
            return self.send({'messages': messages})
        if path in ('/api/memories/thumb', '/api/memories/photo'):
            where = query.get('path', [''])[0]
            if where in ADDED:
                return self.send(ADDED[where][0], ADDED[where][1], headers={'Cache-Control': 'private, max-age=31536000'})
            i = next((n for n, m in enumerate(MEMORIES) if m['path'] == where), None)
            if i is None:
                return self.send("That isn't in Family Memories", 'text/plain', 400)
            big, tall = path.endswith('photo'), i % 3 == 2
            w, h = (1200, 1600) if big and tall else (1600, 1200) if big else (315, 420) if tall else (420, 315)
            return self.send(landscape(i, w, h), 'image/svg+xml', headers={'Cache-Control': 'private, max-age=31536000'})
        if path == '/__mock/memories':
            return self.send(MEM_LOG)
        api = {
            '/api/roots': ROOTS,
            '/api/type': FIELD,
            '/api/memories': memories_json(),
            '/api/phones': {'phones': PHONES},
            '/api/settings': SETTINGS,
            '/api/tvs': tvs_json(),
        }
        if path in api:
            return self.send(api[path])
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        n = int(self.headers.get('content-length') or 0)
        raw = self.rfile.read(n)
        u = urlparse(self.path)
        path, query = u.path, parse_qs(u.query)
        upload = path in ('/api/upload', '/api/memories/add')
        if not path.startswith('/__mock/'):
            log('POST', self.path, raw if not upload else b'x' * len(raw))
        try:
            body = json.loads(raw) if raw and not upload else {}
        except ValueError:
            body = {}
        if path == '/__mock/inbox':
            INBOX.append({'title': body.get('title', 'Dinner\'s ready'), 'body': body.get('body', ''), 'from': body.get('from', "Mom's iPhone")})
            return self.send({'ok': True})
        if path == '/__mock/crash':
            SETTINGS['crash'] = body.get('text', '')
            return self.send({'ok': True})
        if path == '/__mock/field':
            FIELD['field'] = body.get('field')
            return self.send({'ok': True})
        if path == '/__mock/reset':
            TVS_SET['tvs'] = None
            TV_STATE['relinked'] = False
            TV_STATE['playing'] = {'this': True, 'b': True}
            return self.send({'ok': True})
        if path == '/__mock/set':
            SETTINGS.update(body.get('settings') or {})
            if 'tvs' in body:
                TVS_SET['tvs'] = body['tvs']
            if 'phones' in body:
                PHONES[:] = body['phones']
            return self.send({'ok': True})
        if path in ('/api/key', '/api/tvs/key'):
            if body.get('key') not in KEYS:
                return self.send('Unknown key', 'text/plain', 400)
            if path == '/api/tvs/key' and body.get('id') == 'b' and body.get('key') == 'netflix':
                return self.send("Den: Netflix isn't installed on this TV", 'text/plain', 400)
            if body.get('key') == 'playpause':
                tv = 'this' if path == '/api/key' else body.get('id')
                if tv in TV_STATE['playing']:
                    TV_STATE['playing'][tv] = not TV_STATE['playing'][tv]
            return self.send({'ok': True})
        if path in ('/api/media', '/api/tvs/media'):
            tv = 'this' if path == '/api/media' else body.get('id')
            if tv not in TV_STATE['playing']:
                return self.send('Nothing is playing on that TV', 'text/plain', 400)
            if body.get('action') == 'playpause':
                TV_STATE['playing'][tv] = not TV_STATE['playing'][tv]
            return self.send({'ok': True, 'playing': TV_STATE['playing'][tv]})
        if path == '/api/tvs/update/check':
            return self.send({'ok': True})
        if path == '/api/tvs/relink':
            if body.get('id') != 'd':
                return self.send('That TV isn’t linked any more.', 'text/plain', 400)
            if ''.join(ch for ch in str(body.get('code', '')) if ch.isdigit()) != '123456':
                return self.send('That code didn’t work. Check the code on the other TV.', 'text/plain', 400)
            TV_STATE['relinked'] = True
            return self.send({'ok': True, 'name': 'Bedroom', 'id': 'd'})
        if path == '/api/diagnostics/record':
            DIAG['started'] = time.time()
            return self.send({'ok': True, 'seconds': RECORD_S})
        if path == '/api/phone/hello':
            phone = {'id': body.get('id', 'p-blake'), 'name': body.get('name', "Blake's iPhone"),
                     'alerts': body.get('alerts', True), 'topic': 'woodruff-example'}
            return self.send({'phone': phone, 'phones': PHONES})
        if path == '/api/memories/add':
            name = query.get('name', [''])[0].rsplit('/', 1)[-1]
            ext = os.path.splitext(name)[1].lower()
            if ext not in ('.jpg', '.jpeg', '.png', '.heic', '.gif', '.webp', '.mov', '.mp4', '.m4v'):
                return self.send('Only photos and videos go in Family Memories', 'text/plain', 400)
            video = ext in ('.mov', '.mp4', '.m4v')
            taken = query.get('taken', [None])[0]
            item = {'path': (VIDEOS if video else PHOTOS) + '/' + name, 'name': name, 'video': video,
                    'taken': int(taken) if taken else int(time.time() * 1000), 'size': len(raw), 'v': int(time.time() * 1000)}
            MEMORIES[:] = [m for m in MEMORIES if m['path'] != item['path']]
            MEMORIES.append(item)
            MEMORIES.sort(key=lambda m: (-m['taken'], m['name']))
            ADDED[item['path']] = (raw, 'image/jpeg' if ext in ('.jpg', '.jpeg') else 'image/svg+xml' if video else 'application/octet-stream')
            if video:
                ADDED[item['path']] = (landscape(len(MEM_LOG), 420, 315), 'image/svg+xml')
            MEM_LOG.append({'name': name, 'taken': int(taken) if taken else None, 'bytes': len(raw), 'jpeg': jpeg_size(raw)})
            return self.send({'item': item})
        if path == '/api/memories/delete':
            MEMORIES[:] = [m for m in MEMORIES if m['path'] != body.get('path')]
            return self.send(memories_json())
        if path == '/api/announce':
            return self.send({'ok': True, 'phones': len(body.get('phones') or []), 'tvs': 3 if body.get('tv', True) else 0})
        if path == '/api/settings/weather':
            SETTINGS['weather'] = body.get('place', '')
        elif path == '/api/settings/nest':
            for k in ('project', 'doorbell', 'person', 'motion'):
                if k in body:
                    SETTINGS['nest'][k] = body[k]
        elif path == '/api/settings/google':
            SETTINGS['google']['clientId'] = body.get('client_id', '')
        elif path == '/api/settings/tmdb':
            SETTINGS['tmdb'] = bool(body.get('key'))
        elif path == '/api/mail':
            label = next((p['label'] for p in SETTINGS['providers'] if p['id'] == body.get('provider')), 'Mail')
            SETTINGS['mail'].append({'id': 'm%d' % int(time.time() * 1000), 'email': body.get('email', ''), 'provider': label})
        elif path == '/api/mail/delete':
            SETTINGS['mail'] = [m for m in SETTINGS['mail'] if m['id'] != body.get('id')]
        elif path == '/api/crash/clear':
            SETTINGS['crash'] = ''
        if path.startswith('/api/settings') or path in ('/api/mail', '/api/mail/delete', '/api/crash/clear'):
            return self.send(SETTINGS)  # like the TV: settings changes answer with the full settings
        if path == '/api/upload':
            return self.send({'ok': True, 'name': query.get('name', [''])[0]})
        self.send({'ok': True})


ThreadingHTTPServer(('127.0.0.1', 8765), Handler).serve_forever()
