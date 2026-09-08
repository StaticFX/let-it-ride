import { defineConfig, devices } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '..')

/**
 * The suite drives the artefact we actually ship: one jar serving both the API
 * and the built SPA on one port. `E2E_BASE_URL` points it at a server that is
 * already up instead — a dev server, a staging box, a container.
 */
const port = Number(process.env.E2E_PORT ?? 8099)
const externalTarget = process.env.E2E_BASE_URL
const baseURL = externalTarget ?? `http://127.0.0.1:${port}`

/** Rounds are paced by the server — a title card, a deal, bots thinking. */
const ONE_MINUTE = 60_000

export default defineConfig({
  testDir: './tests',
  outputDir: './test-results',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 3 : 4,
  timeout: 3 * ONE_MINUTE,
  expect: {
    // Nothing on this table appears instantly: the shortest wait is a 750ms
    // deal step and the longest is a bot taking its turn behind a title card.
    timeout: 15_000,
  },
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }], ['list']]
    : [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },

  projects: [
    {
      name: 'chromium',
      // Everything except the phone specs, which drive the page with `tap()`
      // and need a touch context this project deliberately does not have.
      grepInvert: /@mobile/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    /**
     * The narrow table, at the three shapes it actually has to hold.
     *
     * All three are Chromium with `hasTouch` and `isMobile` on, which is what
     * makes `(hover: none)` and `(pointer: coarse)` true — the two facts the
     * client branches on. A named device would drag WebKit into CI for a
     * viewport box that can simply be written down.
     *
     * The portrait size is deliberately the *short* one rather than the phone's
     * nominal 844: nothing inside the felt scrolls, so Safari never retracts
     * its toolbars and a table on an iPhone 14 gets about 664px of height for
     * the whole session. Testing the number the device is sold with would be
     * testing a window nobody has.
     */
    {
      name: 'mobile-portrait',
      grep: /@mobile/,
      use: { ...devices['Pixel 7'], viewport: { width: 390, height: 664 } },
    },
    {
      name: 'mobile-landscape',
      grep: /@mobile/,
      use: { ...devices['Pixel 7'], viewport: { width: 844, height: 390 } },
    },
    {
      // A tablet is wide enough for the arc and still has no cursor, which is
      // the one combination neither of the others covers.
      name: 'tablet',
      grep: /@mobile/,
      use: { ...devices['Pixel 7'], viewport: { width: 768, height: 1024 } },
    },
  ],

  webServer: externalTarget
    ? undefined
    : {
        command: `bash ${path.join(here, 'scripts', 'serve.sh')}`,
        url: `${baseURL}/api/health`,
        cwd: repoRoot,
        // The first run builds the frontend bundle and the fat jar.
        timeout: 10 * ONE_MINUTE,
        reuseExistingServer: !process.env.CI,
        stdout: 'pipe',
        stderr: 'pipe',
        env: {
          PORT: String(port),
          // Lets a spec pin a room's shuffle so a run replays card for card.
          LETITRIDE_TEST_HOOKS: '1',
          // The table is deliberately unhurried — a title card, a deal, bots
          // thinking — and the suite spends nearly all of its time watching it
          // wait. This runs the same game at a quarter of the pace, server and
          // client together, so what shrinks is the waiting and not the
          // sequencing. `E2E_PACE=1` puts it back to what a player sees.
          LETITRIDE_PACE: process.env.E2E_PACE ?? '0.25',
        },
      },
})
