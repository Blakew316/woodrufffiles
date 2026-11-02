// Checks that the phone's Watch tab works, not only that it draws: five tabs, the root from one request, Continue
// Watching resuming on the picked TV, the billboard, a film's and a show's sheet (seasons, episodes, Start Over), the
// Play rule (Play On, a TV that's off asks, All TVs, one TV never asks, "Ready on…"), marks and removals, the
// now-playing bar's controls and the TV Screen sheet, the grids (paging, filter, search), Search, deep links, Escape
// and Back, the whole-page states, long words at 375 pt. Against mock_server.py with MOCK_LOG set (it reads what the
// page sent). Usage: MOCK_LOG=/tmp/mock.log python3 mock_server.py <web> &  node phone_watch_check.mjs [base] [log]
import { chromium, devices } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';

const base = process.argv[2] || 'http://127.0.0.1:8765/';
const logFile = process.argv[3] || process.env.MOCK_LOG;
let failures = 0;
function check(ok, what) { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failures++; }
function sent(path) {   // the requests the page made to [path] since the last clear
  if (!logFile) return [];
  return readFileSync(logFile, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)).filter((r) => r.path === path);
}
const clear = () => { if (logFile) writeFileSync(logFile, ''); };
const mock = (what, body) => fetch(base + '__mock/' + what, { method: 'POST', body: JSON.stringify(body || {}) });
const settle = (page, ms = 900) => page.waitForTimeout(ms);
const text = (page, sel) => page.locator(sel).first().innerText();
const texts = (page, sel) => page.$$eval(sel, (ns) => ns.map((n) => n.textContent.trim()));
const shown = (page, sel) => page.locator(sel).first().isVisible();
const menuItems = (page) => page.$$eval('#menu .mi', (ns) => ns.map((n) => ({ label: n.querySelector('.mt').textContent, sub: (n.querySelector('.ms') || {}).textContent || '', disabled: n.disabled })));
const choose = async (page, label) => { await page.locator('#menu .mi', { has: page.locator('.mt', { hasText: label }) }).first().tap(); await settle(page); };
const sheetOpen = (page) => page.$eval('#ts', (n) => n.classList.contains('show'));

const browser = await chromium.launch();
const iphone = devices['iPhone 13'];
const context = await browser.newContext({ ...iphone, viewport: { width: 390, height: 797 } });
await context.addInitScript(() => {
  localStorage.setItem('woodruff.device', JSON.stringify({ id: 'p-check', name: 'Check iPhone', alerts: false }));
  localStorage.setItem('woodruff.installHint', '1');
  Object.defineProperty(navigator, 'standalone', { get: () => true });
});
const fresh = async (hash) => { await page.goto(base + hash); await page.reload(); await settle(page, 1800); };   // a new page load
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await mock('reset');
clear();

// Five tabs, Remote first and still the one the app opens on; no now-playing bar there.
await page.goto(base);
await settle(page, 1500);
check(JSON.stringify(await page.$$eval('#tabbar button', (bs) => bs.map((b) => b.dataset.tab))) === '["remote","watch","send","memories","settings"]', 'five tabs, in order');
check(JSON.stringify(await page.$$eval('#tabbar button .tb-i:not(.t)', (ns) => ns.map((n) => n.textContent.trim()))) === '["Remote","Watch","Send","Memories","Settings"]', 'their labels');
check(await shown(page, '#tab-remote') && !(await shown(page, '#tab-watch')), 'the app opens on Remote');
check(!(await shown(page, '#np')), 'no now-playing bar on Remote');

// Watch: the whole root from one request.
await page.tap('nav button[data-tab=watch]');
await settle(page, 2500);
check(sent('/api/watch/home').length === 1, 'one request for the root');
check((await text(page, '#w-tv-name')) === 'Living Room' && await shown(page, '#w-tv-dot'), 'the bar names the picked TV, live dot on');
check((await text(page, '#w-pager .hero-p b')) === 'Midnight Harbor', 'the billboard: Midnight Harbor');
check((await text(page, '#w-hero-play-t')) === 'Play', 'its Play button');
const caps = await texts(page, '#w-cw .pcap span');
check(caps[0] === '1h 08m left' && caps[1] === 'S2 E4 · 11m left' && caps[2] === 'Next · S3 E1', 'Continue Watching captions: ' + caps.slice(0, 3).join(' | '));
check(await page.locator('#w-recent .ptile .newb').count() >= 1, 'a NEW badge in Recently Added');
check(await page.locator('#w-shows .ptile.card').count() >= 1, 'a dark title card for a show with no poster');
check(await shown(page, '#np') && (await text(page, '#np-t')) === 'Midnight Harbor' && (await text(page, '#np-s')) === 'Living Room', 'now-playing bar: Midnight Harbor, Living Room');
check(await shown(page, '#np-back') && await shown(page, '#np-fwd'), '↺10 and 30↻ (Living Room can seek)');

// A Continue Watching tile resumes on the picked TV at once.
clear();
await page.tap('#w-cw .cwi:nth-child(1)');
await settle(page, 700);
const resume = sent('/api/watch')[0];
check(!!resume && resume.body.key === 'drive:m-harbor' && resume.body.position === 2710000, 'resumes Midnight Harbor at 45:10 on Living Room');
check((await text(page, '#toast-t')) === 'Resuming on Living Room', 'toast: Resuming on Living Room');
await settle(page, 1800);

// The billboard's Details: the sheet, then Start Over plays from 0 and closes it.
await page.tap('#w-hero-info');
await settle(page, 1300);
check(await sheetOpen(page), 'Details opens the title sheet');
check((await text(page, '#ts-title')) === 'Midnight Harbor', 'its title');
check((await text(page, '#ts-play-t')) === 'Resume from 45m' && (await text(page, '#ts-alt-t')) === 'Start Over', 'Resume from 45m and Start Over');
check((await text(page, '#ts-target-t')) === 'On Living Room', 'On Living Room');
check(/^Ends at \d{1,2}:\d{2}\s?[AP]M$/.test(await text(page, '#ts-ends')), 'Ends at …');
clear();
await page.tap('#ts-alt');
await settle(page, 900);
const over = sent('/api/watch')[0];
check(!!over && over.body.position === 0, 'Start Over sends position 0');
check((await text(page, '#toast-t')) === 'Playing on Living Room', 'toast: Playing on Living Room');
check(!(await sheetOpen(page)), 'the sheet closes after a start');
await settle(page, 1800);

// Play On from the sheet: every TV, the ones that can't take it dimmed with why, All TVs; choosing Den makes it the pick.
await page.tap('#w-hero-info');
await settle(page, 1300);
await page.tap('#ts-target');
await settle(page);
let items = await menuItems(page);
check(JSON.stringify(items.map((i) => i.label)) === '["Living Room","Den","Kitchen","Bedroom","All TVs"]', 'Play On: four TVs and All TVs');
check(items[2].disabled && items[2].sub === 'Off' && items[3].disabled && items[3].sub === 'Link again', 'Kitchen (Off) and Bedroom (Link again) dimmed, saying why');
check(!items[0].disabled && !items[1].disabled && items[4].sub === 'Watch together', 'Living Room, Den and All TVs can be chosen');
await choose(page, 'Den');
check((await text(page, '#ts-target-t')) === 'On Den', 'the sheet now plays on Den');
check((await text(page, '#w-tv-name')) === 'Den', 'and Den is the picked TV');
clear();
await page.tap('#ts-play');
await settle(page, 900);
const den = sent('/api/tvs/watch')[0];
check(!!den && den.body.id === 'b' && den.body.key === 'drive:m-harbor' && den.body.position === 2710000, 'Resume plays on Den');
check((await text(page, '#toast-t')) === 'Resuming on Den', 'toast: Resuming on Den');
await settle(page, 1800);

// Den plays YouTube: the bar says so and offers Play/Pause only.
check((await text(page, '#np-s')) === 'Den · YouTube', 'now-playing bar: Den · YouTube');
check(await page.locator('#np-back').isHidden() && await page.locator('#np-fwd').isHidden(), 'no ↺10 or 30↻ for an app that can’t seek');
clear();
await page.tap('#np-pp');
await settle(page, 300);
check(sent('/api/tvs/key').some((r) => r.body.id === 'b' && r.body.key === 'playpause'), 'Play/Pause goes to Den');
check((await page.getAttribute('#np-pp', 'aria-label')) === 'Play', 'the glyph flips at once');
await settle(page, 1500);

// The picker: back to Living Room. A TV that's off asks instead of playing.
await page.tap('#w-tv');
await settle(page);
await choose(page, 'Kitchen');
check((await text(page, '#w-tv-name')) === 'Kitchen', 'Kitchen picked');
check(!(await shown(page, '#np')), 'no now-playing bar for a TV that’s off');
clear();
await page.tap('#w-hero-play');
await settle(page);
check(sent('/api/watch').length === 0 && sent('/api/tvs/watch').length === 0, 'nothing starts on a TV that’s off');
check(await shown(page, '#menu') && (await menuItems(page))[0].label === 'Living Room', 'the Play On menu asks instead');
await choose(page, 'Living Room');
await settle(page, 300);
check(sent('/api/watch').some((r) => r.body.key === 'drive:m-harbor'), 'choosing Living Room plays there');
check((await text(page, '#w-tv-name')) === 'Living Room', 'and makes it the picked TV');
await settle(page, 1800);

// Hold a tile: the TV's title menu plus Play On, Watch together, marks; All TVs starts Watch together.
await page.click('#w-cw .cwi:nth-child(1)', { button: 'right' });
await settle(page);
items = await menuItems(page);
check(JSON.stringify(items.map((i) => i.label)) === '["Resume from 45m","Start Over","Play On…","Details","Watch Together on All TVs","Mark as Watched","Remove from Continue Watching"]', 'the held tile’s menu: ' + items.map((i) => i.label).join(' | '));
clear();
await choose(page, 'Watch Together on All TVs');
await settle(page, 300);
check(sent('/api/tvs/watch').some((r) => r.body.id === 'all' && r.body.key === 'drive:m-harbor'), 'Watch together on all TVs');
check((await text(page, '#toast-t')) === 'Watching together on 3 of 4 TVs', 'toast: Watching together on 3 of 4 TVs');
await settle(page, 1800);

// Accepted but waiting on the TV: the "Ready on…" alert.
await mock('set', { watch: { started: false } });
await page.tap('#w-cw .cwi:nth-child(1)');
await settle(page, 900);
check((await text(page, '#d-title')) === 'Ready on Living Room', 'alert: Ready on Living Room');
check((await text(page, '#d-msg')) === 'It’s waiting on the TV: open Woodruff Family there. To start videos from the phone anytime, turn on Pop-ups over other apps in Woodruff’s Settings on Living Room.', 'with what to do');
await page.locator('#d-btns button', { hasText: 'OK' }).tap();
await settle(page);
await mock('set', { watch: { started: true } });

// A show: its sheet at the season it's in, episodes with ✓ and progress; an episode plays and the sheet closes.
await page.tap('#w-shows .ptile:nth-child(1)');
await settle(page, 1500);
check((await text(page, '#ts-title')) === 'Backyard Science', 'Backyard Science’s sheet');
check((await text(page, '#ts-play-t')) === 'Resume S2 E4' && (await text(page, '#ts-alt-t')) === 'Play from the Start', 'Resume S2 E4 and Play from the Start');
check((await text(page, '#ts-season-t')) === 'Season 2', 'opens at Season 2');
const eps = page.locator('#ts-eplist .ep');
check(await eps.count() === 6, 'six episodes');
check(JSON.stringify(await page.$$eval('#ts-eplist .ep .ep-nm', (ns) => ns.slice(0, 4).map((n) => n.textContent))) === '["Magnets","Ice","Wind","The Volcano"]', 'their names');
check(await page.locator('#ts-eplist .ep:nth-child(1) .ck:not([hidden])').count() === 1, 'a watched episode shows ✓');
check((await page.textContent('#ts-eplist .ep:nth-child(1) .ep-eb')) === 'Episode 1 · 24 min', 'Episode 1 · 24 min');
await page.click('#ts-eplist .ep:nth-child(4)', { button: 'right' });
await settle(page);
check(JSON.stringify((await menuItems(page)).map((i) => i.label)) === '["Play from the Start","Mark as Watched"]', 'holding an episode partway: Play from the Start, Mark as Watched');
await page.keyboard.press('Escape');
await settle(page);
await page.click('#ts-eplist .ep:nth-child(1)', { button: 'right' });
await settle(page);
check(JSON.stringify((await menuItems(page)).map((i) => i.label)) === '["Mark as Unwatched"]', 'holding a watched episode: Mark as Unwatched');
clear();
await choose(page, 'Mark as Unwatched');
await settle(page, 1200);
check(sent('/api/library/watched').some((r) => r.body.watched === false && r.body.keys.length === 1), 'Mark as Unwatched sends watched: false');
check(await page.locator('#ts-eplist .ep:nth-child(1) .ck:not([hidden])').count() === 0, 'the ✓ goes when the sheet asks again');
await page.tap('#ts-season');
await settle(page);
items = await menuItems(page);
check(JSON.stringify(items.map((i) => i.label)) === '["Season 1","Season 2"]', 'the season menu');
clear();
await choose(page, 'Season 1');
await settle(page, 800);
check((await text(page, '#ts-season-t')) === 'Season 1' && sent('/api/library/episodes').length >= 1, 'Season 1’s episodes');
await page.tap('#ts-season');
await settle(page);
await choose(page, 'Season 2');
await settle(page, 800);
clear();
await page.locator('#ts-eplist .ep:nth-child(5)').scrollIntoViewIfNeeded();
await page.tap('#ts-eplist .ep:nth-child(5)');
await settle(page, 900);
const ep = sent('/api/watch')[0];
check(!!ep && /^drive:/.test(ep.body.key) && ep.body.position === 0, 'an episode not started plays from 0');
check((await text(page, '#toast-t')) === 'Playing on Living Room' && !(await sheetOpen(page)), 'toast, and the sheet closes');
await settle(page, 1800);

// Mark as Watched and Remove from Continue Watching change the shared stores, and the tile goes.
const before = await page.locator('#w-cw .cwi').count();
await page.click('#w-cw .cwi:nth-child(2)', { button: 'right' });
await settle(page);
clear();
await choose(page, 'Mark as Watched');
await settle(page, 1200);
const mark = sent('/api/library/watched')[0];
check(!!mark && mark.body.watched === true && mark.body.keys.length === 1 && /^drive:/.test(mark.body.keys[0]), 'Mark as Watched sends the episode’s key');
check(await page.locator('#w-cw .cwi').count() === before - 1, 'its tile goes');
await page.click('#w-cw .cwi:nth-child(1)', { button: 'right' });
await settle(page);
clear();
await choose(page, 'Remove from Continue Watching');
await settle(page, 1200);
check(sent('/api/continue/remove').some((r) => r.body.key === 'drive:m-harbor'), 'Remove from Continue Watching sends its key');
check(await page.locator('#w-cw .cwi').count() === before - 2, 'and that tile goes too');
await mock('reset');

// The now-playing bar on Living Room: Play/Pause by the remote's key, ↺10 and 30↻ by /api/media; the TV Screen sheet.
await page.goto(base + '#watch');
await settle(page, 2000);
clear();
await page.tap('#np-pp');
await settle(page, 300);
check(sent('/api/key').some((r) => r.body.key === 'playpause'), 'Play/Pause: the TV’s key');
await page.tap('#np-back');
await page.tap('#np-fwd');
await settle(page, 400);
check(sent('/api/media').some((r) => r.body.action === 'back10') && sent('/api/media').some((r) => r.body.action === 'fwd30'), '↺10 and 30↻');
await settle(page, 1200);
await page.tap('#np-open');
await settle(page, 1200);
check(await shown(page, '#scr-ctl') && await shown(page, '#scr-next'), 'the TV Screen sheet: controls and Next Episode');
clear();
await page.tap('#scr-next');
await settle(page, 400);
check(sent('/api/media').some((r) => r.body.action === 'next'), 'Next Episode');
await page.keyboard.press('Escape');
await settle(page);
await page.tap('nav button[data-tab=remote]');
await settle(page);
check(!(await shown(page, '#np')), 'the bar stays on the Watch tab');

// The Movies grid: a deep link, 60 at a time, the filter, its own search.
clear();
await page.goto(base + '#watch/movies');
await settle(page, 1800);
check(await shown(page, '#watch-movies'), '#watch/movies opens the Movies grid');
check(await page.locator('#wm-grid .ptile').count() === 60, 'the first 60');
await page.evaluate(() => { const s = document.querySelector('#watch-movies .scroller'); s.scrollTop = s.scrollHeight; });
await settle(page, 1500);
check(await page.locator('#wm-grid .ptile').count() === 64, 'then the rest when the end comes near');
check((await text(page, '#wm-count')) === '64 Movies', '64 Movies');
await page.tap('#wm-filter');
await settle(page);
clear();
await choose(page, 'Unwatched');
await settle(page, 1200);
check(sent('/api/library').some((r) => r.query.unwatched && r.query.unwatched[0] === '1'), 'Unwatched asks the TV again');
check((await text(page, '#wm-filter-t')) === 'Unwatched' && (await text(page, '#wm-count')) === '63 Movies', 'Unwatched · 63 Movies');
await page.tap('#wm-filter');
await settle(page);
await choose(page, 'All');
await settle(page, 1200);
await page.tap('#wm-q');
await page.keyboard.type('orb');
await settle(page, 1200);
check(JSON.stringify(await page.$$eval('#wm-grid .ptile', (ns) => ns.map((n) => n.getAttribute('aria-label') || n.textContent))).includes('Orbit') && await page.locator('#wm-grid .ptile').count() === 1, 'its search: Orbit');

// Search: the keyboard comes up, Recently Added for an empty query, sections like the TV's.
await page.goto(base + '#watch');
await settle(page, 1800);
await page.tap('#w-search-btn');
await settle(page, 1200);
check(await page.evaluate(() => document.activeElement && document.activeElement.id) === 'ws-input', 'Search: the field has the keyboard');
check(JSON.stringify(await texts(page, '#ws-res .ws-h')).startsWith('["Recently Added'), 'an empty query shows Recently Added');
check(!(await shown(page, '#np')), 'the bar hides while typing');
await page.keyboard.type('har');
await settle(page, 1200);
check((await texts(page, '#ws-res .ws-h'))[0].replace(/\s+/g, ' ') === 'Movies · 4', 'Movies · 4');
await page.fill('#ws-input', 'zzzz');
await settle(page, 1200);
check((await text(page, '#ws-res')).includes('No matches for “zzzz”'), 'No matches for “zzzz”');

// A title's address opens its sheet over Watch; Escape and Back close it.
await page.goto(base + '#watch/title/m-harbor');
await settle(page, 1800);
check(await sheetOpen(page) && (await text(page, '#ts-title')) === 'Midnight Harbor', '#watch/title/<id> opens its sheet');
await page.keyboard.press('Escape');
await settle(page);
check(!(await sheetOpen(page)), 'Escape closes it');
await page.tap('#w-hero-info');
await settle(page, 1300);
await page.goBack();
await settle(page);
check(!(await sheetOpen(page)), 'Back closes it');

// Whole-page states, in the TV's words.
for (const [state, sel, words] of [
  ['offline', '#w-note-t', 'Offline · showing your saved library'],
  ['unreachable', '#w-note-t', 'Can’t reach Google · showing your saved library'],
  ['expired', '#w-card-t', 'Sign In to Google Drive Again'],
  ['expiredEmpty', '#w-cuv-t', 'Sign In to Google Drive Again'],
  ['notSignedIn', '#w-cuv-t', 'Sign In to Google Drive'],
  ['notSetUp', '#w-cuv-t', 'Set Up Movies & TV Shows'],
  ['empty', '#w-cuv-t', 'No Movies or TV Shows Yet'],
  ['loading', '#w-wait', 'Movies and TV Shows are still loading…'],
]) {
  await mock('set', { library: state });
  await fresh('#watch');
  check(await shown(page, sel) && (await text(page, sel)) === words, state + ': ' + words);
}
// The TV out of reach: the saved copy stays (said once), else the page says so.
await mock('set', { library: 'down' });
await page.goto(base + '#watch');
await page.reload();
await settle(page, 1200);
check((await text(page, '#toast-t')) === 'Can’t reach Living Room' && await page.locator('#w-cw .cwi').count() > 0, 'out of reach with a saved copy: it stays, with a toast');
await page.evaluate(() => localStorage.removeItem('woodruff.watch'));
await fresh('#watch');
check(await shown(page, '#w-cuv-t') && (await text(page, '#w-cuv-t')) === 'Can’t Reach Living Room', 'out of reach, nothing saved: Can’t Reach Living Room');
await mock('reset');

// One TV: "Watch" in the bar, no picker, and Play never asks.
await mock('set', { tvs: [{ id: 'this', name: 'Living Room', online: true, onScreen: true, link: 'ok', health: { status: 'good', count: 0, first: null }, playing: null }] });
await fresh('#watch');
check(await shown(page, '#w-title-t') && (await text(page, '#w-title-t')) === 'Watch' && !(await shown(page, '#w-tv')), 'one TV: the bar says Watch');
clear();
await page.tap('#w-hero-play');
await settle(page, 700);
check(sent('/api/watch').length === 1 && !(await shown(page, '#menu')), 'one TV: Play plays at once');
// A double tap on Play is one start (the second tap lands while the first is on its way), never the video starting twice.
await settle(page, 1500);
clear();
await page.route('**/api/watch', async (r) => { await new Promise((ok) => setTimeout(ok, 400)); await r.continue(); });
await page.locator('#w-hero-play').dblclick();
await settle(page, 1200);
await page.unroute('**/api/watch');
check(sent('/api/watch').length === 1, 'a double tap on Play starts it once');
await page.tap('#w-hero-info');
await settle(page, 1300);
check(!(await shown(page, '#ts-target')), 'one TV: no “On …” under the buttons');
await mock('reset');

// The bar never covers the last row: scrolled to the end, the TV Shows row ends above it.
await fresh('#watch');
await page.evaluate(() => { const s = document.querySelector('#watch-root .scroller'); s.scrollTop = s.scrollHeight; });
await settle(page);
const gap = await page.evaluate(() => document.querySelector('#np').getBoundingClientRect().top - document.querySelector('#w-shows .wrow').getBoundingClientRect().bottom);
check(gap >= 0, 'the last row ends above the now-playing bar (' + Math.round(gap) + ' pt)');

// Reduce Motion: the sheet and menus still open and close (cross-fades instead of springs).
await page.emulateMedia({ reducedMotion: 'reduce' });
await fresh('#watch');
await page.tap('#w-hero-info');
await settle(page, 1000);
check(await sheetOpen(page) && (await text(page, '#ts-title')) === 'Midnight Harbor', 'Reduce Motion: the sheet opens');
await page.tap('#ts-close');
await settle(page, 800);
check(!(await sheetOpen(page)), 'Reduce Motion: and closes');
await page.emulateMedia({ reducedMotion: 'no-preference' });

// 375 pt with long words: nothing scrolls sideways.
await page.setViewportSize({ width: 375, height: 667 });
await fresh('#watch');
const wide = await page.evaluate(() => { const s = document.querySelector('#watch-root .scroller'); return s.scrollWidth - s.clientWidth + (document.documentElement.scrollWidth - document.documentElement.clientWidth); });
check(wide <= 0, 'no sideways scroll at 375 pt');

check(errors.length === 0, 'no script errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
await browser.close();
console.log(failures ? failures + ' failed' : 'all passed');
process.exit(failures ? 1 : 0);
