// Screenshots the phone app on an iPhone-sized screen, light and dark, for the UI gallery:
// each tab, plus the keyboard sheet, a file's action sheet, a note and a Settings page.
// It runs like the Home Screen app: the web view under a "default" status bar (so no top safe area),
// the home indicator's 34pt at the bottom, and navigator.standalone set.
// Usage: node phone.mjs <output dir>   (with mock_server.py running on :8765)
import { chromium, devices } from 'playwright';
import { mkdirSync } from 'node:fs';

const out = process.argv[2] || 'gallery';
mkdirSync(out, { recursive: true });
const base = 'http://127.0.0.1:8765/';
const tabs = ['remote', 'tv', 'files', 'notes', 'send', 'settings'];
// Extra states: [name, tab, element to tap]. Each opens with a push, sheet or action sheet.
const extras = [
  ['keyboard', 'remote', '#kb-open2'],                  // the keyboard sheet
  ['files-actions', 'files', '#list [data-i="7"]'],     // a video's action sheet
  ['note', 'notes', '#note-list .row'],                 // a note, pushed
  ['settings-drive', 'settings', '#set-main [data-page=set-drive]'],
];
const settle = 900; // the push and sheet animations take 500 ms
const browser = await chromium.launch();
for (const scheme of ['dark', 'light']) {
  const iphone = devices['iPhone 13'];
  const context = await browser.newContext({
    ...iphone, colorScheme: scheme,
    viewport: { width: iphone.screen.width, height: iphone.screen.height - 47 }, // below the 47pt status bar
  });
  // A named phone, like one that has been set up, so Settings shows it; installed on the Home Screen,
  // so the "Add to Home Screen" tip is already gone.
  await context.addInitScript(() => {
    localStorage.setItem('woodruff.device', JSON.stringify({ id: 'p-blake', name: "Blake's iPhone", alerts: true, topic: 'woodruff-example' }));
    localStorage.setItem('woodruff.installHint', '1');
    Object.defineProperty(navigator, 'standalone', { get: () => true });
  });
  const page = await context.newPage();
  try {
    // The home indicator's safe area, so the tab bar sits where it does on the phone (newer Chromium only).
    const cdp = await context.newCDPSession(page);
    await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: { top: 0, bottom: 34, left: 0, right: 0 } });
  } catch (e) {
    console.log('No safe-area emulation in this Chromium; the tab bar sits a little lower.');
  }
  await page.goto(base);
  await page.waitForTimeout(settle);
  for (const tab of tabs) {
    // Tap the tab bar like a person would.
    await page.tap(`nav button[data-tab=${tab}]`);
    await page.waitForTimeout(settle);
    await page.screenshot({ path: `${out}/phone-${tab}-${scheme}.png` });
  }
  for (const [name, tab, target] of extras) {
    await page.goto(base + '#' + tab);
    await page.waitForTimeout(settle);
    await page.tap(target);
    await page.waitForTimeout(settle);
    await page.screenshot({ path: `${out}/phone-${name}-${scheme}.png` });
  }
  await context.close();
}
await browser.close();
