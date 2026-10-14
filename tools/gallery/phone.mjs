// Screenshots the phone app on an iPhone-sized screen, light and dark, for the UI gallery:
// each tab, plus the keyboard, a file's action sheet, the note editor and a Settings page.
// Usage: node phone.mjs <output dir>   (with mock_server.py running on :8765)
import { chromium, devices } from 'playwright';
import { mkdirSync } from 'node:fs';

const out = process.argv[2] || 'gallery';
mkdirSync(out, { recursive: true });
const tabs = ['remote', 'tv', 'files', 'notes', 'send', 'settings'];
// Extra states: [name, tab, element to tap].
const extras = [
  ['keyboard', 'remote', '#kb-open2'],
  ['files-actions', 'files', '#list [data-i="7"]'],
  ['note', 'notes', '#note-list .row'],
  ['settings-drive', 'settings', '[data-page=set-drive]'],
];
const browser = await chromium.launch();
for (const scheme of ['dark', 'light']) {
  const context = await browser.newContext({ ...devices['iPhone 13'], colorScheme: scheme });
  // A named phone, like one that has been set up, so Settings shows the ntfy section.
  // Also as installed on the Home Screen, so the "Add to Home Screen" hint is already dismissed.
  await context.addInitScript(() => {
    localStorage.setItem('woodruff.device', JSON.stringify({ id: 'p-blake', name: "Blake's iPhone", alerts: true, topic: 'woodruff-example' }));
    localStorage.setItem('woodruff.installHint', '1');
  });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:8765/');
  for (const tab of tabs) {
    // Tap the tab bar like a person would.
    await page.click(`nav button[data-tab=${tab}]`);
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${out}/phone-${tab}-${scheme}.png` });
  }
  for (const [name, tab, target] of extras) {
    await page.goto('http://127.0.0.1:8765/#' + tab);
    await page.waitForTimeout(700);
    await page.tap(target);
    await page.waitForTimeout(700);
    await page.screenshot({ path: `${out}/phone-${name}-${scheme}.png` });
  }
  await context.close();
}
await browser.close();
