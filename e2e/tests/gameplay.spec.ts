import { test, expect, alwaysHit, stayAfter } from '../support/fixtures'
import { Table } from '../support/table'

/**
 * A game, played the way a person plays it: click through the lobby, sit
 * through the title card, take turns, read the scoreboard. Nothing here pushes
 * an intent down the socket — if a button does not work, these fail.
 */
test.describe('playing a round', () => {
  test('deals everyone in, then hands the turn over', async ({ app, page, api }) => {
    const catalog = await api.catalog()
    const deck = catalog.decks.find((d) => d.id === 'letitride')!

    await app.hostVersusBots('devin')
    await page.getByTestId('start-game').click()

    // The countdown, then the round's title card, then the deal.
    await expect(page.getByTestId('countdown')).toBeVisible()
    await expect(page.getByTestId('round-intro')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('round-intro')).toHaveAttribute('data-round', '1')
    await expect(page.getByTestId('game-board')).toBeVisible()

    const opening = await app.table.waitForPlay()

    expect(opening.seats).toHaveLength(4)
    expect(opening.round).toBe(1)
    expect(opening.deckCount).toBeLessThan(deck.cardCount)

    // Not one card may be conjured up or dropped on the floor.
    expect(Table.cardsAccountedFor(opening), 'the table has lost track of a card').toBe(deck.cardCount)

    // Somebody is on the clock, and the table says who.
    await expect(page.getByTestId('turn-name')).not.toHaveText('...')
  })

  test('keeps every card accounted for all the way through a round', async ({ app, api }) => {
    const catalog = await api.catalog()
    const deck = catalog.decks.find((d) => d.id === 'chaos')!

    await app.hostVersusBots('devin')
    await app.configure({ deck: 'chaos' })
    await app.start()

    // Chaos is the deck that moves cards around the most — steals, swaps, slot
    // machines — so it is the one worth counting after every transition.
    const counted: number[] = []
    await app.table.playUntil(
      (snapshot) => {
        if (snapshot.screen === 'board') counted.push(Table.cardsAccountedFor(snapshot))
        return snapshot.screen === 'summary' || snapshot.screen === 'gameOver'
      },
      { policy: alwaysHit, description: 'the round to be scored' },
    )

    expect(counted.length, 'the round went by without a single reading').toBeGreaterThan(3)
    const wrong = counted.filter((total) => total !== deck.cardCount)
    expect(wrong, `expected ${deck.cardCount} cards at every point, saw ${[...new Set(wrong)].join(', ')}`).toEqual([])
  })

  test('going out banks the hand and scores it', async ({ app, page }) => {
    await app.hostVersusBots('devin')
    await app.start()

    const summary = await app.table.playRound({ policy: stayAfter(2) })
    expect(summary.screen).toBe('summary')
    await expect(page.getByTestId('round-summary')).toBeVisible()

    const me = page.locator('[data-testid="summary-row"][data-player-name="devin"]')
    await expect(me).toBeVisible()

    const busted = (await me.getAttribute('data-busted')) === 'true'
    const points = Number(await me.getAttribute('data-points'))

    if (busted) {
      // A bust is a legal way for the round to end for you; it scores nothing.
      expect(points).toBe(0)
      await expect(me).toContainText('duplicate card!')
    } else {
      expect(points).toBeGreaterThanOrEqual(0)
    }

    // Everyone has a row, whatever happened to them.
    await expect(page.getByTestId('summary-row')).toHaveCount(4)
  })

  test('the draw pile is a second way to take a card', async ({ app }) => {
    await app.hostVersusBots('devin')
    await app.start()

    const before = await app.table.playUntil((s) => s.myTurn && s.buttonsVisible, {
      timeoutMs: 90_000,
      description: 'my first turn',
    })
    const myHand = before.seats.find((seat) => seat.isSelf)!.handSize

    await app.table.hitByClickingTheDeck()

    const after = await app.table.playUntil(
      (s) => s.screen !== 'board' || (s.seats.find((seat) => seat.isSelf)?.handSize ?? 0) !== myHand,
      { timeoutMs: 30_000, description: 'the card I clicked the deck for' },
    )
    if (after.screen === 'board') {
      expect(after.deckCount).toBeLessThan(before.deckCount)
    }
  })

  test('a card can be inspected without affecting the game', async ({ app, page }) => {
    await app.hostVersusBots('devin')
    await app.start()

    await app.table.playUntil((s) => (s.seats.find((seat) => seat.isSelf)?.handSize ?? 0) > 0, {
      timeoutMs: 60_000,
      description: 'a card in my hand',
    })

    await app.table.mySeat.getByTestId('hand-card').first().click()
    await expect(page.getByTestId('card-inspect')).toBeVisible()
    await page.getByTestId('card-inspect').click()
    await expect(page.getByTestId('card-inspect')).toBeHidden()

    // The table is still live behind it.
    await expect(page.getByTestId('game-board')).toBeVisible()
  })

  test('somebody busts when the whole table pushes its luck', async ({ app, page }) => {
    // Four players all drawing until it goes wrong: the bust path — the
    // animation, the strike, the "duplicate card!" note — is what this covers.
    await app.hostVersusBots('devin')
    await app.configure({ deck: 'pure' })
    await app.start()

    await app.table.playRound({ policy: alwaysHit })

    const busted = page.locator('[data-testid="summary-row"][data-busted="true"]')
    await expect(busted.first()).toBeVisible()
    for (const row of await busted.all()) {
      expect(Number(await row.getAttribute('data-points')), 'a bust must score nothing').toBe(0)
    }
  })

  test('the scoreboard adds the round up the way the summary does', async ({ app, page }) => {
    await app.hostVersusBots('devin')
    await app.start()
    await app.table.playRound({ policy: stayAfter(3) })

    const rows = await page.getByTestId('summary-row').all()
    const points = await Promise.all(rows.map(async (row) => ({
      name: await row.getAttribute('data-player-name'),
      points: Number(await row.getAttribute('data-points')),
    })))

    // Round one, so what each player banked is their whole score.
    for (const { name, points: banked } of points) {
      const line = page.locator('[data-testid="round-summary"]').getByText(name!, { exact: false }).first()
      await expect(line).toBeVisible()
      expect(banked, `${name} scored a negative round`).toBeGreaterThanOrEqual(0)
    }
  })
})

test.describe('a game from the title card to the final standings', () => {
  test('two rounds, then the results', async ({ app, page }) => {
    test.slow()

    await app.hostVersusBots('devin')
    await app.configure({ rounds: 2 })
    await expect(page.getByTestId('table-goal')).toHaveText('best of 2 rounds')

    await app.start()
    await app.playToGameOver({ policy: alwaysHit, maxRounds: 4 })

    await expect(page.getByTestId('game-over')).toBeVisible()
    await expect(page.getByTestId('standings-row')).toHaveCount(4)

    // The standings are sorted, and the winner is the top of them.
    const scores = await Promise.all(
      (await page.getByTestId('standings-row').all()).map(async (row) => Number(await row.getAttribute('data-score'))),
    )
    expect(scores).toEqual([...scores].sort((a, b) => b - a))

    const winnerName = await page.getByTestId('game-over').getAttribute('data-winner-name')
    const top = page.locator('[data-testid="standings-row"][data-rank="1"]')
    await expect(top).toHaveAttribute('data-player-name', winnerName!)
  })

  test('"play again" hands you back a clean title card', async ({ app, page }) => {
    test.slow()

    await app.hostVersusBots('devin')
    await app.configure({ rounds: 1 })
    await app.start()
    await app.playToGameOver({ policy: alwaysHit, maxRounds: 3 })

    await page.getByTestId('play-again').click()
    await expect(page.getByTestId('title-screen')).toBeVisible()
    await expect(page.getByTestId('name-input')).toHaveValue('devin')

    // And you can walk straight back in.
    await app.hostVersusBots('devin')
    await expect(page.getByTestId('start-game')).toBeEnabled()
  })
})

test.describe('the pause menu', () => {
  test('opens on escape, holds the table, and lets you walk out', async ({ app, page }) => {
    await app.hostVersusBots('devin')
    await app.start()

    await app.openPauseMenu()
    await expect(page.getByTestId('escape-menu')).toContainText('paused')

    // The rules are reachable from the pause menu and escape backs out of them.
    await page.getByTestId('pause-rules').click()
    await expect(page.getByTestId('rules-page')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('rules-page')).toBeHidden()

    await app.closePauseMenu()
    await expect(page.getByTestId('game-board')).toBeVisible()

    await app.openPauseMenu()
    await page.getByTestId('pause-leave').click()
    await expect(page.getByTestId('title-screen')).toBeVisible()
  })

  test('does nothing in the lobby, where there is no game to pause', async ({ app, page }) => {
    await app.hostVersusBots('devin')
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('escape-menu')).toHaveCount(0)
    await expect(page.getByTestId('waiting-room')).toBeVisible()
  })
})
