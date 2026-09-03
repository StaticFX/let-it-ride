import { test, expect, alwaysHit } from '../support/fixtures'
import { FLIP_SEVEN, LOCAL_ACTION_CARD, LOCAL_BUST } from '../support/seeds'

/**
 * The moments a round is built around — an action card in your hand, a bust, a
 * flip 7 — reached on purpose rather than by luck.
 *
 * Each of these pins the room's seed, so the same cards come out in the same
 * order every run. The setup has to match what the seed was found with: see
 * `support/seeds.ts`.
 */

test.describe('an action card in your hand', () => {
  test(LOCAL_ACTION_CARD.what, async ({ app, page }) => {
    test.slow()

    await app.setUpScenario(LOCAL_ACTION_CARD)
    // Watch from the first card: on this deck the opening card itself can be
    // the action card, and `start()` would answer that prompt for us.
    await app.startAndWatch()

    // Draw until the deck hands us one.
    const drawn = await app.table.playUntil(
      (snapshot) => snapshot.screen !== 'board' || !!snapshot.pending?.mine,
      { policy: alwaysHit, description: 'an action card of my own' },
    )
    expect(drawn.screen, 'the seeded round did not deal me an action card').toBe('board')
    expect(drawn.pending?.mine).toBe(true)

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

    // The bots draw far more cards than the local player does, so one of them
    // holding an action card is the common case — and the table has to say so
    // without letting the local player answer for them.
    await app.setUpScenario(LOCAL_ACTION_CARD)
    // Watch from the first card: on this deck the opening card itself can be
    // the action card, and `start()` would answer that prompt for us.
    await app.startAndWatch()

    const theirs = await app.table.playUntil(
      (snapshot) =>
        snapshot.screen !== 'board' ||
        (!!snapshot.pending && !snapshot.pending.mine),
      { policy: alwaysHit, description: "somebody else's action card" },
    )

    expect(theirs.screen, 'the seeded round ended before a bot drew an action card').toBe('board')
    await expect(page.getByTestId('turn-prompt')).toHaveText('is picking a target…')
    expect(theirs.seats.some((seat) => seat.targetable), 'seats were offered for a card that is not mine').toBe(false)
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
