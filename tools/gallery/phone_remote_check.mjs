// Checks the phone remote's taps and holds against mock_server.py: a tap of OK presses OK; holding OK presses Menu
// once (the TV's "more options", as holding OK on its remote does) and not OK; holding an arrow sends a press, then
// repeats counting 1, 2, 3… (which the TV gates like its own remote's held button), and nothing more as it's let go.
// Usage: MOCK_LOG=/tmp/keys.log python3 mock_server.py ../../app/src/main/assets/web &
//        node phone_remote_check.mjs /tmp/keys.log
import { chromium, devices } from 'playwright';
import { readFileSync } from 'node:fs';

const log = process.argv[2];
if (!log) { console.error('Usage: node phone_remote_check.mjs <MOCK_LOG file>'); process.exit(2); }
const base = 'http://127.0.0.1:8765/';
const browser = await chromium.launch();
const iphone = devices['iPhone 13'];
const context = await browser.newContext({ ...iphone });
await context.addInitScript(() => {
  localStorage.setItem('woodruff.device', JSON.stringify({ id: 'p-check', name: 'iPhone', alerts: true, topic: 'woodruff-example' }));
  localStorage.setItem('woodruff.installHint', '1');
});
const page = await context.newPage();
// Real touches (Playwright's own tap is too quick to hold), so the page sees touchstart, pointerdown and click.
const cdp = await context.newCDPSession(page);
await page.goto(base + '#remote');
await page.waitForTimeout(1200);

async function hold(selector, ms) {
  const b = await page.locator(selector).boundingBox();
  const x = b.x + b.width / 2, y = b.y + b.height / 2;
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  await page.waitForTimeout(ms);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(900);
}
const sent = () => readFileSync(log, 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l))
  .filter((r) => r.method === 'POST' && r.path.startsWith('/api/key')).map((r) => r.body);
let seen = sent().length;
const since = () => { const all = sent(); const s = all.slice(seen); seen = all.length; return s; };
let failed = 0;
function check(name, ok, got) { if (!ok) failed++; console.log((ok ? 'PASS ' : 'FAIL ') + name + ' ' + JSON.stringify(got)); }
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

await hold('.okb', 80);
let got = since();
check('tap OK', same(got, [{ key: 'ok' }]), got);

await hold('.okb', 900);
got = since();
check('hold OK', same(got, [{ key: 'menu' }]), got);

await hold('[data-key=down]', 1100);
got = since();
check('hold Down', got.length >= 3 && same(got[0], { key: 'down' }) && got.slice(1).every((k, i) => k.key === 'down' && k.repeat === i + 1), got);

await hold('[data-key=down]', 80);
got = since();
check('tap Down', same(got, [{ key: 'down' }]), got);

await browser.close();
process.exit(failed ? 1 : 0);
