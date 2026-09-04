import { test, expect } from '../support/fixtures'

/**
 * The REST surface and the static bundle, checked against a running server —
 * the same jar the container ships. These are the doors every client comes in
 * through, so their shape is part of the contract even though no browser is
 * involved here.
 */
test.describe('http api', () => {
  test('serves the SPA shell from the jar', async ({ request }) => {
    const response = await request.get('/')
    expect(response.status()).toBe(200)
    const html = await response.text()
    expect(html).toContain('<div id="root">')
    expect(html, 'the built bundle should be linked, not a dev entry point')
      .toMatch(/<script[^>]+src="\/assets\/[^"]+\.js"/)
  })

  test('falls through unknown paths to the SPA so deep links work', async ({ request }) => {
    const response = await request.get('/some/route/the/client/owns')
    expect(response.status()).toBe(200)
    expect(await response.text()).toContain('<div id="root">')
  })

  test('reports its health', async ({ api }) => {
    const health = await api.health()
    expect(health.status).toBe('ok')
    expect(health.rooms).toBeGreaterThanOrEqual(0)
  })

  test('serves every sound the client asks for', async ({ request }) => {
    // The player never hears a failure — undecodable audio is swallowed — so a
    // missing sample would otherwise ship silently. Written out in full rather
    // than assembled from names: this is the list in `SOURCES` in `sfx.ts`, and
    // a sample that was renamed or dropped should fail here rather than be
    // quietly reconstructed by the same rule that lost it.
    const sounds = [
      '/sounds/draw-card.m4a',
      '/sounds/action-card.m4a',
      '/sounds/given-action-card-to-player.wav',
      '/sounds/bust.m4a',
      '/sounds/freeze.m4a',
      '/sounds/flip7.m4a',
      '/sounds/go-out.m4a',
      '/sounds/round-ended.m4a',
      '/sounds/timer-less-than-10s.wav',
      '/sounds/button-clicks/Click_1.wav',
      '/sounds/button-clicks/Click_2.wav',
      '/sounds/keystroke.m4a',
    ]
    for (const path of sounds) {
      const response = await request.get(path)
      expect(response.status(), path).toBe(200)
      // A path the jar does not hold falls through to the SPA shell, which is a
      // perfectly good 200 with a perfectly good body — so what is actually
      // being asked here is whether what came back is audio.
      expect(response.headers()['content-type'], `${path} is not audio`).toMatch(/^audio\//)
      expect((await response.body()).byteLength, `${path} is empty`).toBeGreaterThan(0)
    }
  })

  test('the catalog describes every deck, card and house rule', async ({ api }) => {
    const catalog = await api.catalog()

    expect(catalog.minPlayers).toBe(2)
    expect(catalog.maxPlayers).toBe(5)
    expect(catalog.flip7Target).toBe(7)
    expect(catalog.flip7Bonus).toBe(15)

    expect(catalog.decks.map((deck) => deck.id)).toEqual(
      expect.arrayContaining(['flip7', 'letitride', 'pure', 'classic52', 'chaos', 'gambler', 'friendly']),
    )
    expect(catalog.rules.map((rule) => rule.id)).toEqual(
      expect.arrayContaining(['blackjacking', 'doubleIt', 'wompWomp', 'doubleDraw', 'noForcedFirst']),
    )

    for (const deck of catalog.decks) {
      expect(deck.name, `${deck.id} needs a name`).toBeTruthy()
      expect(deck.description, `${deck.id} needs a description`).toBeTruthy()
      expect(deck.cardCount, `${deck.id} should not be empty`).toBeGreaterThan(0)

      // Every card the client may be asked to draw has to be describable, or
      // the table renders a blank face.
      const listed = deck.contents.reduce((total, entry) => total + entry.count, 0)
      expect(listed, `${deck.id}'s listing does not add up to its size`).toBe(deck.cardCount)

      for (const entry of deck.contents) {
        expect(entry.count).toBeGreaterThan(0)
        expect(entry.card.label, `a card in ${deck.id} has no label`).toBeTruthy()
        if (entry.card.kind === 'action') {
          expect(catalog.actions.map((a) => a.id)).toContain(entry.card.defId)
        }
        if (entry.card.kind === 'passive') {
          expect(catalog.passives.map((p) => p.id)).toContain(entry.card.defId)
        }
      }
    }

    for (const action of catalog.actions) {
      expect(action.name, `${action.id} needs a name`).toBeTruthy()
      expect(action.description, `${action.id} needs a description`).toBeTruthy()
      expect(action.sigil, `${action.id} needs a sigil to draw`).toBeTruthy()
    }
    for (const passive of catalog.passives) {
      expect(passive.name).toBeTruthy()
      expect(passive.description).toBeTruthy()
      expect(passive.sigil, `${passive.id} needs a sigil to draw`).toBeTruthy()
      expect(['flat', 'double', 'none', 'voidUnlessFlip', 'halve']).toContain(passive.scoring)
    }

    // The effect cards are cards like any other — everything in this game is —
    // so they come down with a face to draw, and no deck may hold one.
    const effects = catalog.passives.filter((passive) => passive.deckable === false)
    expect(effects.map((passive) => passive.id)).toEqual(
      expect.arrayContaining(['noFlip', 'mustFlip', 'bomber', 'halved']),
    )
    for (const deck of catalog.decks) {
      for (const effect of effects) {
        expect(deck.deck.passiveCards, `${deck.id} deals a ${effect.id}`).not.toContain(effect.id)
      }
    }
  })

  test('opens a room and describes it', async ({ api }) => {
    const room = await api.createRoom('devin')
    expect(room.roomCode).toMatch(/^[A-Z0-9]{4}$/)
    expect(room.playerId).toBeTruthy()

    const info = await api.roomInfo(room.roomCode)
    expect(info).toMatchObject({
      roomCode: room.roomCode,
      players: 0,
      phase: 'LOBBY',
      joinable: true,
    })
  })

  test('looks a room up however the code is cased', async ({ api, request }) => {
    const room = await api.createRoom('devin')
    const lower = await request.get(`/api/rooms/${room.roomCode.toLowerCase()}`)
    expect(lower.status()).toBe(200)
    expect((await lower.json()).roomCode).toBe(room.roomCode)
  })

  test('refuses a room without a name', async ({ request }) => {
    for (const body of [{}, { name: '' }, { name: '   ' }]) {
      const response = await request.post('/api/rooms', { data: body })
      expect(response.status(), JSON.stringify(body)).toBe(400)
      expect((await response.json()).error).toBeTruthy()
    }
  })

  test('survives a body that is not a room request at all', async ({ request }) => {
    const response = await request.post('/api/rooms', {
      headers: { 'Content-Type': 'application/json' },
      data: '"not an object"',
    })
    // A bad body is the client's fault, never a 500.
    expect(response.status()).toBe(400)
  })

  test('404s a room that does not exist', async ({ request }) => {
    const response = await request.get('/api/rooms/ZZZZ')
    expect(response.status()).toBe(404)
    expect((await response.json()).error).toBeTruthy()
  })

  test('trims a long name rather than rejecting it', async ({ api }) => {
    const room = await api.createRoom('a-name-far-longer-than-sixteen-characters')
    expect(room.roomCode).toMatch(/^[A-Z0-9]{4}$/)
  })
})

test.describe('the stacked-deck hook', () => {
  test('deals the cards it was asked for, in order, and keeps the deck whole', async ({ api, openSocket }) => {
    const stack = ['7', '3', 'freeze']
    const room = await api.createRoom('host', { stack })
    const host = await openSocket(room.roomCode, { playerId: 'stack-host', name: 'host' })
    await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
    host.send({ type: 'ADD_BOT' })
    await host.waitForState((s) => s.players.length === 2, { description: 'the bot to sit down' })

    // The chaos deck, so a freeze is in it to be stacked.
    const catalog = await api.catalog()
    const chaos = catalog.decks.find((deck) => deck.id === 'chaos')!
    const lobby = await host.waitForState((s) => s.phase === 'LOBBY', { description: 'the lobby' })
    host.send({
      type: 'SET_CONFIG',
      config: { ...lobby.config, deckPresetId: 'chaos', deck: chaos.deck, turnTimeSeconds: 120 },
    })
    await host.waitForState((s) => s.config.deckPresetId === 'chaos', { description: 'the deck to change' })

    const sizeBefore = chaos.cardCount
    host.send({ type: 'START_GAME' })

    // Two players, so the first two entries are the two opening cards.
    const dealt = await host.waitForState(
      (s) => s.phase === 'PLAYING' && s.dealQueue.length === 0,
      { description: 'the opening deal' },
    )
    expect(dealt.players[0].hand[0]?.label, 'the host was not dealt the first card in the stack').toBe('7')
    expect(dealt.players[1].hand[0]?.label).toBe('3')

    // Nothing was conjured or lost: the named cards were lifted out of the
    // shuffle and put on top of it, so the deck is still the deck.
    const onTable = dealt.players.reduce((total, p) => total + p.hand.length + p.passives.length, 0)
    expect(dealt.deckCount + dealt.discardCount + onTable).toBe(sizeBefore)
  })

  test('a name the deck does not hold is skipped rather than fatal', async ({ api, openSocket }) => {
    const room = await api.createRoom('host', { stack: ['definitelyNotACard', '7'] })
    const host = await openSocket(room.roomCode, { playerId: 'stack-junk', name: 'host' })
    await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
    host.send({ type: 'ADD_BOT' })
    await host.waitForState((s) => s.players.length === 2, { description: 'the bot to sit down' })
    host.send({ type: 'START_GAME' })

    const dealt = await host.waitForState(
      (s) => s.phase === 'PLAYING' && s.dealQueue.length === 0,
      { description: 'the opening deal' },
    )
    expect(dealt.players[0].hand[0]?.label, 'the rest of the stack should still apply').toBe('7')
  })
})

test.describe('the seed hook', () => {
  test('is on for this harness, and pins a room to one shuffle', async ({ api, openSocket }) => {
    expect(
      (await api.health()).testHooks,
      'the suite needs LETITRIDE_TEST_HOOKS=1 to replay a deal',
    ).toBe(true)

    // Two rooms, one seed, the same two players in the same order: the deal
    // has to come out identically or nothing seeded can be relied on.
    const deals: string[][] = []
    for (const attempt of [1, 2]) {
      const room = await api.createRoom('host', { seed: 20260903 })
      const host = await openSocket(room.roomCode, { playerId: `seed-host-${attempt}`, name: 'host' })
      await host.waitFor((m) => m.type === 'WELCOME', { description: 'a welcome' })
      host.send({ type: 'ADD_BOT' })
      await host.waitForState((s) => s.players.length === 2, { description: 'the bot to sit down' })
      host.send({ type: 'START_GAME' })

      const dealt = await host.waitForState(
        (s) => s.phase === 'PLAYING' && s.dealQueue.length === 0,
        { description: 'the opening deal' },
      )
      // Card ids come from the deck's build order, so this is the shuffle
      // itself rather than a summary of it.
      deals.push([
        ...dealt.players.flatMap((p) => [...p.hand, ...p.passives].map((card) => card.id)),
        `pending:${dealt.pendingAction?.cardId ?? 'none'}`,
        `deck:${dealt.deckCount}`,
      ])
    }

    expect(deals[0]).toEqual(deals[1])
  })
})
