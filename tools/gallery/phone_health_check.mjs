// Checks that the phone's TV health works, not only that it draws: Settings → TVs, each TV's page in every state,
// the fixes behind attention rows, Check for Updates, Link Again with a wrong and a right code, the controls, the crash
// page, Copy Health Report, deep links, and a long TV name at 375 pt. Against mock_server.py with MOCK_LOG set
// (it reads what the page sent). Usage: MOCK_LOG=/tmp/mock.log python3 mock_server.py <web> &  node phone_health_check.mjs [base] [log]
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
const settle = (page, ms = 900) => page.waitForTimeout(ms);
const text = (page, sel) => page.locator(sel).first().innerText();

const browser = await chromium.launch();
const iphone = devices['iPhone 13'];
const context = await browser.newContext({ ...iphone, viewport: { width: 390, height: 797 } });
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: new URL(base).origin });
await context.addInitScript(() => {
  localStorage.setItem('woodruff.device', JSON.stringify({ id: 'p-check', name: 'Check iPhone', alerts: false }));
  localStorage.setItem('woodruff.installHint', '1');
  Object.defineProperty(navigator, 'standalone', { get: () => true });
});
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await fetch(base + '__mock/reset', { method: 'POST' });
if (logFile) writeFileSync(logFile, '');

// Settings: the TVs row says which TVs need attention, in amber.
await page.goto(base + '#settings');
await settle(page, 1500);
check((await text(page, '#tvs-status')) === '2 TVs need attention', 'Settings › TVs value: 2 TVs need attention');
check(await page.locator('#tvs-status.warn').count() === 1, 'the value is amber');

// The TV list.
await page.tap('#set-main [data-page=set-tvs]');
await settle(page);
const rows = page.locator('#tv-list > .row');
check(await rows.count() === 4, 'four TVs');
check((await rows.nth(1).locator('.att-l').innerText()) === 'Needs attention · Google Drive’s sign-in ended', 'Den: its fourth line');
check(/^Off · seen 2 h ago$/.test(await rows.nth(2).locator('.s').innerText()), 'Kitchen: Off · seen 2 h ago');
check((await rows.nth(3).locator('.s.warn').innerText()) === 'Link again', 'Bedroom: Link again, amber');
check(await rows.nth(0).locator('.att-l').isHidden(), 'Living Room: no fourth line');
check(await page.locator('#tv-list > .row .chev').count() === 4, 'every row has a chevron');
check((await text(page, '#set-tvs .foot')) === 'Tap a TV for its screen, controls and health.', 'the footer');

// Den: needs attention.
await rows.nth(1).tap();
await settle(page, 1200);
check((await page.locator('header.navbar.vis .nb-tt').last().innerText()) === 'Den', 'Den’s page, titled Den');
check((await text(page, '#tvp-sum .verdict .t')) === '2 Things Need Attention', 'summary: 2 Things Need Attention');
check((await text(page, '#tvp-sum .verdict .s')) === 'Checked 3 min ago', 'Checked 3 min ago');
check(await page.locator('#tvp-sum [data-att="signin.drive"]').count() === 1 && await page.locator('#tvp-sum [data-att="update.failed"]').count() === 1, 'its two attention rows');
check((await text(page, '#tvp-body [data-grant=drive] .detail.warn')) === 'Sign-in ended', 'Google Drive: Sign-in ended, amber');
check((await text(page, '#tvp-body [data-grant=cameras] .detail')) === 'Can’t reach Google', 'Cameras: Can’t reach Google, not amber');
check(await page.locator('#tvp-body [data-grant=cameras] .detail.warn').count() === 0, 'offline words are never amber');
check((await text(page, '#tvp-body [data-k="u-try"] .detail')) === 'Didn’t install', 'Last Update Try: Didn’t install');
check(await page.locator('#tvp-body [data-k="a-smooth"]').count() === 0, 'no Smoothness Report for a linked TV');
check(await page.locator('#tvp-ctl').isVisible() && await page.locator('#tvp-back10').isHidden(), 'Play/Pause only (Den can’t seek)');

// An attention row opens its fix: a sign-in that ended explains where to sign in.
await page.tap('#tvp-sum [data-att="signin.drive"]');
await settle(page);
check((await text(page, '#sheet .as-h b')) === 'Google Drive’s sign-in ended', 'its action sheet');
await page.locator('#sheet .as-b', { hasText: 'How to Sign In' }).tap();
await settle(page);
check((await text(page, '#d-title')) === 'Sign In on Den', 'the alert: Sign In on Den');
check((await text(page, '#d-msg')).startsWith('Sign-ins aren’t shared between TVs. Use Den’s own app on this phone, or on Den open Streaming'), 'with where to do it');
await page.locator('#d-btns button', { hasText: 'OK' }).tap();
await settle(page);

// …and a failed update offers Check for Updates.
await page.tap('#tvp-sum [data-att="update.failed"]');
await settle(page);
await page.locator('#sheet .as-b', { hasText: 'Check for Updates' }).tap();
await settle(page, 600);
check((await text(page, '#toast-t')) === 'Checking for updates on Den', 'toast: Checking for updates on Den');
check((await text(page, '#tvp-body [data-k="a-check"]')) === 'Checking…', 'the row reads Checking…');
check(sent('/api/tvs/update/check').some((r) => r.body && r.body.id === 'b'), 'asked the hub to check Den');

// A crash kept on Den: its page (Copy, no Clear: that's only for the TV this phone talks to).
await page.tap('#tvp-body [data-k="st-crash"]');
await settle(page);
check((await text(page, '#crash-text')).includes('IllegalStateException'), 'Den’s crash report');
check(await page.locator('#crash-clear').isHidden(), 'no Clear for a linked TV');
check((await page.locator('header.navbar.vis .gb.back span').last().innerText()) === 'Den', 'back to Den');
await page.locator('header.navbar.vis .gb.back').last().tap();
await settle(page);
await page.locator('header.navbar.vis .gb.back').last().tap();
await settle(page);

// Kitchen: off, its last report muted, only Copy.
await rows.nth(2).tap();
await settle(page, 1200);
check((await text(page, '#tvp-sum .verdict .t')) === 'Off', 'Kitchen: Off');
check((await text(page, '#tvp-sum .verdict .s')) === 'Last seen 2 h ago', 'Last seen 2 h ago');
check((await text(page, '#tvp-body [data-k="f-off"]')) === 'Off or asleep. These are the last things it reported.', 'its footer');
check(await page.locator('#tvp-body .warn').count() === 0, 'every value muted');
check(await page.locator('#tvp-body [data-k="a-check"]').count() === 0 && await page.locator('#tvp-body [data-k="a-copy"]').count() === 1, 'only Copy Health Report');
check(await page.locator('#tvp-now').isHidden(), 'no picture for a TV that’s off');
await page.locator('header.navbar.vis .gb.back').last().tap();
await settle(page);

// Bedroom: its link stopped working. A wrong code, then the right one.
await rows.nth(3).tap();
await settle(page, 1200);
check((await text(page, '#tvp-sum .verdict .t')) === 'Its Link Stopped Working', 'Bedroom: Its Link Stopped Working');
check((await text(page, '#tvp-body .steps')).startsWith('Bedroom answered but turned down Living Room’s link.'), 'the steps');
check(/^last report\s*yesterday at /i.test(await text(page, '#tvp-body .head.prom')), 'its last report, under a heading saying when');
await page.tap('#tvp-link');
await settle(page);
check((await text(page, '#d-title')) === 'Code Shown on Bedroom', 'the code alert');
check((await page.getAttribute('#d-input', 'inputmode')) === 'numeric' && (await page.getAttribute('#d-input', 'autocomplete')) === 'one-time-code', 'a number pad, one-time code');
await page.fill('#d-input', '111111');
await page.locator('#d-btns button', { hasText: 'Link' }).tap();
await settle(page, 800);
check((await text(page, '#toast-t')) === 'That code didn’t work. Check the code on the other TV.', 'a wrong code says so');
check((await text(page, '#tvp-link')) === 'Link Again', 'the row is back to Link Again');
await settle(page, 2000);
await page.tap('#tvp-link');
await settle(page);
await page.fill('#d-input', '123 456');
await page.locator('#d-btns button', { hasText: 'Link' }).tap();
await settle(page, 1500);
check((await text(page, '#toast-t')) === 'Bedroom is linked again', 'toast: Bedroom is linked again');
check((await text(page, '#tvp-sum .verdict .t')) === 'All Good', 'the page turns into its report');
check(sent('/api/tvs/relink').some((r) => r.body && r.body.id === 'd' && r.body.code === '123456'), 'the code went to the hub, digits only');
await page.locator('header.navbar.vis .gb.back').last().tap();
await settle(page);
check(!(await rows.nth(3).innerText()).includes('Link again'), 'one Bedroom in the list, linked');
// (Settings' first page is under the list, not drawn: its words, not its rendering.)
const after = await page.locator('#tvs-status').textContent(); check(after === 'Den needs attention', 'Settings value: Den needs attention (' + after + ')');

// Living Room: the controls, Smoothness Report, Copy Health Report.
await rows.nth(0).tap();
await settle(page, 1200);
check(await page.locator('#tvp-back10').isVisible() && await page.locator('#tvp-fwd30').isVisible(), 'all three controls');
await page.tap('#tvp-play');
await settle(page, 300);
check(sent('/api/key').some((r) => r.body && r.body.key === 'playpause'), 'Play/Pause pressed the TV’s key');
check((await page.getAttribute('#tvp-play', 'aria-label')) === 'Play', 'the glyph flipped at once');
await page.tap('#tvp-fwd30');
await settle(page, 300);
check(sent('/api/media').some((r) => r.body && r.body.action === 'fwd30'), '+30 s');
await page.tap('#tvp-body [data-k="a-copy"]');
await settle(page, 500);
check((await text(page, '#toast-t')) === 'Health report copied', 'toast: Health report copied');
const clip = await page.evaluate(() => navigator.clipboard.readText());
check(clip.startsWith('Living Room · Version 2.0.171\nChecked ') && clip.includes('\nAll good.\n') && clip.includes('Keys: all saved'), 'the copied report');
check(!/\d+\.\d+\.\d+\.\d+|@/.test(clip.replace('2.0.171', '')), 'no addresses or e-mails in it');
await page.tap('#tvp-body [data-k="a-smooth"]');
await settle(page);
check((await page.locator('header.navbar.vis .gb.back span').last().innerText()) === 'Living Room', 'Smoothness Report, back to Living Room');

// A deep link opens a TV's page with TVs and Settings under it.
await page.goto(base + '#settings/tvs/b');
await settle(page, 1500);
check((await text(page, '#tvp-sum .verdict .t')) === '2 Things Need Attention', '#settings/tvs/b opens Den');
check((await page.locator('header.navbar.vis .gb.back span').last().innerText()) === 'TVs', 'with TVs under it');
// A TV the hub doesn't link any more (a banner's old link, or unlinked meanwhile): said once, then back to the list,
// never "Can’t Reach Living Room" for a hub that answered.
await page.goto(base + '#settings/tvs/gone-tv');
await settle(page, 2000);
check((await text(page, '#toast-t')) === 'That TV isn’t linked any more.', 'a TV that isn’t linked any more says so');
check((await page.locator('header.navbar.vis .nb-tt').last().innerText()) === 'TVs', 'and its page gives way to the TV list');

// A long name, at 375 pt: one TV needing attention never cuts the sentence.
await fetch(base + '__mock/set', { method: 'POST', body: JSON.stringify({ tvs: [
  { id: 'this', name: 'Living Room', online: true, onScreen: true, link: 'ok', health: { status: 'good', count: 0, first: null }, playing: null },
  { id: 'b', name: 'Upstairs Guest Bedroom TV', online: true, onScreen: false, link: 'ok', health: { status: 'attention', count: 1, first: 'Google Drive’s sign-in ended' }, playing: null },
] }) });
await page.setViewportSize({ width: 375, height: 760 });
await page.goto(base + '#settings');
await settle(page, 1500);
check((await text(page, '#tvs-status')) === '1 TV needs attention', 'a long name: 1 TV needs attention');
await page.goto(base + '#settings/tvs/b');
await settle(page, 1500);
const wide = await page.evaluate(() => { const s = document.querySelector('#set-tv .scroller'); return s.scrollWidth - s.clientWidth; });
check(wide <= 0, 'no sideways scroll at 375 pt');
await fetch(base + '__mock/reset', { method: 'POST' });

check(errors.length === 0, 'no script errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
await browser.close();
console.log(failures ? failures + ' failed' : 'all passed');
process.exit(failures ? 1 : 0);
