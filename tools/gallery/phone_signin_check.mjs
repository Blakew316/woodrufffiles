// Checks that signing the TV in from the phone works, not only that it draws: Connections' words and Sign In rows in
// every state, the Sign In sheet through each of its states (the request it makes, the real link to Google, the polls,
// Start Again, Try Again, Done, the keys and project first), every way to close it, the Tailscale variant, the deep link,
// a banner whose tap opens its link (once per message), the TV page's fixes for this TV and for another one (which never
// asks for a sign-in), Watch's Sign In, a long TV name, Reduce Motion and 375 pt. Against mock_server.py with MOCK_LOG
// set (it reads what the page sent).
// Usage: MOCK_LOG=/tmp/mock.log MOCK_PORT=8765 python3 mock_server.py <web> &  node phone_signin_check.mjs [base] [log]
import { chromium, devices } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';

const base = process.argv[2] || 'http://127.0.0.1:8765/';
const logFile = process.argv[3] || process.env.MOCK_LOG;
const port = new URL(base).port;
let failures = 0;
function check(ok, what) { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failures++; }
function sent(path) {   // the requests the page made to [path] since the last clear
  if (!logFile) return [];
  return readFileSync(logFile, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)).filter((r) => r.path === path);
}
function clear() { if (logFile) writeFileSync(logFile, ''); }
const settle = (page, ms = 900) => page.waitForTimeout(ms);
const text = (page, sel) => page.locator(sel).first().innerText();
const shown = async (page, sel) => (await page.locator(sel).count()) > 0 && page.locator(sel).first().isVisible();
const mock = (what, body) => fetch(base + '__mock/' + what, { method: 'POST', body: JSON.stringify(body || {}) });
const sheetOpen = (page) => page.evaluate(() => document.getElementById('si').classList.contains('show'));

// A host name for the Tailscale variant (*.ts.net), resolved to the mock.
const browser = await chromium.launch({ args: ['--host-resolver-rules=MAP living-room.example.ts.net 127.0.0.1'] });
const iphone = devices['iPhone 13'];
const context = await browser.newContext({ ...iphone, viewport: { width: 390, height: 797 } });
await context.addInitScript(() => {
  if (!/^http/.test(location.protocol)) return;   // (about:blank has no storage)
  localStorage.setItem('woodruff.device', JSON.stringify({ id: 'p-check', name: 'Check iPhone', alerts: false }));
  localStorage.setItem('woodruff.installHint', '1');
  Object.defineProperty(navigator, 'standalone', { get: () => true });
});
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
async function fresh(hash, state) {
  await mock('reset');
  if (state) await mock('set', state);
  await page.goto(base + hash);
  await page.reload();
  await settle(page, 1100);
}
async function toDrive(state) {
  await fresh('#settings', state);
  await page.tap('#set-main [data-page=set-conn]'); await settle(page);
  await page.tap('#set-conn [data-page=set-drive]'); await settle(page);
}

// ---- Connections: the TV's Accounts words, and Sign In rows exactly when signing in can work ----------------------
await fresh('#settings');
check((await text(page, '#conn-status')) === '4 On', 'the Connections summary is as before (4 On)');
await page.tap('#set-main [data-page=set-conn]');
await settle(page);
check((await text(page, '#g-status')) === 'Signed in' && await page.locator('#g-status.warn').count() === 0, 'Google Drive: Signed in, not amber');
check((await text(page, '#c-status')) === 'Connected', 'Cameras: Connected');
for (const [state, words, warn, row] of [
  ['expired', 'Sign-in ended', true, 'Sign In to Google Drive Again'],
  ['notSignedIn', 'Not signed in', false, 'Sign In to Google Drive'],
  ['notSetUp', 'Not set up', false, null],
  ['keysRejected', 'Keys not accepted', true, null],
]) {
  await toDrive({ settings: { google: { state } } });
  check((await text(page, '#g-status2')) === words && (await page.locator('#g-status2.warn').count() === 1) === warn, 'Drive ' + state + ': ' + words + (warn ? ' (amber)' : ''));
  check(row ? (await shown(page, '#g-signin')) && (await text(page, '#g-signin')) === row : !(await shown(page, '#g-signin')), 'Drive ' + state + ': ' + (row || 'no Sign In row'));
  check((await shown(page, '#g-foot')) === (state === 'keysRejected'), 'Drive ' + state + ': the keys footer ' + (state === 'keysRejected' ? 'shows' : 'is hidden'));
}
check((await text(page, '#g-foot')) === 'Google doesn’t accept the Client ID or secret. Check them above, then sign in.', 'the keys footer’s words');
for (const [nest, words, row] of [
  [{ state: 'expired' }, 'Sign-in ended', 'Connect Your Cameras Again'],
  [{ state: 'notSignedIn' }, 'Not connected', 'Connect Your Cameras'],
  [{ state: 'notSetUp', project: '' }, 'Not connected', null],
  [{ state: 'keysRejected' }, 'Keys not accepted', null],
]) {
  await fresh('#settings', { settings: { nest } });
  await page.tap('#set-main [data-page=set-conn]'); await settle(page);
  await page.tap('#set-conn [data-page=set-cams]'); await settle(page);
  check((await text(page, '#c-status2')) === words, 'Cameras ' + nest.state + ': ' + words);
  check(row ? (await shown(page, '#c-signin')) && (await text(page, '#c-signin')) === row : !(await shown(page, '#c-signin')), 'Cameras ' + nest.state + ': ' + (row || 'no Connect row'));
}
await page.tap('[data-page=set-cams-help]'); await settle(page);
check((await text(page, '#set-cams-help .steps')).includes('then tap Connect Your Cameras (or open the Command Center on the TV and scan the code).'), 'Cameras help: step 3');
await toDrive();
await page.tap('[data-page=set-drive-help]'); await settle(page);
check((await text(page, '#set-drive-help .steps')).includes('then tap Sign In to Google Drive (or scan the code on the TV).'), 'Drive help: step 3');

// New keys saved: signed out on the TV, so the toast says what's next and the row glides in.
await toDrive();
await page.fill('#g-id', '5678-new.apps.googleusercontent.com');
await page.fill('#g-secret', 'GOCSPX-new');
await page.tap('#g-save');
await settle(page, 700);
check((await text(page, '#toast-t')) === 'Saved. Now sign in to Google Drive', 'after new keys: Saved. Now sign in to Google Drive');
check(await shown(page, '#g-signin') && (await text(page, '#g-signin')) === 'Sign In to Google Drive', 'and the Sign In row');

// ---- The sheet: ready, waiting, finishing, done -------------------------------------------------------------------
await toDrive({ settings: { google: { state: 'expired' } } });
clear();
await page.tap('#g-signin');
await settle(page, 1000);
check(await sheetOpen(page), 'Sign In opens the sheet');
check(sent('/api/signin').length === 1 && sent('/api/signin')[0].body.grant === 'drive', 'it asked the TV for an attempt (drive)');
check((await text(page, '#si .stt b')) === 'Sign In' && (await text(page, '#si-tv')) === 'Living Room', 'the bar: Sign In · Living Room');
check((await text(page, '#si-t')) === 'Sign In to Google Drive Again', 'title: Sign In to Google Drive Again');
check((await text(page, '#si-b')) === 'Google ended Living Room’s sign-in. Sign in with the same Google account and leave every box ticked. Your library and progress are kept.', 'the words after an ending');
check((await text(page, '#si-go')) === 'Continue to Google', 'Continue to Google');
check((await page.getAttribute('#si-go', 'href')) === 'http://127.0.0.1:' + port + '/__mock/google' && (await page.getAttribute('#si-go', 'target')) === '_blank'
  && (await page.getAttribute('#si-go', 'rel')) === 'noopener noreferrer', 'a real link to Google, in a new tab, no opener');
check((await text(page, '#si-note')) === 'Works on home Wi-Fi only.', 'Works on home Wi-Fi only.');
check(await page.locator('#si-ico svg').count() === 1 && !(await page.evaluate(() => document.getElementById('si-ico').classList.contains('done'))), 'the Google Drive square');
await page.waitForTimeout(2300);
const polled = sent('/api/signin/status');
check(polled.length >= 1 && polled[0].query.state[0] === 'mock-' + (polled[0].query.state[0].split('-')[1]) && polled[0].query.grant[0] === 'drive', 'it asks the TV how it’s going (state and grant)');
const [google] = await Promise.all([context.waitForEvent('page'), page.tap('#si-go')]);
await settle(page);
check(google.url() === 'http://127.0.0.1:' + port + '/__mock/google', 'Continue to Google opened Google’s page');
check((await text(page, '#si-b')) === 'Finish on Google’s page, then come back here.', 'waiting: Finish on Google’s page, then come back here.');
check((await text(page, '#si-go')) === 'Open Google Again' && await page.locator('#si-go.tinted').count() === 1, 'waiting: Open Google Again (tinted)');
check(await shown(page, '#si-wait') && (await text(page, '#si-wait .w')) === 'Waiting for Google…' && (await text(page, '#si-again')) === 'Start Again', 'waiting: Waiting for Google… and Start Again');
await google.close();
// Start Again: a new attempt, and the polls follow it.
clear();
await page.tap('#si-again');
await settle(page, 2800);
const again = sent('/api/signin'), againPolls = sent('/api/signin/status');
check(again.length === 1 && (await text(page, '#si-go')) === 'Continue to Google', 'Start Again: a new attempt, ready again');
check(againPolls.length >= 1 && againPolls.every((r) => r.query.state[0] !== polled[0].query.state[0]), 'and the polls ask about the new one');
await mock('set', { signin: 'finishing' });
await page.waitForTimeout(2300);
check((await text(page, '#si-t')) === 'Signing In…' && (await text(page, '#si-b')) === 'Living Room is finishing with Google.', 'finishing: Signing In… / Living Room is finishing with Google.');
check(!(await shown(page, '#si-go')) && !(await shown(page, '#si-act')) && await shown(page, '#si-fin'), 'finishing: no button, a spinner');
await mock('set', { signin: 'done' });
await page.waitForTimeout(2400);
check((await text(page, '#si-t')) === 'Living Room Is Signed In' && (await text(page, '#si-b')) === 'Google Drive is back on Living Room.', 'done: Living Room Is Signed In / Google Drive is back on Living Room.');
check(await page.evaluate(() => document.getElementById('si-ico').classList.contains('done')) && !(await shown(page, '#si-b2')), 'done: the green check, no weekly tip');
check((await text(page, '#si-act')) === 'Done', 'done: Done');
await settle(page, 600);
check((await text(page, '#g-status2')) === 'Signed in' && !(await shown(page, '#g-signin')), 'under the sheet, Google Drive now says Signed in (no Sign In row)');
clear();
await page.waitForTimeout(4500);
check(sent('/api/signin/status').length === 0, 'no more polls once it’s done');
await page.tap('#si-act');
await settle(page);
check(!(await sheetOpen(page)), 'Done closes the sheet');

// Signed in, and Google ended it twice within 8 days: the Testing tip.
await toDrive({ settings: { google: { state: 'expired' } }, signin: 'weekly' });
await page.tap('#g-signin');
await page.waitForTimeout(3000);
check((await text(page, '#si-b2')) === 'Google ends sign-ins every 7 days while your Google Cloud consent screen is in Testing. To stop that, publish it (Google Cloud → OAuth consent screen → Publish app).', 'weekly: the Testing tip');

// It didn't finish: each reason in the TV's words, and Try Again makes a new attempt.
for (const [mode, body] of [
  ['failed', 'Living Room couldn’t reach Google. Try again.'],
  ['cancelled', 'Google’s sign-in was cancelled. Try again when you’re ready.'],
  ['replaced', 'Someone started signing in again, on another phone or the TV. Try again here if you still need to.'],
  ['expired', 'Google didn’t send an answer. Check this phone is on the home Wi-Fi, then try again.'],
]) {
  await toDrive({ settings: { google: { state: 'expired' } }, signin: mode });
  await page.tap('#g-signin');
  await page.waitForTimeout(3000);
  check((await text(page, '#si-t')) === 'Signing In Didn’t Finish' && (await text(page, '#si-b')) === body && (await text(page, '#si-act')) === 'Try Again', mode + ': ' + body);
}
clear();
await mock('set', { signin: null });
await page.tap('#si-act');
await settle(page, 700);
check(sent('/api/signin').length === 1 && (await text(page, '#si-go')) === 'Continue to Google', 'Try Again: a new attempt, ready');

// What comes first: the keys (Set Up Google Drive goes there), the cameras' project, or a TV that doesn't answer.
await toDrive({ settings: { google: { state: 'expired' } }, signin: 'keys' });
await page.tap('#g-signin');
await settle(page, 1000);
check((await text(page, '#si-t')) === 'Set Up Google Keys First' && (await text(page, '#si-b')) === 'Living Room needs the Google Client ID and secret before it can sign in. They’re entered once and copied to every TV.', 'keys first: its words');
await page.tap('#si-act');
await settle(page);
check(!(await sheetOpen(page)) && (await page.locator('header.navbar.vis .nb-tt').last().innerText()) === 'Google Drive', 'Set Up Google Drive: the sheet closes on the Google Drive page');
await fresh('#settings', { settings: { nest: { state: 'notSignedIn' } }, signin: 'project' });
await page.tap('#set-main [data-page=set-conn]'); await settle(page);
await page.tap('#set-conn [data-page=set-cams]'); await settle(page);
clear();
await page.tap('#c-signin');
await settle(page, 1000);
check(sent('/api/signin')[0].body.grant === 'cameras', 'Connect Your Cameras asks for the cameras’ sign-in');
check((await text(page, '#si-t')) === 'Add the Cameras Project First' && (await text(page, '#si-b')) === 'Cameras need the Device Access Project ID. Add it once and every TV gets it.' && (await text(page, '#si-act')) === 'Open Cameras Settings', 'project first: its words');
await page.tap('#si-act');
await settle(page);
check(!(await sheetOpen(page)) && (await page.locator('header.navbar.vis .nb-tt').last().innerText()) === 'Cameras', 'Open Cameras Settings: the Cameras page');
await toDrive({ settings: { google: { state: 'notSignedIn' } }, signin: 'down' });
await page.tap('#g-signin');
await settle(page, 1000);
check((await text(page, '#si-t')) === 'Sign In to Google Drive' && (await text(page, '#si-b')) === 'Couldn’t start signing in. Check that Living Room is on.' && (await text(page, '#si-act')) === 'Try Again', 'couldn’t start: its words (first time: no Again)');

// Every way out closes it, and the polls stop.
for (const [how, act] of [
  ['✕', async () => page.tap('#si-close')],
  ['Escape', async () => page.keyboard.press('Escape')],
  ['the veil', async () => page.mouse.click(195, 60)],
  ['browser Back', async () => page.goBack()],
]) {
  await toDrive({ settings: { google: { state: 'expired' } } });
  await page.tap('#g-signin');
  await settle(page, 1000);
  await act();
  await settle(page);
  clear();
  await page.waitForTimeout(2500);
  check(!(await sheetOpen(page)) && sent('/api/signin/status').length === 0, how + ' closes the sheet, and nothing more is asked');
}

// Reached through Tailscale: Sign In at Home (the button still works for someone at home).
await mock('reset');
await mock('set', { settings: { google: { state: 'expired' } } });
const ts = await context.newPage();
ts.on('pageerror', (e) => errors.push(e.message));
await ts.goto('http://living-room.example.ts.net:' + port + '/#settings/drive');
await settle(ts, 1300);
await ts.tap('#g-signin');
await settle(ts, 1000);
check((await text(ts, '#si-t')) === 'Sign In at Home', 'Tailscale: Sign In at Home');
check((await text(ts, '#si-b')) === 'Signing in only works on the home Wi-Fi: Google sends its answer straight to Living Room. Connect this phone to the home Wi-Fi, then try.', 'Tailscale: its words');
check((await text(ts, '#si-note')) === 'This phone reaches Living Room through Tailscale right now.' && await ts.locator('#si-go.tinted').count() === 1 && (await ts.getAttribute('#si-go', 'href')) !== null, 'Tailscale: its note, Continue to Google tinted and still a link');
await ts.close();

// The banner's link: this TV's page with its Sign In sheet; the same message again isn't shown twice.
await fresh('#remote', { settings: { google: { state: 'expired' } } });
const msg = { title: 'Sign in to Google Drive again', body: 'Google ended Living Room’s sign-in. Tap to sign it in again from this phone.', from: 'Living Room', link: '#settings/tvs/this/signin/drive', id: 'signin:this:drive:42' };
await mock('inbox', msg);
await page.waitForTimeout(5600);
check(await shown(page, '#alert.show') && (await text(page, '#alert-from')) === 'From Living Room' && (await text(page, '#alert-title')) === 'Sign in to Google Drive again', 'the banner: From Living Room · Sign in to Google Drive again');
await page.tap('#alert');
await settle(page, 1300);
check(!(await shown(page, '#alert.show')), 'a tap puts it away');
check((await page.locator('header.navbar.vis .nb-tt').last().innerText()) === 'Living Room' && await sheetOpen(page) && (await text(page, '#si-t')) === 'Sign In to Google Drive Again', 'and opens Living Room’s page with the Sign In sheet');
await page.tap('#si-close'); await settle(page);
await mock('inbox', msg);
await page.waitForTimeout(5600);
check(!(await shown(page, '#alert.show')), 'the same message again isn’t shown twice');
await mock('inbox', { title: 'Dinner’s ready', body: 'Come down', from: 'Living Room' });
await page.waitForTimeout(5600);
const route0 = await page.evaluate(() => location.hash);
await page.tap('#alert');
await settle(page);
check(!(await shown(page, '#alert.show')) && (await page.evaluate(() => location.hash)) === route0 && !(await sheetOpen(page)), 'a banner without a link only goes away');

// The deep link on its own (opened afresh: a sheet isn't kept in the address, so a reload shows the page alone).
await mock('reset');
await mock('set', { settings: { nest: { state: 'expired' } } });
await page.goto('about:blank');
await page.goto(base + '#settings/tvs/this/signin/cameras');
await settle(page, 1700);
check((await page.locator('header.navbar.vis .nb-tt').last().innerText()) === 'Living Room' && await sheetOpen(page) && (await text(page, '#si-t')) === 'Connect Your Cameras Again', '#settings/tvs/this/signin/cameras: the page and the sheet');

// This TV's page: its ended sign-in's fix and row open the sheet; another TV's only say where (never a sign-in request).
await fresh('#settings', { thisSignIns: { drive: 'expired', photos: 'expired' } });
await page.tap('#set-main [data-page=set-tvs]'); await settle(page);
await page.tap('#tv-list .row:nth-child(1)'); await settle(page, 1300);
await page.tap('#tvp-sum [data-att="signin.drive"]'); await settle(page);
check((await text(page, '#sheet .as-h b')) === 'Google Drive’s sign-in ended', 'this TV: the fix sheet');
check(await page.locator('#sheet .as-b', { hasText: 'Sign In Again' }).count() === 1 && await page.locator('#sheet .as-b', { hasText: 'How to Sign In' }).count() === 0, 'this TV: Sign In Again');
await page.locator('#sheet .as-b', { hasText: 'Sign In Again' }).tap();
await settle(page, 1100);
check(await sheetOpen(page) && (await text(page, '#si-t')) === 'Sign In to Google Drive Again', 'Sign In Again opens the sheet');
await page.tap('#si-close'); await settle(page);
await page.tap('#tvp-sum [data-att="signin.photos"]'); await settle(page);
check(await page.locator('#sheet .as-b', { hasText: 'Connect Again' }).count() === 1, 'Photos: Connect Again');
await page.locator('#sheet .as-b', { hasText: 'Cancel' }).tap(); await settle(page);
check((await page.getAttribute('#tvp-signins [data-grant="photos"]', 'aria-haspopup')) === 'dialog', 'the sign-in row says it opens a sheet');
await page.tap('#tvp-signins [data-grant="photos"]');
await settle(page, 1100);
check(await sheetOpen(page) && (await text(page, '#si-t')) === 'Connect Google Photos Again', 'the Photos row opens its sheet');
check((await text(page, '#si-b')) === 'Google ended Living Room’s sign-in. Sign in with the same Google account and leave every box ticked.', 'Photos: no word of a library to keep');
await mock('set', { signin: 'done' });
await page.waitForTimeout(2600);
check((await text(page, '#si-b')) === 'To add photos, open Family Memories on Living Room and choose Add from Google Photos.', 'Photos done: where to add photos');
await page.tap('#si-act'); await settle(page, 1500);
await page.locator('header.navbar.vis .gb.back').last().tap(); await settle(page);
clear();
await page.tap('#tv-list .row:nth-child(2)'); await settle(page, 1300);
await page.tap('#tvp-signins [data-grant="drive"]'); await settle(page);
check((await text(page, '#d-title')) === 'Sign In on Den', 'Den’s row: Sign In on Den');
check((await text(page, '#d-msg')) === 'Sign-ins aren’t shared between TVs. Use Den’s own app on this phone, or on Den open Streaming and scan the code with your phone. No Den app yet? On Den, open Settings → TVs & phones → Connect a phone.', 'with exactly where to do it');
await page.locator('#d-btns button', { hasText: 'OK' }).tap(); await settle(page);
await page.tap('#tvp-sum [data-att="signin.drive"]'); await settle(page);
check(await page.locator('#sheet .as-b', { hasText: 'How to Sign In' }).count() === 1 && await page.locator('#sheet .as-b', { hasText: 'Sign In Again' }).count() === 0, 'Den’s fix: How to Sign In');
await page.locator('#sheet .as-b', { hasText: 'Cancel' }).tap(); await settle(page);
check(sent('/api/signin').length === 0 && sent('/api/signin/status').length === 0, 'nothing for another TV ever asks for a sign-in');

// Watch: Sign In opens the sheet for Google Drive.
await fresh('#watch', { library: 'expiredEmpty', settings: { google: { state: 'expired' } } });
await settle(page, 1500);
await page.tap('#w-cuv-b');
await settle(page, 1100);
check(await sheetOpen(page) && (await text(page, '#si-t')) === 'Sign In to Google Drive Again', 'Watch’s Sign In opens the sheet');
await page.tap('#si-close'); await settle(page);

// A long TV name: titles two lines at most, the bar's name one line, nothing sideways; at 375 pt too.
await page.setViewportSize({ width: 375, height: 667 });
await toDrive({ settings: { google: { state: 'expired' } }, signinTv: 'Upstairs Guest Bedroom TV', signin: 'done' });
await page.tap('#g-signin');
await page.waitForTimeout(3000);
const lines = await page.evaluate(() => { const t = document.getElementById('si-t'); return Math.round(t.getBoundingClientRect().height / parseFloat(getComputedStyle(t).lineHeight)); });
check((await text(page, '#si-t')) === 'Upstairs Guest Bedroom TV Is Signed In' && lines <= 2, 'long name: the title fits in two lines');
const small = await page.evaluate(() => { const s = document.getElementById('si-tv'); return s.scrollWidth <= s.clientWidth + 1 || getComputedStyle(s).textOverflow === 'ellipsis'; });
const wide = await page.evaluate(() => { const s = document.getElementById('si'); return s.scrollWidth - s.clientWidth + (document.documentElement.scrollWidth - document.documentElement.clientWidth); });
check(small && wide <= 0, 'long name at 375 pt: the bar’s name ends in an ellipsis, nothing scrolls sideways');
const inView = await page.evaluate(() => { const r = document.getElementById('si').getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight; });
check(inView, 'the sheet fits on a 375 × 667 screen');
await page.setViewportSize({ width: 390, height: 797 });

// Reduce Motion: it still opens and closes (cross-fades).
await page.emulateMedia({ reducedMotion: 'reduce' });
await toDrive({ settings: { google: { state: 'expired' } } });
await page.tap('#g-signin');
await settle(page, 1000);
check(await sheetOpen(page) && (await text(page, '#si-go')) === 'Continue to Google', 'Reduce Motion: the sheet opens');
await page.tap('#si-close');
await settle(page, 800);
check(!(await sheetOpen(page)), 'Reduce Motion: and closes');
await page.emulateMedia({ reducedMotion: 'no-preference' });

check(errors.length === 0, 'no script errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
await mock('reset');
await browser.close();
console.log(failures ? failures + ' failed' : 'all passed');
process.exit(failures ? 1 : 0);
