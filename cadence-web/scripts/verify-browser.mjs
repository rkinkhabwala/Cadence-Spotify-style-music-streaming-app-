// Real-browser check of the web client against a running stack (spec 9 Phase 2 AC5, plus AC1 in the UI):
// logs in, finds "The Beatlz" by typing "beatls", plays, then navigates through several pages and asserts the same
// <audio> kept playing without being reloaded. Finally reloads the page: the session must come back from the
// HttpOnly refresh cookie with no token in Web Storage (D86). Uses the locally installed Google Chrome through playwright-core
// (no browser download). Screenshots go to cadence-web/target/screens/.
import { chromium } from 'playwright-core';
import { mkdirSync } from 'node:fs';

const WEB = process.env.WEB_URL ?? 'http://localhost:5173';
const EMAIL = process.env.CADENCE_DEMO_EMAIL;
const PASSWORD = process.env.CADENCE_DEMO_PASSWORD;
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const SHOTS = new URL('../target/screens/', import.meta.url).pathname;
mkdirSync(SHOTS, { recursive: true });

const log = (...a) => console.log('[verify-web]', ...a);
const fail = (msg) => { throw new Error(msg); };

const browser = await chromium.launch({
  executablePath: CHROME, headless: true,
  args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio'],
});
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('pageerror', (e) => log('page error:', e.message));
const state = () => page.evaluate(() => {
  const p = window.cadencePlayer;
  return p ? { time: p.audio.currentTime, paused: p.audio.paused, loads: p.loads(), marked: p.audio.dataset.marker === 'same' } : null;
});

try {
  await page.goto(`${WEB}/login`);
  await page.getByLabel('Email address').fill(EMAIL);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Log in' }).click();
  await page.getByRole('heading', { name: /Good|Up late/ }).waitFor();
  await page.screenshot({ path: `${SHOTS}home.png` });
  log('logged in as', EMAIL);

  await page.getByRole('link', { name: 'Search' }).click();
  await page.getByRole('combobox').fill('beatls');
  const option = page.getByRole('option', { name: /The Beatlz/ });
  await option.waitFor({ timeout: 5000 });
  await page.screenshot({ path: `${SHOTS}suggest.png` });
  log('suggestions for "beatls" include The Beatlz');
  await option.click();
  await page.getByRole('heading', { name: 'The Beatlz', level: 1 }).waitFor();

  await page.getByRole('button', { name: 'Play The Beatlz' }).click();
  await page.waitForFunction(() => {
    const a = window.cadencePlayer?.audio;
    return a && !a.paused && a.currentTime > 1.5;
  }, null, { timeout: 20000 });
  await page.evaluate(() => { window.cadencePlayer.audio.dataset.marker = 'same'; });
  const start = await state();
  log('playing:', JSON.stringify(start));
  await page.screenshot({ path: `${SHOTS}artist-playing.png` });

  const stops = [
    ['Home (sidebar)', () => page.getByRole('link', { name: 'Home' }).click(), () => page.getByRole('heading', { name: /Good|Up late/ }).waitFor()],
    ['Search (sidebar)', () => page.getByRole('link', { name: 'Search' }).click(), () => page.getByRole('combobox').waitFor()],
    ['Album (player bar)', () => page.getByRole('contentinfo').locator('.now-title a').click(), () => page.locator('.hero-title').waitFor()],
    ['Liked Songs (library)', () => page.locator('.library-list').getByRole('link', { name: /Liked Songs/ }).click(),
      () => page.getByRole('heading', { name: 'Liked Songs', level: 1 }).waitFor()],
    ['Back', () => page.goBack(), () => page.locator('.hero-title').waitFor()],
  ];
  let previous = start;
  for (const [name, go, ready] of stops) {
    await go();
    await ready();
    await page.waitForTimeout(1200);
    const now = await state();
    if (!now || now.paused) fail(`${name}: playback stopped`);
    if (now.loads !== start.loads) fail(`${name}: the source was reloaded (${start.loads} → ${now.loads})`);
    if (!now.marked) fail(`${name}: the audio element was replaced`);
    if (now.time <= previous.time) fail(`${name}: playback did not advance (${previous.time} → ${now.time})`);
    log(`${name}: still playing at ${now.time.toFixed(1)} s, same element, ${now.loads} load(s)`);
    previous = now;
  }
  await page.screenshot({ path: `${SHOTS}album-playing.png` });
  await page.getByRole('button', { name: 'Queue' }).click();
  await page.screenshot({ path: `${SHOTS}queue.png` });
  log(`playback continued across ${stops.length} navigations (${(previous.time - start.time).toFixed(1)} s heard meanwhile)`);

  // D86: the refresh token is an HttpOnly cookie, never in Web Storage; a reload restores the session from it
  const storage = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
  if (/eyJ|refresh|token/i.test(storage)) fail(`token material in Web Storage: ${storage}`);
  if ((await page.evaluate(() => document.cookie)).includes('cadence_refresh')) fail('refresh cookie is readable by scripts');
  const cookie = (await page.context().cookies()).find((c) => c.name === 'cadence_refresh');
  if (!cookie?.httpOnly || cookie.sameSite !== 'Strict' || cookie.path !== '/api/v1/auth') {
    fail(`unexpected refresh cookie: ${JSON.stringify({ ...cookie, value: undefined })}`);
  }
  await page.reload();
  await page.locator('.hero-title').waitFor();
  if (page.url().includes('/login')) fail('reload lost the session');
  log('reload restored the session from the HttpOnly refresh cookie; Web Storage holds no tokens');
  log('PASS');
} catch (e) {
  await page.screenshot({ path: `${SHOTS}failure.png` }).catch(() => undefined);
  console.error('[verify-web] FAIL:', e.message);
  process.exitCode = 1;
} finally {
  await browser.close();
}
