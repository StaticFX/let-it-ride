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
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      // The table is laid out for a desktop window; only the screens that are
      // meant to work on a phone are checked here.
      name: 'mobile',
      grep: /@mobile/,
      use: { ...devices['Pixel 7'] },
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
        },
      },
})
