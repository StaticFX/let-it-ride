import { test, expect, stayAfter } from '../support/fixtures'

/**
 * Two real browsers at one table. Everything here is about the two of them
 * agreeing: the server is the only thing that decides anything, so what one
 * player does has to show up in the other's window.
 */
test.describe('two players at one table', () => {
  test('the guest sees the host, and the host sees the guest', async ({ app, openPlayer }) => {
    const code = await app.host('devin')

    const guest = await openPlayer()
    await guest.app.join('sam', code)

    await expect(app.players).toHaveCount(2)
    await expect(guest.app.players).toHaveCount(2)
    await expect(app.playerRow('sam')).toBeVisible()
    await expect(guest.app.playerRow('devin')).toBeVisible()

    expect(await guest.app.roomCode()).toBe(code)
    expect(await app.isHost()).toBe(true)
    expect(await guest.app.isHost()).toBe(false)
  })

  test('only the host gets the controls', async ({ app, openPlayer, page }) => {
    const code = await app.host('devin')
    const guest = await openPlayer()
    await guest.app.join('sam', code)

    // The guest has no start button, no kick, no way to add a bot.
    await expect(guest.page.getByTestId('start-game')).toHaveCount(0)
    await expect(guest.page.getByTestId('add-bot')).toHaveCount(0)
    await expect(guest.page.getByTestId('kick-player')).toHaveCount(0)
    await expect(guest.page.getByTestId('waiting-for-host')).toBeVisible()

    await expect(page.getByTestId('start-game')).toBeEnabled()
  })

  test("the host's settings land in the guest's window", async ({ app, openPlayer }) => {
    const code = await app.host('devin')
    const guest = await openPlayer()
    await guest.app.join('sam', code)

    await app.configure({ deck: 'friendly', rounds: 4 })

    await expect(guest.page.getByTestId('table-deck-name')).toHaveText('Friendly')
    await expect(guest.page.getByTestId('table-goal')).toHaveText('best of 4 rounds')

    // The guest can look at the settings but not touch them.
    await guest.app.openSettings()
    await expect(guest.page.getByTestId('settings-screen')).toHaveAttribute('data-host', 'false')
    await expect(guest.page.getByTestId('settings-screen')).toContainText('the host decides these')
  })

  test('a kicked player is told, and is gone from the table', async ({ app, openPlayer }) => {
    const code = await app.host('devin')
    const guest = await openPlayer()
    await guest.app.join('sam', code)
    // Being kicked closes the socket from the server's end.
    guest.guard.allow(/WebSocket|Failed to load resource/i)

    await app.kick('sam')

    await expect(guest.page.getByTestId('disconnected')).toBeVisible()
    await expect(guest.page.getByTestId('disconnected')).toHaveAttribute('data-kicked', 'true')
    await expect(guest.page.getByTestId('disconnected')).toContainText('removed')
    await expect(app.players).toHaveCount(1)

    // And they can walk back to the menu from there.
    await guest.page.getByTestId('back-to-menu').click()
    await expect(guest.page.getByTestId('title-screen')).toBeVisible()
  })

  test('a guest who joined by code still lands on the menu when they leave', async ({ app, openPlayer }) => {
    // Leaving used to hand back whichever screen you came in through, so
    // somebody who joined by code got the join form from a "back to menu" button.
    const code = await app.host('devin')
    const guest = await openPlayer()
    await guest.app.join('sam', code)

    await guest.app.leaveRoom()
    await expect(guest.page.getByTestId('title-screen')).toBeVisible()
    await expect(guest.page.getByTestId('join-screen')).toHaveCount(0)
  })

  test('a guest who leaves frees their seat', async ({ app, openPlayer }) => {
    const code = await app.host('devin')
    const guest = await openPlayer()
    await guest.app.join('sam', code)
    await expect(app.players).toHaveCount(2)

    await guest.app.leaveRoom()
    await expect(app.players).toHaveCount(1)
  })

  test('both play the same round and see the same result', async ({ app, openPlayer }) => {
    test.slow()

    const code = await app.host('devin')
    const guest = await openPlayer()
    await guest.app.join('sam', code)
    await app.configure({ rounds: 1 })

    await app.page.getByTestId('start-game').click()
    await Promise.all([app.table.waitForPlay(), guest.app.table.waitForPlay()])

    // Both players take their turns; whoever is asked answers.
    const [mine, theirs] = await Promise.all([
      app.table.playRound({ policy: stayAfter(2) }),
      guest.app.table.playRound({ policy: stayAfter(2) }),
    ])

    expect(mine.screen).toBe('summary')
    expect(theirs.screen).toBe('summary')

    // The same scoreboard, in both windows.
    const read = async (locator: ReturnType<typeof app.page.getByTestId>) =>
      Promise.all((await locator.all()).map(async (row) => ({
        name: await row.getAttribute('data-player-name'),
        points: await row.getAttribute('data-points'),
        busted: await row.getAttribute('data-busted'),
      })))

    expect(await read(app.page.getByTestId('summary-row')))
      .toEqual(await read(guest.page.getByTestId('summary-row')))
  })

  test('the table fills up and turns the sixth player away', async ({ app, openPlayer, api }) => {
    const code = await app.host('devin')
    await app.addBotsUntil(5)
    await expect(app.players).toHaveCount(5)

    expect((await api.roomInfo(code)).joinable).toBe(false)

    const late = await openPlayer()
    // Looking the full room up is a legitimate request that comes back saying no.
    late.guard.allow(/WebSocket|Failed to load resource/i)
    await late.app.join('sam', code)
    await expect(late.page.getByTestId('lobby-error')).toContainText('full or already underway')
    await expect(app.players).toHaveCount(5)
  })
})

test.describe('the protocol itself', () => {
  test('a socket for a room that does not exist is refused', async ({ openSocket }) => {
    const stranger = await openSocket('ZZZZ')
    const closed = await stranger.waitForClose()
    expect(closed.reason).toContain('no game with that code')
  })

  test('a game already underway will not take a new player', async ({ api, openSocket }) => {
    const room = await api.createRoom('host')
    const host = await openSocket(room.roomCode, { name: 'host' })
    await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
    host.send({ type: 'ADD_BOT' })
    await host.waitForState((s) => s.players.length === 2, { description: 'a bot' })
    host.send({ type: 'START_GAME' })
    await host.waitForState((s) => s.phase === 'PLAYING', { description: 'the game to start' })

    expect((await api.roomInfo(room.roomCode)).joinable).toBe(false)

    const late = await openSocket(room.roomCode, { name: 'late' })
    const refusal = await late.waitFor(
      (m) => m.type === 'ERROR',
      { description: 'a refusal', timeoutMs: 10_000 },
    )
    expect(refusal).toMatchObject({ type: 'ERROR' })
    await late.waitForClose()
  })

  test('a player who drops mid-game keeps their seat and their score', async ({ api, openSocket }) => {
    const room = await api.createRoom('host')
    const host = await openSocket(room.roomCode, { playerId: 'rejoin-host', name: 'host' })
    await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
    host.send({ type: 'ADD_BOT' })
    await host.waitForState((s) => s.players.length === 2, { description: 'a bot' })
    host.send({ type: 'START_GAME' })
    await host.waitForState((s) => s.phase === 'PLAYING', { description: 'the game to start' })

    host.close()

    // Same player id, same seat: the engine folds a disconnected player rather
    // than removing them, so scores and seat order survive a dropped socket.
    const back = await openSocket(room.roomCode, { playerId: 'rejoin-host', name: 'host' })
    const welcome = await back.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome back' })
    expect(welcome).toMatchObject({ roomCode: room.roomCode })

    const state = await back.waitForState((s) => s.players.some((p) => p.id === 'rejoin-host'), {
      description: 'my seat still being there',
    })
    expect(state.players.find((p) => p.id === 'rejoin-host')?.connected).toBe(true)
  })

  test('a nonsense frame is ignored rather than fatal', async ({ api, openSocket }) => {
    const room = await api.createRoom('host')
    const host = await openSocket(room.roomCode, { name: 'host' })
    await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })

    host.sendRaw('this is not json')
    host.sendRaw('{"type":"NO_SUCH_MESSAGE"}')
    host.sendRaw('{}')

    // Still talking.
    host.send({ type: 'PING' })
    await host.waitFor((m) => m.type === 'PONG', { description: 'a pong', timeoutMs: 10_000 })
    expect(host.closed).toBeNull()
  })

  test('a player who is not the host cannot start the game or change it', async ({ api, openSocket }) => {
    const room = await api.createRoom('host')
    const host = await openSocket(room.roomCode, { name: 'host' })
    await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
    const guest = await openSocket(room.roomCode, { name: 'guest' })
    await guest.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
    await host.waitForState((s) => s.players.length === 2, { description: 'the guest to sit down' })

    const before = host.state!.config

    guest.send({ type: 'START_GAME' })
    guest.send({ type: 'ADD_BOT' })
    guest.send({ type: 'SET_CONFIG', config: { ...before, turnTimeSeconds: 11 } })
    guest.send({ type: 'KICK', playerId: host.playerId })

    // Give the server long enough to have acted on any of it.
    await new Promise((resolve) => setTimeout(resolve, 1_500))

    const state = host.state!
    expect(state.phase, 'a guest started the game').toBe('LOBBY')
    expect(state.players, 'a guest added a bot or removed the host').toHaveLength(2)
    expect(state.config.turnTimeSeconds, 'a guest changed the settings').toBe(before.turnTimeSeconds)
  })

  test('the host cannot set a configuration the engine will not accept', async ({ api, openSocket }) => {
    const room = await api.createRoom('host')
    const host = await openSocket(room.roomCode, { name: 'host' })
    await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
    const before = host.state ?? (await host.waitForState(() => true, { description: 'a first state' }))

    host.send({
      type: 'SET_CONFIG',
      config: {
        ...before.config,
        deckPresetId: 'no-such-deck',
        totalRounds: 9999,
        targetScore: -5,
        turnTimeSeconds: 1,
        ruleIds: ['blackjacking', 'blackjacking', 'not-a-rule'],
      },
    })

    const clamped = await host.waitForState(
      (s) => s.config.totalRounds !== before.config.totalRounds || s.config.turnTimeSeconds !== before.config.turnTimeSeconds,
      { description: 'the clamped config' },
    )

    expect(clamped.config.deckPresetId, 'an unknown deck was accepted').not.toBe('no-such-deck')
    expect(clamped.config.totalRounds).toBeLessThanOrEqual(20)
    expect(clamped.config.targetScore).toBeGreaterThanOrEqual(50)
    expect(clamped.config.turnTimeSeconds).toBeGreaterThanOrEqual(10)
    expect(clamped.config.ruleIds, 'a made-up rule was accepted').toEqual(['blackjacking'])
  })

  test('never sends the deck to a client, only its size', async ({ api, openSocket }) => {
    const room = await api.createRoom('host')
    const host = await openSocket(room.roomCode, { name: 'host' })
    await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
    host.send({ type: 'ADD_BOT' })
    await host.waitForState((s) => s.players.length === 2, { description: 'a bot' })
    host.send({ type: 'START_GAME' })
    const playing = await host.waitForState((s) => s.phase === 'PLAYING' && s.deckCount > 0, {
      description: 'the game to start',
    })

    expect(playing.deckCount).toBeGreaterThan(0)
    // Knowing what is coming next would give the whole game away.
    expect(Object.keys(playing)).not.toContain('deck')
    expect(Object.keys(playing)).not.toContain('discard')
  })
})
