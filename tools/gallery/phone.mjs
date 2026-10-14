// Screenshots each tab of the phone app on an iPhone-sized screen, light and dark, for the UI gallery.
// Usage: node phone.mjs <output dir>   (with mock_server.py running on :8765)
import { chromium, devices } from 'playwright';
import { mkdirSync } from 'node:fs';

const out = process.argv[2] || 'gallery';
mkdirSync(out, { recursive: true });
const tabs = ['files', 'tv', 'keyboard', 'notes', 'send', 'settings'];
const browser = await chromium.launch();
for (const scheme of ['dark', 'light']) {
  const context = await browser.newContext({ ...devices['iPhone 13'], colorScheme: scheme });
  // A named phone, like one that has been set up, so Settings shows the ntfy section.
  await context.addInitScript(() => localStorage.setItem('woodruff.device',
    JSON.stringify({ id: 'p-blake', name: "Blake's iPhone", alerts: true, topic: 'woodruff-example' })));
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:8765/');
  for (const tab of tabs) {
    // Tap the tab bar like a person would (a #tab address makes the browser jump to that section).
    await page.click(`nav button[data-tab=${tab}]`);
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${out}/phone-${tab}-${scheme}.png` });
  }
  await context.close();
}
await browser.close();
