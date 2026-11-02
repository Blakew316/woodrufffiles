// Screenshots the phone app on an iPhone-sized screen, light and dark, for the UI gallery:
// each tab, plus the keyboard sheet; Watch scrolled, a film's and a show's sheet, the Play On menu, a held tile,
// the Movies grid, Search, the TV Screen sheet from the now-playing bar, and Watch offline, signed out and loading;
// Send's To page, Photos & Files (also scrolled, its large title collapsed into the bar) and a file's action sheet,
// a photo in Family Memories, Settings › TVs, each TV's page (all good, needs attention and its fix, off, Link again
// and its code), a TV's screen, and a Settings page.
// It runs like the Home Screen app: the web view under a "default" status bar (so no top safe area),
// the home indicator's 34pt at the bottom, and navigator.standalone set.
// Usage: node phone.mjs <output dir>   (with mock_server.py running on :8765, or on MOCK_PORT)
import { chromium, devices } from 'playwright';
import { mkdirSync } from 'node:fs';

const out = process.argv[2] || 'gallery';
mkdirSync(out, { recursive: true });
const base = `http://127.0.0.1:${process.env.MOCK_PORT || 8765}/`;
const tabs = ['remote', 'watch', 'send', 'memories', 'settings'];
// Extra states: [name, tab, steps in turn, mock state]. A step is an element to tap (each tap opens a push, sheet or
// action sheet), { scroll: scroller, y } to scroll a page to y, { type: field, text } to type into a field, or
// { context: element } to hold it (a right click, which the page takes as a long press). The mock state, when
// there is one, is posted to /__mock/set before the shot and undone with /__mock/reset after it.
const extras = [
  ['keyboard', 'remote', ['#kb-open2']],                                               // the keyboard sheet
  ['watch-scrolled', 'watch', [{ scroll: '#watch-root .scroller', y: 520 }]],          // Movies and TV Shows rows under the now-playing bar
  ['watch-title-movie', 'watch', ['#w-hero .hero-info']],                              // a film in progress: Resume, Start Over, On Living Room
  ['watch-title-show', 'watch', ['#w-shows .ptile:nth-child(1)', { scroll: '#ts-body', y: 380 }]],   // a show at its season, progress and ✓
  ['watch-play-on', 'watch', ['#w-hero .hero-info', '#ts-target']],                    // the Play On menu: Off, Link again, All TVs
  ['watch-context', 'watch', [{ context: '#w-cw .cwi:nth-child(1)' }]],                // a Continue Watching tile, held
  ['watch-movies', 'watch', ['#w-see-movies']],                                        // every film, 60 at a time
  ['watch-search', 'watch', ['#w-search-btn', { type: '#ws-input', text: 'har' }]],    // results as you type
  ['watch-now-playing', 'watch', ['#np-open']],                                        // the TV's screen with ↺10 ❚❚ 30↻ and Next Episode
  ['watch-offline', 'watch', [], { library: 'offline' }],                              // the saved library, offline
  ['watch-signin', 'watch', [], { library: 'expiredEmpty' }],                          // Drive's sign-in ended, nothing saved
  ['watch-loading', 'watch', [], { library: 'loading' }],                              // the TV's first listing
  ['send-to', 'send', ['#n-to']],                                                      // who gets a message
  ['files', 'send', ['#send-root [data-page=files-root]']],                            // Photos & Files
  ['files-scrolled', 'send', ['#send-root [data-page=files-root]', { scroll: '#files-root .scroller', y: 90 }]],   // its large title collapsed into the bar
  ['files-actions', 'send', ['#send-root [data-page=files-root]', '#list [data-i="7"]']], // a video's action sheet
  ['memories-photo', 'memories', ['#mem-grid .mtile:nth-child(3)']],                    // a photo, full screen
  ['settings-tvs', 'settings', ['#set-main [data-page=set-tvs]']],                     // every TV, what it plays, how it's doing
  ['settings-tv', 'settings', ['#set-main [data-page=set-tvs]', '#tv-list .row:nth-child(1)']],                   // Living Room: all good
  ['settings-tv-attention', 'settings', ['#set-main [data-page=set-tvs]', '#tv-list .row:nth-child(2)']],         // Den: needs attention
  ['settings-tv-attention-sheet', 'settings', ['#set-main [data-page=set-tvs]', '#tv-list .row:nth-child(2)', '#tvp-sum [data-att="signin.drive"]']],
  ['settings-tv-off', 'settings', ['#set-main [data-page=set-tvs]', '#tv-list .row:nth-child(3)']],               // Kitchen: off
  ['settings-tv-link', 'settings', ['#set-main [data-page=set-tvs]', '#tv-list .row:nth-child(4)']],              // Bedroom: Link again
  ['settings-tv-relink', 'settings', ['#set-main [data-page=set-tvs]', '#tv-list .row:nth-child(4)', '#tvp-link']],
  ['tv-screen', 'settings', ['#set-main [data-page=set-tvs]', '#tv-list .row:nth-child(1)', '#tvp-screen']],     // a TV's screen (sheet)
  ['settings-drive', 'settings', ['#set-main [data-page=set-conn]', '#set-conn [data-page=set-drive]']],
];
const settle = 900; // the push and sheet animations take 500 ms
const mock = (what, body) => fetch(base + '__mock/' + what, { method: 'POST', body: JSON.stringify(body || {}) });
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
    // Watch's first visit asks the TV for the library and fades it in once the tab bar's glide has settled.
    await page.waitForTimeout(tab === 'watch' ? 2500 : settle);
    await page.screenshot({ path: `${out}/phone-${tab}-${scheme}.png` });
  }
  for (const [name, tab, targets, state] of extras) {
    if (state) await mock('set', state);
    // A fresh page each time: nothing left open from the shot before (a sheet, a menu, a search).
    await page.goto(base + '#' + tab);
    await page.reload();
    await page.waitForTimeout(settle);
    for (const target of targets) {
      if (typeof target === 'string') await page.tap(target);
      else if (target.type) { await page.tap(target.type); await page.keyboard.type(target.text); }
      else if (target.context) await page.click(target.context, { button: 'right' });
      else await page.evaluate(([sel, y]) => { document.querySelector(sel).scrollTop = y; }, [target.scroll, target.y]);
      await page.waitForTimeout(settle);
    }
    await page.screenshot({ path: `${out}/phone-${name}-${scheme}.png` });
    if (state) await mock('reset');
  }
  await context.close();
}
await browser.close();
