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
    'movies-offline': 'Movies · offline', 'movies-cant-reach': 'Movies · can’t reach Google (search field focused)',
    'tv-shows-back-online': 'TV Shows · back online', 'movies-offline-empty': 'Movies · offline, nothing saved',
    'tv-shows-trying': 'TV Shows · can’t reach Google, trying again', 'movies-keys': 'Movies · keys not accepted',
    'drive-signin-again': 'Google Drive · sign in again', 'home-offline': 'Home · offline',
    'command-center-offline': 'Command center · offline', 'home-signin-notice': 'Home · sign-in ended notice',
    'movies-truncated': 'Movies · very large library', 'settings-accounts-ended': 'Settings › Accounts · sign-ins ended',
    'title-show': 'Title page · show', 'title-show-episodes': 'Title page · show, on a new episode',
    'title-show-plain': 'Title page · show without TMDB', 'title-show-offline': 'Title page · a season not listed yet, offline',
    'tv-shows-season': 'TV Shows › a season’s folder',
    'home-new-episodes': 'Home · new episodes',
    'tv-shows-rows': 'TV Shows · Overview',
    'search': 'Search', 'search-results': 'Search · results', 'search-no-matches': 'Search · no matches',
    'search-setup': 'Search · Google Drive not set up', 'search-offline': 'Search · offline, the saved library',
    'search-loading': 'Search · still loading', 'search-signin-again': 'Search · sign in again',
    'search-keys': 'Search · keys not accepted',
    'home-added': 'Home · Recently added', 'title-menu': 'Title menu (hold OK)', 'title-film': 'Title page · film',
}
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

ORDER = ['home', 'home-menu', 'home-notice', 'home-added', 'title-menu', 'title-film', 'home-new-episodes', 'home-offline', 'home-signin-notice',
         'movies', 'tv-shows', 'search', 'search-results', 'search-no-matches', 'search-setup', 'search-offline', 'search-loading',
         'search-signin-again', 'search-keys', 'tv-shows-rows', 'title-show', 'title-show-episodes', 'title-show-plain', 'title-show-offline', 'tv-shows-season', 'movies-offline', 'movies-cant-reach',
         'tv-shows-back-online', 'movies-offline-empty', 'tv-shows-trying', 'movies-keys', 'drive-signin-again', 'movies-truncated',
         'command-center', 'command-center-offline', 'google-drive', 'mail', 'notifications', 'notes', 'apps', 'phone', 'settings', 'settings-accounts-ended',
         'player', 'player-menu', 'player-up-next', 'files', 'tv', 'keyboard', 'send']
PHONE_ORDER = ['remote', 'keyboard', 'send', 'send-to', 'files', 'files-actions', 'memories', 'memories-photo', 'settings', 'settings-tvs', 'tv-screen', 'settings-drive']
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
