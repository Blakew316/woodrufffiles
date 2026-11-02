// Checks the phone polish (L4-5) works, not only that it draws: the keyboard types into the TV that's picked (one text
// at a time, in order, Enter last; another TV starts it again; a TV that can't take typing says why), the Remote says
// when the TV can't be reached (dropped connections, and requests that never answer) and stops saying it when it's
// back, messages arrive once each and in turn (four at once are one banner) and stay under Settings › This Phone through
// a reload, and a video too big for the room the TV has is turned down on the phone before anything is sent. Against
// mock_server.py with MOCK_LOG set (it reads what the page sent).
// Usage: MOCK_LOG=/tmp/mock.log MOCK_PORT=8765 python3 mock_server.py <web> &  node phone_polish_check.mjs [base] [log]
import { chromium, devices } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';

const base = process.argv[2] || 'http://127.0.0.1:8765/';
const logFile = process.argv[3] || process.env.MOCK_LOG;
let failures = 0;
function check(ok, what) { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failures++; }
function sent(path, method) {   // the requests the page made to [path] since the last clear
  if (!logFile) return [];
  return readFileSync(logFile, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)).filter((r) => r.path === path && (!method || r.method === method));
}
function clear() { if (logFile) writeFileSync(logFile, ''); }
const settle = (page, ms = 900) => page.waitForTimeout(ms);
const text = (page, sel) => page.locator(sel).first().innerText({ timeout: 5000 });
const shown = async (page, sel) => (await page.locator(sel).count()) > 0 && page.locator(sel).first().isVisible();
const mock = (what, body) => fetch(base + '__mock/' + what, { method: 'POST', body: JSON.stringify(body || {}) });
const typed = async () => (await fetch(base + '__mock/typed')).json();
async function until(page, test, ms = 15000) {   // waits (up to ms) for test() to be true; how long it took, or -1
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { if (await test()) return Date.now() - t0; await page.waitForTimeout(200); }
  return -1;
}

const browser = await chromium.launch();
const iphone = devices['iPhone 13'];
const context = await browser.newContext({ ...iphone, viewport: { width: 390, height: 797 } });
await context.addInitScript(() => {
  if (!/^http/.test(location.protocol)) return;   // (about:blank has no storage)
  if (!localStorage.getItem('woodruff.device')) localStorage.setItem('woodruff.device', JSON.stringify({ id: 'p-check', name: 'Check iPhone', alerts: false }));
  localStorage.setItem('woodruff.installHint', '1');
  Object.defineProperty(navigator, 'standalone', { get: () => true });
});
const page = await context.newPage();
const errors = [], asked = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('request', (r) => { if (r.url().indexOf('/api/inbox') >= 0) asked.push(new URL(r.url())); });   // (polls aren't in the mock's log)
async function fresh(hash, state) {
  await mock('reset');
  if (state) await mock('set', state);
  await page.goto(base + (hash || ''));
  await page.reload();
  await settle(page, 1300);
}
async function pick(name) {
  await page.tap('#rm-tv'); await settle(page, 600);
  await page.locator('#menu button', { hasText: name }).first().tap();
}
const card = async () => (await shown(page, '#rm-reach')) ? (await text(page, '#rm-reach-t')) + ' / ' + (await text(page, '#rm-reach-s')) : '';
const banner = async () => (await page.evaluate(() => document.getElementById('alert').classList.contains('show')))
  ? [await text(page, '#alert-from'), await text(page, '#alert-title'), await page.locator('#alert-body').innerText()] : null;
const attach = (sel, name, type, mb) => page.evaluate(([sel, name, type, mb]) => {
  const f = new File([new Uint8Array(Math.round(mb * 1048576))], name, { type }); const dt = new DataTransfer(); dt.items.add(f);
  const input = document.querySelector(sel); input.files = dt.files; input.dispatchEvent(new Event('change'));
}, [sel, name, type, mb]);

// ---- The keyboard types into the TV that's picked (finding 42) ---------------------------------------------------
await fresh('#remote');
check(await shown(page, '#rm-kb-hint') && (await text(page, '#rm-kb-hint-t')) === 'Type into Search file names', 'this TV: Type into its own text box');
await pick('Den');
check(await until(page, async () => (await shown(page, '#rm-kb-hint')) && (await text(page, '#rm-kb-hint-t')) === 'Type into Search Movies & TV Shows', 5000) >= 0,
  'Den picked: the pill offers Den’s own text box (through Living Room)');
await page.tap('#rm-kb-hint'); await settle(page, 700);
check((await text(page, '#kb-status')) === 'Search Movies & TV Shows' && (await page.locator('#kb-dot.live').count()) === 1, 'the keyboard names Den’s box, live');
clear();
await mock('set', { typeDelay: 700 });   // Den slow to answer: letters pile up meanwhile
for (const ch of 'midnight harbor') { await page.keyboard.type(ch); await page.waitForTimeout(170); }
await page.waitForTimeout(2600);
let posts = sent('/api/tvs/type', 'POST');
const texts = posts.map((r) => r.body.text);
check(posts.length >= 2 && posts.length < 'midnight harbor'.length, 'one text at a time: ' + posts.length + ' requests for 15 letters typed while Den was slow');
check(texts.every((t, i) => i === 0 || t.length > texts[i - 1].length) && texts[texts.length - 1] === 'midnight harbor', 'each request newer than the last, the last the whole text');
check(posts.every((r) => r.body.id === 'b' && r.body.field === 41 && r.body.submit === false), 'each names Den and its box');
await mock('set', { typeDelay: 0 });
await page.keyboard.press('Enter'); await settle(page, 1200);
let all = await typed();
check(all.length && all[all.length - 1].submit === true && all[all.length - 1].text === 'midnight harbor' && all[all.length - 1].tv === 'b', 'Enter goes last, with the text');
check((await text(page, '#toast-t')) === 'Sent to Den', 'the toast names Den');
check(!(await page.evaluate(() => document.getElementById('kb').classList.contains('show'))), 'the keyboard closes after Enter');
check(!all.some((t) => t.tv === 'this'), 'nothing typed for Den went to Living Room');
// Another TV: the keyboard starts again on its own box.
await page.tap('#kb-open2'); await settle(page, 700);
await page.keyboard.type('abc'); await settle(page, 600);
await page.tap('#kb-close'); await settle(page, 700);
await pick('Living Room'); await settle(page, 400);
check((await page.inputValue('#kb-text')) === 'vacation' || (await page.inputValue('#kb-text')) === '', 'another TV picked: what was typed for Den isn’t kept');
check(await until(page, async () => (await text(page, '#rm-kb-hint-t')) === 'Type into Search file names', 5000) >= 0, 'back on Living Room: its own box');
// Bedroom turns its link down: the keyboard says why, and nothing offers to type there.
await pick('Bedroom'); await settle(page, 2600);
await page.tap('#kb-open2'); await settle(page, 1500);
check((await text(page, '#kb-status')) === 'Bedroom’s link stopped working. Link it again in Settings → TVs.', 'Bedroom: the keyboard says its link stopped working');
check(!(await shown(page, '#rm-kb-hint')), 'Bedroom: no Type into pill');
await page.tap('#kb-close'); await settle(page, 600);
// This TV's own box: the text and the box's id go to /api/type.
await fresh('#remote');
clear();
await page.tap('#rm-kb-hint'); await settle(page, 700);
check((await page.inputValue('#kb-text')) === 'vacation', 'the keyboard starts with the text already in the TV’s box');
await page.keyboard.press('End'); await page.keyboard.type(' 2025'); await settle(page, 900);
posts = sent('/api/type', 'POST');
check(posts.length >= 1 && posts[posts.length - 1].body.id === 1 && posts[posts.length - 1].body.text === 'vacation 2025', 'this TV: the whole text with its box’s id');

// ---- The Remote says when the TV can't be reached (finding 45) -------------------------------------------------------
await fresh('#remote');
check(await card() === '', 'nothing said while the TV answers');
await mock('set', { down: true });
let took = await until(page, async () => (await card()) !== '', 12000);
check(took >= 0 && took < 9000, 'the TV stops answering: said within ' + took + ' ms');
check(await card() === 'Can’t Reach Living Room / Check that it’s on and this phone is on the same Wi-Fi.', 'its words: ' + await card());
check(!(await shown(page, '#rm-kb-hint')) && !(await shown(page, '#rm-tv-dot')), 'no Type into pill, no green dot, while it can’t be reached');
await page.tap('[data-key=up]'); await settle(page, 1200);
check((await text(page, '#toast-t')) === 'Can’t reach Living Room', 'a key press says so in plain words (not “Load failed”)');
await page.tap('#kb-open2'); await settle(page, 2600);
check((await text(page, '#kb-status')) === 'Can’t reach Living Room' && (await page.locator('#kb-dot.live').count()) === 0, 'the keyboard says so too');
await page.tap('#kb-close'); await settle(page, 600);
await mock('set', { down: false });
took = await until(page, async () => (await card()) === '', 12000);
check(took >= 0 && took < 6000, 'back: gone again within ' + took + ' ms');
check(await until(page, () => shown(page, '#rm-kb-hint'), 5000) >= 0, 'back: the Type into pill returns');
// A TV that never answers (asleep, over Tailscale): requests give up by themselves and it's said all the same.
await mock('set', { hang: true });
took = await until(page, async () => (await card()) !== '', 30000);
check(took >= 0 && took < 22000, 'requests that never answer give up: said within ' + took + ' ms');
await mock('set', { hang: false });
check(await until(page, async () => (await card()) === '', 20000) >= 0, 'and gone once it answers');
// The picked TV is the one Living Room can't reach (or whose link stopped working).
await pick('Kitchen');
check(await until(page, async () => (await card()) === 'Can’t Reach Kitchen / Check that it’s on.', 8000) >= 0, 'Kitchen (off): Can’t Reach Kitchen');
await pick('Bedroom'); await settle(page, 1200);
check(await card() === 'Can’t Reach Bedroom / Its link stopped working. Link it again in Settings → TVs.', 'Bedroom: its link stopped working');
await pick('Den'); await settle(page, 1500);
check(await card() === '', 'Den (on): nothing said');

// ---- Messages to this phone never vanish (finding 43) ----------------------------------------------------------------
await page.evaluate(() => { localStorage.removeItem('woodruff.inbox'); localStorage.removeItem('woodruff.messages'); localStorage.removeItem('woodruff.seen'); });
await fresh('#remote');
clear();
await mock('inbox', { title: 'Dinner’s ready', from: 'Kitchen iPad' });
check(await until(page, async () => JSON.stringify(await banner()) === JSON.stringify(['From Kitchen iPad', 'Dinner’s ready', '']), 8000) >= 0, 'a message shows on the banner');
check(asked.length > 0 && asked.every((u) => u.searchParams.has('after') && u.searchParams.has('epoch')), 'the phone asks after the last one it has, with the inbox’s name');
await page.waitForTimeout(9000);
check(await banner() === null, 'it goes after 8 s');
check(asked[asked.length - 1].searchParams.get('epoch') === 'mock1' && +asked[asked.length - 1].searchParams.get('after') > 0, 'and asks after the number it was given');
await mock('inbox', [{ title: 'Movie night at 8', from: 'Den' }, { title: 'Bring the dog in', from: 'Kitchen iPad' }]);
check(await until(page, async () => ((await banner()) || [])[1] === 'Movie night at 8', 8000) >= 0, 'two at once: the first');
took = await until(page, async () => ((await banner()) || [])[1] === 'Bring the dog in', 8000);
check(took >= 3000, 'then the second, in turn (' + took + ' ms later)');
await page.waitForTimeout(9000);
await mock('inbox', [1, 2, 3, 4].map((i) => ({ title: ['Soccer moved to 5:30', 'Pick up milk', 'Pizza tonight', 'Lights out at 9'][i - 1], from: i % 2 ? 'Den' : 'Kitchen iPad' })));
check(await until(page, async () => JSON.stringify(await banner()) === JSON.stringify(['From Den and Kitchen iPad', '4 new messages', 'Lights out at 9 · Pizza tonight · Pick up milk']), 8000) >= 0,
  'four at once: one banner, “4 new messages”, the newest titles');
await page.tap('#alert'); await settle(page, 1200);
check(await page.evaluate(() => location.hash.indexOf('#settings/phone') === 0), 'its tap opens Settings › This Phone');
check((await page.locator('#p-msgs .row').count()) === 7, 'Recent Messages: all seven, newest first');
check((await text(page, '#p-msgs .row .t')) === 'Lights out at 9' && (await text(page, '#p-msgs .row .meta')) === 'From Kitchen iPad · just now', 'the newest first, with who and when');
// Through a reload (or the TV restarting), nothing comes twice, and the list stays.
await page.reload(); await page.waitForTimeout(6500);
check(await banner() === null, 'after a reload: no banner again');
await page.goto(base + '#settings/phone'); await settle(page, 1200);
check((await page.locator('#p-msgs .row').count()) === 7, 'after a reload: the list is still there');
// One with a link (a sign-in that ended) opens its TV's page from the list.
await mock('inbox', { title: 'Sign in to Google Drive again', body: 'Google ended Den’s sign-in. Tap to see how to sign it in again.', from: 'Den', link: '#settings/tvs/b', id: 'signin:b:drive:1' });
await page.waitForTimeout(6000);
await page.tap('#alert'); await settle(page, 1200);
check(await page.evaluate(() => location.hash.indexOf('#settings/tvs/b') === 0), 'the banner’s tap opens Den’s page');
await page.goto(base + '#settings/phone'); await page.reload(); await settle(page, 1200);
check((await page.locator('#p-msgs button.row .chev').count()) === 1, 'in the list it has a chevron');
await page.locator('#p-msgs button.row').first().tap(); await settle(page, 1200);
check(await page.evaluate(() => location.hash.indexOf('#settings/tvs/b') === 0), 'and opens Den’s page from there');
// The TV's inbox made anew (reinstalled): its first message still shows.
await mock('set', { inboxEpoch: 'mock2' });
await mock('inbox', { title: 'Hello again', from: 'Living Room' });
check(await until(page, async () => ((await banner()) || [])[1] === 'Hello again', 9000) >= 0, 'a reinstalled TV’s first message still shows');
// A phone with nothing yet.
await page.evaluate(() => { localStorage.removeItem('woodruff.messages'); });
await page.goto(base + '#settings/phone'); await page.reload(); await settle(page, 1200);
check((await text(page, '#p-msgs .empty')) === 'No messages this week' && (await text(page, '#p-msgs-sec .foot')) === 'Messages to this phone stay here for a week.', 'none yet: said, and how long they stay');

// ---- A video too big for the TV is turned down on the phone (finding 44) ---------------------------------------------
await fresh('#memories', { free: 120 * 1048576 });
clear();
await attach('#mem-file', 'IMG_0042.MOV', 'video/quicktime', 150);
check(await until(page, () => shown(page, '#dialog'), 5000) >= 0, 'a video bigger than the room: an alert at once');
check((await text(page, '#d-title')) === 'Not Enough Room' && (await text(page, '#d-msg')) === 'This video is 150 MB; Living Room has 120 MB free.', 'its words: ' + (await text(page, '#d-msg')));
check(sent('/api/memories/add', 'POST').length === 0, 'nothing was sent');
check((await page.locator('#mem-grid .mtile.fail').count()) === 1, 'its tile stays, to try again or remove');
await page.locator('#d-btns button').first().tap(); await settle(page, 600);
await attach('#mem-file', 'IMG_0043.MOV', 'video/quicktime', 2);
await page.waitForTimeout(2500);
check(sent('/api/memories/add', 'POST').length === 1, 'one that fits goes');
await fresh('#send/files', { free: 1048576 });
clear();
await attach('#file', 'Lake weekend.mov', 'video/quicktime', 2);
await settle(page, 1000);
check((await text(page, '#uploads .row .s')) === 'This video is 2.0 MB; Living Room has 1.0 MB free.' && (await page.locator('#uploads .row .s.warn').count()) === 1, 'Photos & Files: the row says why, not “Failed”');
check(sent('/api/upload', 'POST').length === 0, 'nothing was sent');
await page.tap('#storage'); await settle(page, 600);
check((await page.locator('#menu .ms').allInnerTexts()).join('|') === '1.0 MB free|58.0 GB free', 'the storage menu shows each one’s room');
await page.mouse.click(10, 700); await settle(page, 500);
// The USB drive has room: it goes, named by the drive when it doesn't.
await page.tap('#storage'); await settle(page, 600);
await page.locator('#menu button', { hasText: 'USB drive' }).first().tap(); await settle(page, 1200);
await attach('#file', 'Lake weekend.mov', 'video/quicktime', 2);
check(await until(page, async () => (await text(page, '#uploads .row:last-child .s')) === 'Uploaded', 5000) >= 0, 'with room it uploads');

// ---- 375 pt, and nothing scrolls sideways --------------------------------------------------------------------------
await page.setViewportSize({ width: 375, height: 667 });
await fresh('#remote', { down: true });
await until(page, async () => (await card()) !== '', 12000);
const wide = await page.evaluate(() => [document.documentElement.scrollWidth, document.getElementById('rm-reach').getBoundingClientRect().right]);
check(wide[0] <= 375 && wide[1] <= 375, '375 pt: the card fits, nothing scrolls sideways');

check(errors.length === 0, 'no script errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
await browser.close();
console.log(failures ? failures + ' failed' : 'all passed');
process.exit(failures ? 1 : 0);
