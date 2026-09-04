import { test, expect, alwaysHit, stayAfter } from '../support/fixtures'

/**
 * What happens when things go wrong: the network drops, a player walks away
 * from their turn, the page is reloaded mid-game.
 */

test.describe('a dropped connection', () => {
  test('reconnects on its own and the table carries on', async ({ app, page }) => {
    test.slow()

    await app.hostVersusBots('devin')
    await app.start()

    const before = await app.table.snapshot()
    expect(before.screen).toBe('board')

    // Cut the socket the way a flaky network does — the client is not told.
    await app.dropConnection()

    // The client says so, then puts itself back together.
    await expect(page.getByTestId('reconnecting')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('reconnecting')).toBeHidden({ timeout: 30_000 })

    // Same game, same seat, still playable.
    const after = await app.table.snapshot()
    expect(after.screen).toBe('board')
    expect(after.round).toBe(before.round)
    expect(after.seats.map((seat) => seat.name)).toEqual(before.seats.map((seat) => seat.name))

    const summary = await app.table.playRound({ policy: alwaysHit })
    expect(summary.screen).toBe('summary')
  })

  test('a reload drops you back into the same game', async ({ app, page }) => {
    test.slow()

    await app.hostVersusBots('devin')
    await app.start()
    const before = await app.table.snapshot()

    // A reload throws away the whole client, including which room it was in —
    // nothing is persisted but the player's name, so this lands at the front
    // door rather than back at the table. What matters is that it lands
    // somewhere usable instead of on a broken screen.
    await page.reload()
    await expect(page.getByTestId('title-screen')).toBeVisible()
    await expect(page.getByTestId('name-input')).toHaveValue('devin')
    expect(before.screen).toBe('board')

    await app.hostVersusBots('devin')
    await expect(page.getByTestId('start-game')).toBeEnabled()
  })

  test('gives up with a way out when the server never comes back', async ({ app, page, context, consoleGuard }) => {
    test.slow()
    // Every retry failing is the point of this one.
    consoleGuard.allow(/WebSocket|Failed to load resource|ERR_INTERNET_DISCONNECTED/i)

    await app.hostVersusBots('devin')
    await app.start()

    // Pull the network out from under it, so every retry fails too. HTTP
    // routing cannot do this — a websocket never goes through it.
    await context.setOffline(true)
    await app.dropConnection()

    // Five backoffs, the longest 8s, so giving up is deliberately slow.
    await expect(page.getByTestId('disconnected')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('disconnected')).toContainText('lost the connection')
    await expect(page.getByTestId('disconnected')).toHaveAttribute('data-kicked', 'false')

    // There is a way out that does not need the network.
    await page.getByTestId('back-to-menu').click()
    await expect(page.getByTestId('title-screen')).toBeVisible()

    await context.setOffline(false)
  })
})

test.describe('the turn clock', () => {
  test('takes the turn away from a player who never answers', async ({ app, page }) => {
    test.slow()

    await app.hostVersusBots('devin')
    // The shortest clock the lobby offers, so this does not take all day.
    await app.configure({ turnSeconds: 10 })
    await expect(page.getByTestId('table-timer')).toHaveText('10s')

    await app.start()

    // Wait for our turn, then do nothing at all.
    const mine = await app.table.playUntil((s) => s.myTurn && s.buttonsVisible, {
      timeoutMs: 90_000,
      description: 'my turn',
    })
    expect(mine.myTurn).toBe(true)
    await expect(page.getByTestId('turn-prompt')).toHaveText('your move!')

    // The clock runs out and the round moves on without us.
    const after = await app.table.playUntil(
      (s) => s.screen !== 'board' || !s.myTurn,
      { policy: () => 'hit', timeoutMs: 40_000, description: 'the clock to run out' },
    )

    // Timing out goes out — it never leaves the table stuck on us.
    if (after.screen === 'board') {
      const me = after.seats.find((seat) => seat.isSelf)
      expect(['stayed', 'bust', 'active']).toContain(me?.status)
    }
  })

  test('is shown for a human and counts down; bots are not on it', async ({ app, page }) => {
    await app.hostVersusBots('devin')
    await app.configure({ turnSeconds: 30 })
    await app.start()

    await app.table.playUntil((s) => s.myTurn && s.buttonsVisible, {
      timeoutMs: 90_000,
      description: 'my turn',
    })

    const clock = page.getByTestId('turn-clock')
    await expect(clock).toBeVisible()
    await expect(page.getByTestId('turn-prompt')).toHaveText('your move!')

    // Near the configured 30, but not exactly: the deadline is an absolute
    // server timestamp and the two clocks need not agree to the millisecond.
    const started = Number(await clock.getAttribute('data-seconds'))
    expect(started, `the clock started at ${started}s for a 30s turn`).toBeGreaterThan(20)
    expect(started, `the clock started at ${started}s for a 30s turn`).toBeLessThanOrEqual(35)

    // It is a clock, so it has to move.
    await expect
      .poll(async () => Number(await clock.getAttribute('data-seconds')), { timeout: 10_000 })
      .toBeLessThan(started)

    // Once the turn is somebody else's — a bot's — there is no clock at all.
    await app.table.hit()
    await app.table.playUntil((s) => s.screen !== 'board' || !s.myTurn, {
      timeoutMs: 30_000,
      description: 'the turn to pass on',
    })
    const stillPlaying = await app.table.snapshot()
    if (stillPlaying.screen === 'board' && !stillPlaying.pickingTarget) {
      await expect(clock, 'a bot was put on the clock').toHaveCount(0)
    }
  })
})

test.describe('a room nobody is in', () => {
  test('is still there for a player who comes back', async ({ api }) => {
    const room = await api.createRoom('devin')
    // Rooms are swept only after ten idle minutes, so a player who closed the
    // tab by accident can still walk back in.
    const info = await api.roomInfo(room.roomCode)
    expect(info.joinable).toBe(true)
  })
})

test.describe('the table under stress', () => {
  test('a burst of clicks does not double-draw or wedge the round', async ({ app }) => {
    test.slow()

    await app.hostVersusBots('devin')
    await app.start()

    const before = await app.table.playUntil((s) => s.myTurn && s.buttonsVisible, {
      timeoutMs: 90_000,
      description: 'my turn',
    })
    const myHand = before.seats.find((seat) => seat.isSelf)!.handSize

    // Hammer the button. The server decides whose turn it is, so only the
    // first of these can possibly count.
    await Promise.all([
      app.table.hitButton.click({ force: true }),
      app.table.hitButton.click({ force: true }),
      app.table.hitButton.click({ force: true }),
    ])

    // Watching, not playing: `playUntil` takes a turn whenever it finds one
    // going spare, and its default policy draws. If the turn came back round
    // before the first draw was noticed, the harness itself would deal the
    // second card this test is looking for. Going out instead keeps the only
    // cards that arrive the ones the burst asked for.
    const after = await app.table.playUntil(
      (s) => s.screen !== 'board' || (s.seats.find((seat) => seat.isSelf)?.handSize ?? 0) !== myHand || !s.myTurn,
      { timeoutMs: 30_000, policy: stayAfter(1), description: 'the draw to land' },
    )

    if (after.screen === 'board') {
      const me = after.seats.find((seat) => seat.isSelf)!
      // One click, one card — three clicks must not deal three.
      expect(me.handSize, 'a burst of clicks drew more than one card').toBeLessThanOrEqual(myHand + 1)
    }

    // And the round still finishes.
    const summary = await app.table.playRound({ policy: alwaysHit })
    expect(summary.screen).toBe('summary')
  })
})
