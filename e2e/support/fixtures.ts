import { test as base, expect, type BrowserContext, type Page } from '@playwright/test'
import { App } from './app'
import { Api } from './api'
import { SocketPlayer } from './socket'

/**
 * Recorded in the page so a spec can cut the socket the way a flaky network
 * does — silently, without the client being told. Nothing in the app knows
 * about this; it is installed before any of the app's own code runs.
 */
const SOCKET_SPY = `
  window.__sockets = [];
  const Native = window.WebSocket;
  const Spy = function (url, protocols) {
    const socket = protocols === undefined ? new Native(url) : new Native(url, protocols);
    window.__sockets.push(socket);
    return socket;
  };
  Spy.prototype = Native.prototype;
  Spy.CONNECTING = Native.CONNECTING;
  Spy.OPEN = Native.OPEN;
  Spy.CLOSING = Native.CLOSING;
  Spy.CLOSED = Native.CLOSED;
  window.WebSocket = Spy;
`

/**
 * Console output the suite deliberately ignores.
 *
 * Kept as narrow as possible: everything not listed here fails the test that
 * produced it, which is the point — a React key warning or a rejected promise
 * is a bug even when the assertions still pass.
 */
const IGNORED_CONSOLE = [
  // Playwright's Chromium ships without the AAC decoder, so the .m4a samples
  // cannot be decoded there. The app already treats that as "stay silent", and
  // a spec checks the files themselves are served.
  /decodeAudioData/i,
  /Unable to decode audio data/i,
  /The AudioContext was not allowed to start/i,
  /autoplay/i,
  // React's dev build tells every browser about its devtools.
  /Download the React DevTools/i,
]

function isIgnored(text: string): boolean {
  return IGNORED_CONSOLE.some((pattern) => pattern.test(text))
}

export interface ConsoleGuard {
  /** Everything the page complained about that was not deliberately ignored. */
  readonly problems: string[]
  /**
   * Declares output this spec causes on purpose — a request it aborts, a room
   * it looks up knowing there is none. Narrow it to the resource so the guard
   * still fails on anything else.
   */
  allow(pattern: RegExp): void
  /** Stops this page's output from failing the test entirely. */
  stopWatching(): void
}

function watch(page: Page): ConsoleGuard {
  const recorded: string[] = []
  const allowed: RegExp[] = []
  let watching = true

  // Chromium reports a failed request without saying which one, so the URL is
  // pulled off the message: a failure that names the resource is the whole
  // difference between "something 404'd" and a fix.
  page.on('console', (message) => {
    if (!watching || message.type() !== 'error') return
    const where = message.location().url
    recorded.push(`console.error: ${message.text()}${where ? ` [${where}]` : ''}`)
  })
  page.on('pageerror', (error) => {
    if (!watching) return
    recorded.push(`uncaught: ${error.name}: ${error.message}`)
  })

  return {
    get problems() {
      return recorded.filter((entry) => !isIgnored(entry) && !allowed.some((pattern) => pattern.test(entry)))
    },
    allow(pattern: RegExp) { allowed.push(pattern) },
    stopWatching() { watching = false },
  }
}

interface Fixtures {
  app: App
  api: Api
  consoleGuard: ConsoleGuard
  /** Opens a second player in their own browser context, sharing nothing with the first. */
  openPlayer: () => Promise<{ app: App; page: Page; context: BrowserContext; guard: ConsoleGuard }>
  /** A bare websocket player, closed for you at the end of the test. */
  openSocket: (roomCode: string, options?: { playerId?: string; name?: string }) => Promise<SocketPlayer>
}

export const test = base.extend<Fixtures>({
  api: async ({ request }, use) => {
    await use(new Api(request))
  },

  consoleGuard: async ({ page }, use) => {
    const guard = watch(page)
    await use(guard)
    expect(guard.problems, 'the page logged errors').toEqual([])
  },

  app: async ({ page, api, consoleGuard }, use) => {
    void consoleGuard // ensures the guard is armed before the page is driven
    await page.addInitScript(SOCKET_SPY)
    const app = new App(page, api)
    await app.open()
    await use(app)
  },

  openPlayer: async ({ browser, api }, use) => {
    const opened: { context: BrowserContext; guard: ConsoleGuard }[] = []

    await use(async () => {
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
      const page = await context.newPage()
      const guard = watch(page)
      await page.addInitScript(SOCKET_SPY)
      const app = new App(page, api)
      await app.open()
      opened.push({ context, guard })
      return { app, page, context, guard }
    })

    for (const { context, guard } of opened) {
      expect(guard.problems, 'a second player logged errors').toEqual([])
      await context.close()
    }
  },

  openSocket: async ({ baseURL }, use) => {
    const sockets: SocketPlayer[] = []
    let counter = 0

    await use(async (roomCode, options = {}) => {
      counter += 1
      const player = await SocketPlayer.open(baseURL!, roomCode, {
        playerId: options.playerId ?? `e2e-socket-${counter}-${Math.random().toString(16).slice(2, 8)}`,
        name: options.name ?? `socket${counter}`,
      })
      sockets.push(player)
      return player
    })

    for (const socket of sockets) socket.close()
  },
})

export { expect } from '@playwright/test'
export { alwaysHit, stayAfter } from './table'
export type { Snapshot, Seat, Policy } from './table'
