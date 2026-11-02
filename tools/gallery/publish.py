"""Turns the raw screenshots into small WebP files plus gallery.json for docs/ui/index.html.
Usage: python3 publish.py <raw dir> <docs/ui dir> <build number> <commit>"""
import datetime, glob, json, os, sys
from PIL import Image

raw, dest, build, commit = sys.argv[1:5]
img_dir = os.path.join(dest, 'img')
os.makedirs(img_dir, exist_ok=True)
for old in glob.glob(os.path.join(img_dir, '*.webp')):
    os.remove(old)

TITLES = {
    'home': 'Home', 'home-menu': 'Home · menu open', 'home-notice': 'Home · a notice', 'movies': 'Movies (sample posters)', 'tv-shows': 'TV Shows (sample posters)', 'command-center': 'Command center', 'google-drive': 'Google Drive', 'mail': 'Mail',
    'notifications': 'Notifications', 'notes': 'Notes', 'apps': 'Apps', 'phone': 'Phone connection',
    'settings': 'Settings', 'files': 'Send › Photos & Files', 'tv': 'TV', 'remote': 'Remote', 'keyboard': 'Keyboard', 'send': 'Send',
    'files-actions': 'File actions', 'memories': 'Family Memories', 'memories-photo': 'Family Memories › a photo',
    'settings-drive': 'Settings › Connections › Google Drive',
    'tv-screen': 'Settings › TVs › TV Screen', 'settings-tvs': 'Settings › TVs', 'send-to': 'Send › To',
    'player': 'Player', 'player-menu': 'Player › Subtitles menu', 'player-up-next': 'Player › Up next',
    'movies-offline': 'Movies · offline (the saved library)', 'tv-shows-reconnecting': 'TV Shows · reconnecting', 'drive-offline': 'Movies · offline, nothing saved yet',
    'health': 'Settings › TV health', 'health-report': 'Settings › TV health › a TV that needs attention',
    'health-good': 'Settings › TV health › all good', 'health-link-again': 'Settings › TV health › link again',
    'settings-tv': 'Settings › TVs › a TV', 'settings-tv-attention': 'Settings › TVs › a TV that needs attention',
    'settings-tv-attention-sheet': 'Settings › TVs › fixing it', 'settings-tv-off': "Settings › TVs › a TV that's off",
    'settings-tv-link': 'Settings › TVs › Link again', 'settings-tv-relink': 'Settings › TVs › typing the code',
    'watch': 'Watch', 'watch-scrolled': 'Watch · Movies and TV Shows', 'watch-title-movie': 'Watch › a film',
    'watch-title-show': 'Watch › a show', 'watch-play-on': 'Watch › Play on', 'watch-context': 'Watch › hold a tile',
    'watch-movies': 'Watch › Movies', 'watch-search': 'Watch › Search', 'watch-now-playing': 'Watch › now playing',
    'watch-offline': 'Watch · offline', 'watch-signin': 'Watch · sign-in ended', 'watch-loading': 'Watch · first load',
    'signin': 'Sign in from the phone', 'signin-waiting': 'Sign in · waiting for Google', 'signin-done': 'Sign in · done',
    'signin-banner': 'A sign-in ended (banner)', 'signin-linked': 'Sign in · another TV',
}
# Phone polish (L4-5).
TITLES.update({
    'remote-unreachable': 'Remote · can’t reach the TV', 'remote-unreachable-linked': 'Remote · can’t reach the picked TV',
    'keyboard-linked': 'Keyboard · typing into another TV', 'messages-summary': 'Messages that arrived together (banner)',
    'settings-phone-messages': 'Settings › This Phone › Recent Messages', 'memories-no-room': 'Family Memories · a video too big for the TV',
    'files-no-room': 'Photos & Files · a file too big for the TV',
})
groups = {'tv': {}, 'phone': {}}
for path in sorted(glob.glob(os.path.join(raw, '*.png'))):
    kind, rest = os.path.basename(path)[:-4].split('-', 1)
    name, scheme = rest.rsplit('-', 1)
    im = Image.open(path).convert('RGB')
    width = 1600 if kind == 'tv' else 780
    if im.width > width:
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    out = f'{kind}-{name}-{scheme}.webp'
    im.save(os.path.join(img_dir, out), 'WEBP', quality=82, method=6)
    groups[kind].setdefault(name, {'name': name, 'title': TITLES.get(name, name.title())})[scheme] = 'img/' + out

ORDER = ['home', 'home-menu', 'home-notice', 'movies', 'tv-shows', 'movies-offline', 'tv-shows-reconnecting', 'drive-offline', 'command-center', 'google-drive', 'mail', 'notifications', 'notes', 'apps', 'phone', 'settings',
         'health', 'health-report', 'health-good', 'health-link-again',
         'player', 'player-menu', 'player-up-next', 'files', 'tv', 'keyboard', 'send']
PHONE_ORDER = ['remote', 'keyboard',
               'watch', 'watch-scrolled', 'watch-title-movie', 'watch-title-show', 'watch-play-on', 'watch-context', 'watch-movies',
               'watch-search', 'watch-now-playing', 'watch-offline', 'watch-signin', 'watch-loading',
               'send', 'send-to', 'files', 'files-actions', 'memories', 'memories-photo', 'settings', 'settings-tvs',
               'settings-tv', 'settings-tv-attention', 'settings-tv-attention-sheet', 'settings-tv-off', 'settings-tv-link', 'settings-tv-relink',
               'tv-screen', 'settings-drive', 'signin', 'signin-waiting', 'signin-done', 'signin-banner', 'signin-linked']
# Phone polish (L4-5): each new shot after the one it belongs with.
for after, name in [('remote', 'remote-unreachable-linked'), ('remote', 'remote-unreachable'), ('keyboard', 'keyboard-linked'),
                    ('files-actions', 'files-no-room'), ('memories-photo', 'memories-no-room'), ('signin-banner', 'messages-summary'),
                    ('messages-summary', 'settings-phone-messages')]:
    if after in PHONE_ORDER and name not in PHONE_ORDER:
        PHONE_ORDER.insert(PHONE_ORDER.index(after) + 1, name)
rank = lambda g: (ORDER.index(g['name']) if g['name'] in ORDER else 99, g['name'])
data = {
    'build': build,
    'commit': commit,
    'updated': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC'),
    'tv': sorted(groups['tv'].values(), key=rank),
    'phone': sorted(groups['phone'].values(), key=lambda g: PHONE_ORDER.index(g['name']) if g['name'] in PHONE_ORDER else 99),
}
with open(os.path.join(dest, 'gallery.json'), 'w') as f:
    json.dump(data, f, indent=1)
print(f"{len(data['tv'])} TV screens, {len(data['phone'])} phone screens")
