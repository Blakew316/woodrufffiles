// Screenshots the phone app on an iPhone-sized screen, light and dark, for the UI gallery:
// each tab, plus the keyboard sheet; Watch scrolled, a film's and a show's sheet, the Play On menu, a held tile,
// the Movies grid, Search, the TV Screen sheet from the now-playing bar, and Watch offline, signed out and loading;
// Send's To page, Photos & Files (also scrolled, its large title collapsed into the bar) and a file's action sheet,
// a photo in Family Memories, Settings › TVs, each TV's page (all good, needs attention and its fix, off, Link again
// and its code), a TV's screen, a Settings page, and signing the TV in from the phone (ready, waiting for Google, done,
// the banner when a sign-in ends, and another TV's "Sign In on Den"); the Remote when the TV (or the picked one) can't be
// reached, typing into another TV, messages that arrived together and the week's messages under This Phone, and a video
// too big for the room the TV has (Family Memories and Photos & Files).
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
// action sheet), { scroll: scroller, y } to scroll a page to y, { type: field, text } to type into a field,
// { context: element } to hold it (a right click, which the page takes as a long press), { attach: file input, name,
// mime, mb } to choose a file of that size there (made in the page: nothing that big crosses to it), or { wait: ms } to
// let time pass (a poll answering). The mock state, when there is one, is posted to /__mock/set before the shot (its `inbox`,
// a message for this phone, to /__mock/inbox) and undone with /__mock/reset after it.
// The messages for the shots of messages that arrived together, oldest first (ago: how long ago each was sent, in ms).
const MESSAGES = [
  { title: 'Sign in to Google Drive again', body: 'Google ended Den’s sign-in. Tap to see how to sign it in again.', from: 'Den', link: '#settings/tvs/b', id: 'signin:b:drive:7', ago: 26 * 3600e3 },
  { title: 'Soccer practice moved to 5:30', body: 'Same field as last week. Bring the orange cones.', from: 'Kitchen iPad', ago: 2 * 3600e3 },
  { title: 'Movie night at 8', body: '', from: 'Den', ago: 3 * 60e3 },
  { title: 'Dinner’s ready', body: '', from: 'Kitchen iPad', ago: 0 },
];
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
  // Signing this TV in from the phone: Google Drive's sign-in ended, the sheet over its page, waiting for Google, done.
  ['signin', 'settings', ['#set-main [data-page=set-conn]', '#set-conn [data-page=set-drive]', '#g-signin'], { settings: { google: { state: 'expired' } } }],
  ['signin-waiting', 'settings', ['#set-main [data-page=set-conn]', '#set-conn [data-page=set-drive]', '#g-signin', '#si-go'], { settings: { google: { state: 'expired' } } }],
  ['signin-done', 'settings', ['#set-main [data-page=set-conn]', '#set-conn [data-page=set-drive]', '#g-signin', '#si-go', { wait: 2500 }],
    { settings: { google: { state: 'expired' } }, signin: 'done' }],
  // The banner when Google ends this TV's sign-in, over the Remote (the inbox is asked every 5 s).
  ['signin-banner', 'remote', [{ wait: 5600 }], { inbox: { title: 'Sign in to Google Drive again', body: 'Google ended Living Room’s sign-in. Tap to sign it in again from this phone.',
    from: 'Living Room', link: '#settings/tvs/this/signin/drive', id: 'signin:this:drive:1' } }],
  // Another TV can't be signed in from here: the phone says where.
  ['signin-linked', 'settings', ['#set-main [data-page=set-tvs]', '#tv-list .row:nth-child(2)', '#tvp-signins [data-grant="drive"]']],
  // The TV stops answering (asleep, off the Wi-Fi): after two polls the Remote says so; the picked TV off, it names it.
  ['remote-unreachable', 'remote', [{ wait: 5000 }], { down: true }],
  ['remote-unreachable-linked', 'remote', ['#rm-tv', '#menu button:has-text("Kitchen")', { wait: 2500 }]],
  // The keyboard types into the TV that's picked: Den's search box, through Living Room. (A mock state, so the mock is
  // put back after the shot and Den's box starts empty in the next theme.)
  ['keyboard-linked', 'remote', ['#rm-tv', '#menu button:has-text("Den")', { wait: 2400 }, '#kb-open2', { type: '#kb-text', text: 'midnight harbor' }], { typeDelay: 0 }],
  // Four messages at once are one banner; its tap opens them all under Settings › This Phone.
  ['messages-summary', 'remote', [{ wait: 6200 }], { inbox: MESSAGES }],
  ['settings-phone-messages', 'remote', [{ wait: 6200 }, '#alert'], { inbox: MESSAGES }],
  // A video bigger than the room the TV has: said on the phone before anything is sent.
  ['memories-no-room', 'memories', [{ attach: '#mem-file', name: 'IMG_0042.MOV', mime: 'video/quicktime', mb: 150 }, { wait: 1200 }], { free: 125829120 }],
  ['files-no-room', 'send', ['#send-root [data-page=files-root]', { attach: '#file', name: 'Lake weekend.mov', mime: 'video/quicktime', mb: 150 }, { wait: 600 }], { free: 125829120 }],
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
    if (state) {
      const { inbox, ...rest } = state;
      if (Object.keys(rest).length) await mock('set', rest);
      if (inbox) await mock('inbox', inbox);
    }
    // A shot of messages starts with none on the phone (none seen, nothing kept under This Phone).
    if (state && state.inbox) await page.evaluate(() => ['woodruff.inbox', 'woodruff.messages', 'woodruff.seen'].forEach((k) => localStorage.removeItem(k)));
    // A fresh page each time: nothing left open from the shot before (a sheet, a menu, a search).
    await page.goto(base + '#' + tab);
    await page.reload();
    await page.waitForTimeout(settle);
    for (const target of targets) {
      if (typeof target === 'string') await page.tap(target);
      else if (target.type) { await page.tap(target.type); await page.keyboard.type(target.text); }
      else if (target.context) await page.click(target.context, { button: 'right' });
      else if (target.attach) {
        await page.evaluate(([sel, name, type, mb]) => {
          const f = new File([new Uint8Array(Math.round(mb * 1048576))], name, { type }), dt = new DataTransfer();
          dt.items.add(f);
          const input = document.querySelector(sel); input.files = dt.files; input.dispatchEvent(new Event('change'));
        }, [target.attach, target.name, target.mime, target.mb]);
      }
      else if (target.wait) { await page.waitForTimeout(target.wait); continue; }
      else await page.evaluate(([sel, y]) => { document.querySelector(sel).scrollTop = y; }, [target.scroll, target.y]);
      await page.waitForTimeout(settle);
    }
    await page.screenshot({ path: `${out}/phone-${name}-${scheme}.png` });
    if (state) await mock('reset');
  }
  await context.close();
}
await browser.close();
