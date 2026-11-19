import assert from 'node:assert/strict';
import { setTimeout as sleep } from 'node:timers/promises';

const catalogs = [
  ['artists', 'artists'],
  ['tracks', 'tracks-catalog'],
  ['patches', 'patches'],
  ['archived-songs', 'archived-songs'],
  ['archived-refills', 'archived-refills']
];

async function rendered(page, selector, pattern) {
  await page.waitForFunction(({ selector, source }) => {
    const element = document.querySelector(selector);
    return element && new RegExp(source).test(element.textContent);
  }, { selector, source: pattern.source });
}

async function loadedHome(page) {
  await page.locator('#home-page').waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const lists = [...document.querySelectorAll('#home-page [aria-busy]')];
    return lists.length > 0 && lists.every(list => list.getAttribute('aria-busy') === 'false');
  });
  assert.doesNotMatch(await page.locator('#home-page').innerText(), /Unable to load/);
}

export async function journey(page, config, metrics, user, iteration) {
  const step = async (name, action) => {
    await metrics.step(name, action);
    await sleep((config.thinkMin + Math.random() * (config.thinkMax - config.thinkMin)) * 1000);
  };
  await step('home', async () => {
    const response = await page.goto(`${config.baseUrl}/index.html`, { waitUntil: 'load' });
    assert.ok(response?.ok(), `Home returned HTTP ${response?.status()}`);
    assert.equal(new URL(page.url()).origin, config.baseUrl, 'Home redirected to another origin');
    await loadedHome(page);
    await page.locator('#login-link').waitFor({ state: 'visible' });
  });

  // Rotate the starting catalog so users do not all follow the same path.
  const offset = (user + iteration) % catalogs.length;
  for (let index = 0; index < catalogs.length; index++) {
    const [name, prefix] = catalogs[(offset + index) % catalogs.length];
    const status = `#${prefix}-status`;
    const ready = /\d[\d,]* (?:artist|track|patch|archived song|archived refill)s?\./;
    await step(`catalog:${name}`, async () => {
      await page.locator(`#${name}-link`).click();
      await rendered(page, status, ready);
      await page.locator(`#${prefix}-table tbody tr`).first().waitFor({ state: 'visible' });
    });

    if (name === 'tracks' || name === 'patches') {
      await step(`detail:${name}`, async () => {
        const link = page.locator(`#${prefix}-table a.${name === 'tracks' ? 'track' : 'patch'}-detail-link`).first();
        if (!await link.count()) return metrics.skip(`detail:${name}:empty-catalog`);
        await link.click();
        const content = page.locator('.track-card-dialog[open] [aria-live]');
        await page.waitForFunction(() =>
          document.querySelector('.track-card-dialog[open] [aria-live]')?.getAttribute('aria-busy') === 'false');
        assert.equal(await content.locator('.form-message.error').count(), 0, await content.innerText());
        await content.locator('.track-card, .patch-card').first().waitFor({ state: 'visible' });
        await page.locator('.track-card-dialog[open] .dialog-close').click();
      });
    }

    if (name === 'artists') {
      await step('artist', async () => {
        const link = page.locator('#artists-table tbody a').first();
        if (!await link.count()) return metrics.skip('artist:empty-catalog');
        await link.click();
        await page.locator('#artist-page').waitFor({ state: 'visible' });
        for (const kind of ['tracks', 'patches']) {
          await page.locator(`#artist-${kind}`).waitFor({ state: 'visible' });
          assert.doesNotMatch(await page.locator(`#artist-${kind}`).innerText(), /Unable to load/);
        }
      });
    }

    if (name === 'tracks') {
      await step('tracks:filter', async () => {
        await page.locator('#tracks-catalog-filter-input').fill(config.search);
        await page.locator('#tracks-catalog-filter-form button[type="submit"]').click();
        await rendered(page, status, ready);
      });
    }

    if (name.startsWith('archived-')) {
      await step(`${name}:pagination`, async () => {
        const next = page.locator(`#${prefix}-pagination .catalog-next-page`);
        if (!await next.isVisible() || !await next.isEnabled()) return metrics.skip(`${name}:no-next-page`);
        await next.click();
        await rendered(page, `#${prefix}-pagination .catalog-page-status`, /Page 2 of/);
        await rendered(page, status, ready);
      });
    }
  }

  await step('search', async () => {
    await page.locator('#track-search-input').fill(config.search);
    await page.locator('#track-search-form button[type="submit"]').click();
    await rendered(page, '#search-status', /\d+ tracks? found\./);
    await page.locator('#search-page').waitFor({ state: 'visible' });
  });
  await step('tutorials', async () => {
    await page.locator('#tutorials-link').click();
    await page.locator('#tutorials-page').waitFor({ state: 'visible' });
    await page.locator('#tutorials-page .tutorial-card').first().click();
    await page.locator('#tutorial-oscillators-page').waitFor({ state: 'visible' });
  });
  await step('return-home', async () => {
    await page.locator('.logo-title').click();
    await loadedHome(page);
  });
}
