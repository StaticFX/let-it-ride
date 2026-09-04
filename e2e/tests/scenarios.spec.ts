import { test, expect, alwaysHit } from '../support/fixtures'
import { FLIP_SEVEN, LOCAL_BUST } from '../support/seeds'

/** Cards named the way a stacked deck names them — see `hostStacked`. */
const FREEZE = 'freeze'
const SWAP_CARDS = 'swapCards'

/**
 * The moments a round is built around — an action card in your hand, a bust, a
 * flip 7 — reached on purpose rather than by luck.
 *
 * Each of these pins the room's seed, so the same cards come out in the same
 * order every run. The setup has to match what the seed was found with: see
 * `support/seeds.ts`.
 */

test.describe('an action card in your hand', () => {
  test('the local player is handed an action card and picks who it lands on', async ({ app, page }) => {
    test.slow()

    // Four low cards to open with, then a freeze straight into my hand. Said
    // outright rather than hunted for: a seed only produces this by accident
    // and stops the moment the deck's contents change.
    await app.hostStacked('devin', ['2', '3', '4', '5', FREEZE])
    await app.startAndWatch()

    const drawn = await app.table.playUntil(
      (snapshot) => snapshot.screen !== 'board' || !!snapshot.pending?.mine,
      { policy: alwaysHit, description: 'an action card of my own' },
    )
    expect(drawn.screen, 'the stacked deck did not deal me an action card').toBe('board')
    expect(drawn.pending?.mine).toBe(true)
    expect(drawn.pending?.cardDefId).toBe(FREEZE)

    // The table stops and asks. The card is on screen and the prompt is mine.
    await expect(page.getByTestId('pending-action')).toBeVisible()
    await expect(page.getByTestId('turn-prompt')).toHaveText('pick a target!')
    await expect(page.getByTestId('game-board')).toHaveAttribute('data-picking-target', 'true')

    // Only the seats the server said are legal may be clicked.
    const offered = drawn.seats.filter((seat) => seat.targetable)
    expect(offered.length, 'the picker offered nobody at all').toBeGreaterThan(0)

    const target = offered.find((seat) => !seat.isSelf) ?? offered[0]
    const cardId = drawn.pending!.cardId
    expect(cardId, 'the picker has no card behind it').toBeTruthy()

    await app.table.pickTarget(target.id)

    // The client marks the seat the moment it is clicked — that is what sends
    // the card flying to it — though on a fast table the card can already have
    // resolved by the time we look.
    const immediately = await app.table.snapshot()
    if (immediately.pending?.cardId === cardId) {
      expect(immediately.pending.chosen, 'the card never set off for the seat').toBe(target.id)
    }

    // And the table lets go of it rather than sitting on a prompt forever —
    // the failure mode a second copy of the same card used to cause.
    const settled = await app.table.playUntil(
      (snapshot) => snapshot.screen !== 'board' || snapshot.pending?.cardId !== cardId,
      { policy: alwaysHit, timeoutMs: 45_000, description: 'the card I aimed to land' },
    )
    expect(settled.pending?.cardId, 'the picker never let go of the card').not.toBe(cardId)
  })

  test('a card somebody else is holding is shown, but not offered to me', async ({ app, page }) => {
    test.slow()

    // A freeze as the second player's opening card, so somebody else is holding
    // it before the local player has done anything at all. The table has to say
    // so without letting the local player answer for them.
    await app.hostStacked('devin', ['2', FREEZE, '4', '5'])
    await app.startAndWatch()

    const theirs = await app.table.playUntil(
      (snapshot) =>
        snapshot.screen !== 'board' ||
        (!!snapshot.pending && !snapshot.pending.mine),
      { policy: alwaysHit, description: "somebody else's action card" },
    )

    expect(theirs.screen, 'the seeded round ended before a bot drew an action card').toBe('board')
    // Either wording will do — some cards point at a seat and some at cards on
    // the table. What matters is the next line: it is not my pick to make.
    await expect(page.getByTestId('turn-prompt')).toHaveText(/is picking (a target|cards)…/)
    expect(theirs.seats.some((seat) => seat.targetable), 'seats were offered for a card that is not mine').toBe(false)
  })
})

test.describe('a card that points at cards', () => {
  test('a card that asks for cards puts the picker on the table itself', async ({ app, page }) => {
    test.slow()

    // Everyone opens with a card in hand — a trade needs two seats holding
    // something — and then the swap lands in mine.
    await app.hostStacked('devin', ['2', '3', '4', '5', SWAP_CARDS])
    await app.startAndWatch()

    const drawn = await app.table.playUntil(
      (snapshot) =>
        snapshot.screen !== 'board' ||
        (!!snapshot.pending?.mine && snapshot.pickableCards.length > 0),
      { policy: alwaysHit, description: 'a card of my own that picks cards' },
    )
    expect(drawn.screen, 'the stacked deck did not deal me a card-picking card').toBe('board')
    expect(drawn.pending?.cardDefId).toBe(SWAP_CARDS)

    // The seats are out of it entirely — the pick is made off the table.
    expect(drawn.seats.some((seat) => seat.targetable), 'seats were offered for a pick made on cards').toBe(false)
    // Two cards ask for cards and they word it differently — one is a trade,
    // the other a bet. What matters is that the table is asking me for cards.
    await expect(page.getByTestId('turn-prompt')).toHaveText(/pick \d+ more cards?!|bet a card, face down/)

    const first = drawn.pickableCards[0]
    await app.table.pickCard(first)

    // One down: it is marked, and it is no longer one of the ones on offer.
    const halfway = await app.table.playUntil(
      (snapshot) =>
        snapshot.screen !== 'board' ||
        !snapshot.pending?.mine ||
        snapshot.pickedCards.includes(first),
      { timeoutMs: 20_000, description: 'the first card to be marked' },
    )
    if (halfway.screen === 'board' && halfway.pending?.mine) {
      expect(halfway.pickableCards, 'a picked card was still on offer').not.toContain(first)
      // Two cards off one seat would trade a hand with itself, so the rest of
      // that seat's cards step back with it.
      expect(halfway.pickableCards.length).toBeGreaterThan(0)
    }

    // Playing on answers the rest of it; the prompt has to let go either way.
    const settled = await app.table.playUntil(
      (snapshot) => snapshot.screen !== 'board' || !snapshot.pending?.mine,
      { policy: alwaysHit, timeoutMs: 45_000, description: 'the swap to resolve' },
    )
    expect(settled.pending?.mine ?? false).toBe(false)
  })
})

test.describe('busting', () => {
  test(LOCAL_BUST.what, async ({ app, page }) => {
    test.slow()

    await app.setUpScenario(LOCAL_BUST)
    await app.start()

    const summary = await app.table.playUntil(
      (snapshot) => snapshot.myStatus === 'bust' || snapshot.screen === 'summary' || snapshot.screen === 'gameOver',
      { policy: alwaysHit, description: 'the duplicate that ends my round' },
    )

    // The table calls the clash out before it scatters the hand.
    if (summary.screen === 'board') {
      await expect(app.table.mySeat).toHaveAttribute('data-status', 'bust')
      await expect(page.locator('.bust-match-tag').first()).toBeVisible({ timeout: 5_000 })
    }

    await expect(page.getByTestId('round-summary')).toBeVisible({ timeout: 60_000 })
    const me = page.locator('[data-testid="summary-row"][data-player-name="devin"]')
    await expect(me).toHaveAttribute('data-busted', 'true')
    await expect(me).toHaveAttribute('data-points', '0')
    await expect(me).toContainText('duplicate card!')
  })
})

test.describe('flip 7', () => {
  test(FLIP_SEVEN.what, async ({ app, page }) => {
    test.slow()

    await app.setUpScenario(FLIP_SEVEN)
    await app.start()

    await app.table.playRound({ policy: alwaysHit })

    const summary = page.getByTestId('round-summary')
    await expect(summary).toBeVisible()

    // Seven different numbers ends the round for everyone, and the bonus is
    // paid on top of the hand.
    const flip7Id = await summary.getAttribute('data-flip7-id')
    expect(flip7Id, 'the seeded round did not produce a flip 7').toBeTruthy()

    const winner = page.locator(`[data-testid="summary-row"][data-player-id="${flip7Id}"]`)
    await expect(winner).toContainText('flip 7!')
    expect(Number(await winner.getAttribute('data-points'))).toBeGreaterThanOrEqual(15)
  })
})
