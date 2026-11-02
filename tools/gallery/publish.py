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
    'files-scrolled': 'Send › Photos & Files · scrolled', 'files-actions': 'File actions', 'memories': 'Family Memories', 'memories-photo': 'Family Memories › a photo',
    'settings-drive': 'Settings › Connections › Google Drive',
    'tv-screen': 'Settings › TVs › TV Screen', 'settings-tvs': 'Settings › TVs', 'send-to': 'Send › To',
    'player': 'Player', 'player-menu': 'Player › Subtitles menu', 'player-up-next': 'Player › Up next',
    'movies-offline': 'Movies · offline', 'movies-cant-reach': 'Movies · can’t reach Google (search field focused)',
    'tv-shows-back-online': 'TV Shows · back online', 'movies-offline-empty': 'Movies · offline, nothing saved',
    'tv-shows-trying': 'TV Shows · can’t reach Google, trying again', 'movies-keys': 'Movies · keys not accepted',
    'drive-signin-again': 'Google Drive · sign in again', 'home-offline': 'Home · offline',
    'command-center-offline': 'Command center · offline', 'home-signin-notice': 'Home · sign-in ended notice',
    'movies-truncated': 'Movies · very large library', 'settings-accounts-ended': 'Settings › Accounts · sign-ins ended',
    'movies-back-to-top': 'Movies · Back from deep in the rows: the banner’s Play',
    'movies-grid-back-to-top': 'Movies › All · Back from deep in the grid: the first poster',
    'update-offer': 'An update offered as Woodruff opens', 'update-offer-retry': 'An update offered again after a try',
    'settings-updates': 'Settings › Updates', 'settings-updates-asks': 'Settings › Updates · a TV that asks first',
    'settings-updated': 'Settings · the day after an update', 'player-restored': 'Player · back where you left it',
    'restore-gone': 'After an update · the video has gone',
    'phone-waiting': 'Phone connection · waiting for the network', 'phone-waiting-focused': 'Phone connection · waiting, Network settings focused',
    'phone-off': 'Phone connection · couldn’t start', 'settings-start': 'Settings › TVs & phones › Start when the TV turns on',
    'settings-popups': 'Settings › Advanced › Pop-ups over other apps',
    'recovery': 'Recovery screen', 'recovery-ready': 'Recovery screen · fix ready',
    'recovery-details': 'Recovery screen · what happened',
    'home-person-notice': 'Home · person seen', 'home-cameras': 'Home · camera states',
    'command-center-cameras': 'Command center · camera states',
    'doorbell-ring-home': 'Doorbell · from Home', 'doorbell-slideshow': 'Doorbell · over the slideshow',
    'doorbell-connecting': 'Doorbell · connecting', 'doorbell-reconnecting': 'Doorbell · reconnecting',
    'doorbell-offline': 'Doorbell · offline',
    'player-audio-menu': 'Player › Audio menu', 'player-no-sound': 'Player · sound this TV can’t play',
    'player-buffering': 'Player · loading', 'player-reconnecting': 'Player · reconnecting',
    'player-still-reconnecting': 'Player · still reconnecting', 'player-signed-out': 'Player · Drive sign-in ended',
    'player-subtitles': 'Player · subtitles', 'player-subtitles-raised': 'Player · subtitles above the controls',
    'player-subtitle-size': 'Player › Subtitle size', 'settings-subtitle-size': 'Settings › Appearance › Subtitle size',
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

ORDER = ['home', 'home-menu', 'home-notice', 'home-person-notice', 'home-cameras', 'home-added', 'title-menu', 'title-film',
         'home-new-episodes', 'home-offline', 'home-signin-notice',
         'movies', 'tv-shows', 'movies-back-to-top', 'movies-grid-back-to-top',
         'search', 'search-results', 'search-no-matches', 'search-setup', 'search-offline', 'search-loading',
         'search-signin-again', 'search-keys', 'tv-shows-rows', 'title-show', 'title-show-episodes', 'title-show-plain',
         'title-show-offline', 'tv-shows-season', 'movies-offline', 'movies-cant-reach',
         'tv-shows-back-online', 'movies-offline-empty', 'tv-shows-trying', 'movies-keys', 'drive-signin-again', 'movies-truncated',
         'command-center', 'command-center-cameras', 'command-center-offline',
         'doorbell-ring-home', 'doorbell-slideshow', 'doorbell-connecting', 'doorbell-reconnecting', 'doorbell-offline',
         'google-drive', 'mail', 'notifications', 'notes', 'apps',
         'phone', 'phone-waiting', 'phone-waiting-focused', 'phone-off',
         'settings', 'health', 'health-report', 'health-good', 'health-link-again',
         'settings-accounts-ended', 'settings-updated', 'settings-updates', 'settings-updates-asks', 'settings-start', 'settings-popups',
         'update-offer', 'update-offer-retry', 'recovery', 'recovery-ready', 'recovery-details',
         'player', 'player-menu', 'player-up-next', 'player-audio-menu', 'player-no-sound',
         'player-buffering', 'player-reconnecting', 'player-still-reconnecting', 'player-signed-out',
         'player-subtitles', 'player-subtitles-raised', 'player-subtitle-size', 'settings-subtitle-size', 'player-restored', 'restore-gone', 'files', 'tv', 'keyboard', 'send']
PHONE_ORDER = ['remote', 'keyboard',
               'watch', 'watch-scrolled', 'watch-title-movie', 'watch-title-show', 'watch-play-on', 'watch-context', 'watch-movies',
               'watch-search', 'watch-now-playing', 'watch-offline', 'watch-signin', 'watch-loading',
               'send', 'send-to', 'files', 'files-scrolled', 'files-actions', 'memories', 'memories-photo', 'settings', 'settings-tvs',
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
