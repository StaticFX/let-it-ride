import { test, expect, alwaysHit } from '../support/fixtures'
import type { App } from '../support/app'
import {
  expectNoHorizontalScroll,
  expectOnScreen,
  expectTapTarget,
} from '../support/layout'

/** Cards named the way a stacked deck names them — see `hostStacked`. */
const FREEZE = 'freeze'
const SPIN_TABLE = 'spinTable'

/**
 * The table on a screen with no cursor.
 *
 * Everything here runs under the three `@mobile` projects in
 * `playwright.config.ts` — a phone upright, a phone on its side, and a tablet —
 * so each spec is written once and asked three times. Two rules for anything
 * added here:
 *
 * **Tap, never click.** `locator.click()` dispatches `mousemove`, `mouseover`
 * and `mouseenter` even in a touch context, so a spec written with it passes
 * against exactly the hover-only code these projects exist to catch.
 * `locator.tap()` sends touch events and nothing else.
 *
 * **Assert the branch, do not infer it.** The board publishes `data-compact`
 * and `data-touch`; check those rather than reasoning from a viewport size,
 * which stops being true the day a threshold moves.
 */
/**
 * A table with an unhurried clock, and the local player sat at it.
 *
 * The clock matters more here than anywhere else in the suite: these specs
 * measure things — every box read, every tap target checked — and the suite
 * runs at a quarter pace, which takes the default thirty seconds down to seven
 * and a half. Spend that on assertions and the turn is taken away, the seat is
 * out, the bots finish the round, and the board the spec was measuring is
 * replaced by the scoreboard.
 */
async function seatMe(app: App, bots = 3): Promise<void> {
  // Plain number cards off the top, one per seat, so every hand on the table
  // has something in it. Dealt at random the opening card can be an action
  // card, which resolves on the table and never joins a hand — and a spec that
  // measures the cards in front of you has nothing to measure.
  await app.hostStacked('devin', ['2', '3', '4', '5'], { bots })
  await app.start()
  await waitForMyTurn(app)
}

/**
 * Waits until the table is actually asking the local player for something.
 *
 * `app.start()` hands back once the opening deal is over, which is not the same
 * as being on the clock — the deal can turn up an action card that stops the
 * table, and round one does not always start with the host. Every spec below
 * that presses something has to know the difference.
 */
async function waitForMyTurn(app: App): Promise<void> {
  await app.table.playUntil(
    (snapshot) =>
      snapshot.screen !== 'board' ||
      (snapshot.myTurn && !snapshot.dealing && !snapshot.pickingTarget),
    { description: 'the table to ask me for a move' },
  )
}

test.describe('@mobile the table on a phone', () => {
  test('deals, plays and fits', async ({ app, page }) => {
    await seatMe(app)

    const board = page.getByTestId('game-board')
    await expect(board).toHaveAttribute('data-touch', 'true')

    await expectNoHorizontalScroll(page, 'the table')

    // Everything you press to take a turn, on screen and big enough to press.
    await expect(page.getByTestId('action-buttons')).toHaveAttribute('data-visible', 'true')
    for (const id of ['hit', 'stay']) {
      await expectOnScreen(page, page.getByTestId(id), id)
      await expectTapTarget(page.getByTestId(id), id)
    }
    await expectTapTarget(page.getByTestId('draw-pile'), 'the draw pile')

    // ...and it takes a tap. The deck rather than the hand: what comes off the
    // top might be an action card, which resolves on the table and never joins
    // a hand at all — so a hand that has not grown is not proof of anything.
    const before = (await app.table.snapshot()).deckCount
    await page.getByTestId('hit').tap()
    await expect
      .poll(async () => (await app.table.snapshot()).deckCount, {
        message: 'nothing came off the deck after tapping "let it ride!"',
      })
      .toBeLessThan(before)

    await expectNoHorizontalScroll(page, 'the table mid-round')
  })

  test('seats a full table without anybody falling off the felt', async ({ app, page }) => {
    await app.hostVersusBots('devin', 3)
    await app.addBotsUntil(10)
    await app.configure({ turnSeconds: 120 })
    await app.start()

    const seats = page.getByTestId('seat')
    await expect(seats).toHaveCount(10)

    const count = await seats.count()
    for (let i = 0; i < count; i++) {
      const seat = seats.nth(i)
      const name = (await seat.getAttribute('data-player-name')) ?? `seat ${i}`
      // Generous slack: a seat is drawn scaled and its hand fans out under it,
      // so what is being asked here is "is this player on the screen at all",
      // not "is every pixel of them inside the frame".
      await expectOnScreen(page, seat, `${name}'s seat`, 24)
    }

    await expectNoHorizontalScroll(page, 'a table of ten')
  })

  test('your own hand can be aimed at when it is not your turn', async ({ app, page }) => {
    await seatMe(app)

    const cards = page.locator('[data-testid="seat"][data-self="true"] [data-testid="hand-card"]')
    await expect(cards.first()).toBeVisible()
    await expectTapTarget(cards.last(), 'the last card in my own hand')

    // ...and it stays open once you are off the clock, which is where most of a
    // round is actually spent. On a wide table the hand folds away to 0.75 of
    // `small` until you point at it; there is nothing to point with here, so it
    // does not fold. Only asked while the table is still up: the bots can
    // finish the round between the tap and the question.
    await page.getByTestId('stay').tap()
    if (await page.getByTestId('game-board').isVisible()) {
      await expectTapTarget(cards.last(), 'the last card in my own hand, off the clock')
    }
  })

  test('a card can be read by tapping it', async ({ app, page }) => {
    await seatMe(app)

    // Only once nothing is being asked of the table. A card that opens a prompt
    // takes over what a tap on a card *means* — that is the whole of the
    // picking model — so reading one is a question for a table at rest.
    await expect(page.getByTestId('game-board')).toHaveAttribute('data-picking-target', 'false')
    await expect(page.getByTestId('game-board')).toHaveAttribute('data-dealing', 'false')

    const card = page.locator('[data-testid="seat"][data-self="true"] [data-testid="hand-card"]').first()
    const inspector = page.getByTestId('card-inspect')
    // Tapped until it takes. A card that was dealt a moment ago is still
    // sliding into its place in the fan — `DealtCard` flies it in from the
    // deck long after the deal itself is over — and a tap at a card that is
    // still moving is a miss, which is exactly what a player would do about it.
    await expect
      .poll(async () => {
        if (await inspector.isVisible()) return true
        await card.tap()
        return inspector.isVisible()
      }, { message: 'tapping a card never opened the inspector' })
      .toBe(true)
    await expectOnScreen(page, page.getByTestId('card-inspect').locator('.card-inspect-pop'), 'the inspected card', 8)

    await page.getByTestId('card-inspect').tap({ position: { x: 5, y: 5 } })
    await expect(page.getByTestId('card-inspect')).toBeHidden()
  })

  test('the scores are a tap away and the table note comes with them', async ({ app, page }) => {
    await seatMe(app)

    const compact = (await page.getByTestId('game-board').getAttribute('data-compact')) === 'true'
    const pad = page.getByTestId('scores-toggle')
    if (!compact) {
      // A tablet has room to leave the scoreboard open, and does.
      await expect(pad).toBeHidden()
      await expect(page.getByTestId('table-note')).toBeVisible()
      return
    }

    await expectTapTarget(pad, 'the scores pad', 32)
    await pad.tap()
    await expect(page.getByTestId('scores-sheet')).toBeVisible()
    await expect(page.getByTestId('score-row')).toHaveCount(4)
    await expect(page.getByTestId('table-note')).toBeVisible()

    await page.getByTestId('scores-sheet').tap({ position: { x: 5, y: 5 } })
    await expect(page.getByTestId('scores-sheet')).toBeHidden()
  })

  test('the pause menu opens without a keyboard', async ({ app, page }) => {
    await seatMe(app)

    const open = page.getByTestId('open-pause')
    await expectTapTarget(open, 'the pause button')
    await expectOnScreen(page, open, 'the pause button')
    await open.tap()

    await expect(page.getByTestId('escape-menu')).toBeVisible()
    await page.getByTestId('pause-resume').tap()
    await expect(page.getByTestId('escape-menu')).toBeHidden()
  })
})

test.describe('@mobile pointing at things without a cursor', () => {
  test('the first tap arms a target and the second commits it', async ({ app, page }) => {
    await app.hostStacked('devin', ['2', '3', '4', '5', FREEZE])
    await app.startAndWatch()

    const drawn = await app.table.playUntil(
      (snapshot) => snapshot.screen !== 'board' || !!snapshot.pending?.mine,
      { policy: alwaysHit, description: 'an action card of my own' },
    )
    expect(drawn.screen, 'the stacked deck did not deal me an action card').toBe('board')
    expect(drawn.pending?.cardDefId).toBe(FREEZE)

    const target = page.locator('[data-testid="seat"][data-targetable="true"]').first()
    await expect(target).toBeVisible()

    // One tap arms it. Nothing has been sent: the card is still waiting.
    await target.tap()
    await expect(target).toHaveAttribute('data-armed', 'true')
    await expect(page.getByTestId('pending-action')).toHaveAttribute('data-chosen', '')

    // ...and the second spends it. The card resolves as soon as the answer
    // lands, so what is asserted is that the question has gone rather than that
    // it is showing an answer — by the time it could be read it is off the
    // table, which is the point.
    await target.tap()
    await expect(page.getByTestId('pending-action')).toBeHidden()
  })

  test('the spin preview is shown before the call is sent', async ({ app, page }) => {
    await app.hostStacked('devin', ['2', '3', '4', '5', SPIN_TABLE])
    await app.startAndWatch()

    const drawn = await app.table.playUntil(
      (snapshot) => snapshot.screen !== 'board' || !!snapshot.pending?.mine,
      { policy: alwaysHit, description: 'a card of my own that asks a question' },
    )
    expect(drawn.pending?.cardDefId).toBe(SPIN_TABLE)

    const option = page.locator('[data-testid="choice-option"]').first()
    await expect(option).toBeVisible()
    await expectTapTarget(option, 'a choice')
    await expectOnScreen(page, page.getByTestId('choice-picker'), 'the choice picker', 2)

    // The preview is what says which way "left" means at a round table, and it
    // has to arrive with the finger rather than after the answer has gone.
    await option.dispatchEvent('pointerdown', { pointerType: 'touch', isPrimary: true })
    await expect(page.getByTestId('spin-preview')).toBeVisible()
    await expect(page.getByTestId('choice-picker')).toHaveAttribute('data-chosen', '')
  })
})

test.describe('@mobile the screens either side of a round', () => {
  test('the title card and the waiting room fit', async ({ app, page }) => {
    await expect(page.getByTestId('title-screen')).toBeVisible()
    await expectNoHorizontalScroll(page, 'the title card')

    await app.hostVersusBots('devin')
    await expectNoHorizontalScroll(page, 'the waiting room')
    await expectTapTarget(page.getByTestId('add-bot'), '"add a bot"')
    await expect(page.getByTestId('start-game')).toBeEnabled()

    await app.openSettings()
    await expectNoHorizontalScroll(page, 'the settings screen')
    await expectTapTarget(page.getByTestId('turn-timer-slider'), 'the turn timer slider')
    await app.closeSettings()
  })

  test('the rules book is readable', async ({ app, page }) => {
    await app.hostVersusBots('devin')
    await app.openRules()
    await expectNoHorizontalScroll(page, 'the rules book')
    await expectTapTarget(page.getByTestId('rules-back'), '"back"')
    await expectTapTarget(page.getByTestId('rules-next'), '"next page"')
    await app.closeRules()
  })

  test('the summary fits and the round can be taken on', async ({ app, page }) => {
    await seatMe(app)
    await app.table.playRound({ policy: alwaysHit })

    await app.waitForScreen(['summary', 'gameOver'])
    await expectNoHorizontalScroll(page, 'the round summary')
  })
})

test.describe('@mobile a phone that gets put away', () => {
  test('does not hold the table on the gate', async ({ app, page }) => {
    await seatMe(app)

    // The gate releases on timers, and a hidden tab's timers are clamped or
    // suspended outright — so the client lets go of the gate the moment nobody
    // is watching rather than leaving the server to time it out at eight
    // seconds a batch. Faked here, because Playwright cannot lock a phone.
    await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
      Object.defineProperty(document, 'hidden', { value: true, configurable: true })
      document.dispatchEvent(new Event('visibilitychange'))
    })

    const round = await app.table.snapshot()
    await page.getByTestId('hit').tap().catch(() => {})

    // The table keeps moving: the point is only that nothing wedges.
    await expect
      .poll(async () => (await app.table.snapshot()).screen, { timeout: 30_000 })
      .toMatch(/board|summary|gameOver/)
    expect(round.screen).toBe('board')
  })
})
