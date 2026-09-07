import { expect, test } from '../support/fixtures'

/**
 * The local testing mode: a panel that opens its own table, writes a situation
 * onto it and says which cards come next.
 *
 * It only exists against a server started with `LETITRIDE_TEST_HOOKS=1`, which
 * is what this suite runs — so the panel being here at all is part of what these
 * check, and `api.spec.ts` is what checks it is *not* there on a real one.
 */
test.describe('the testing panel', () => {
  test('deals its own table and says what is coming off the deck', async ({ app, page }) => {
    await page.getByTestId('dev-toggle').click()
    await expect(page.getByTestId('dev-panel')).toBeVisible()

    // The panel does the hosting, the bots and the start.
    await page.getByTestId('dev-quick-1').click()
    await app.table.waitForPlay()

    await page.getByTestId('dev-tab-cards').click()
    await expect(page.getByTestId('dev-deck-peek')).toBeVisible()

    // A freeze this deck may not even contain: the server mints one when it has
    // none, which is the point of being able to ask for it.
    await page.getByTestId('dev-stage-card').click()
    await page.getByTestId('dev-picker-actions').click()
    await page.locator('[data-testid="dev-pick-card"][data-card-name="freeze"]').click()
    await expect(page.locator('[data-testid="dev-staged-card"]')).toHaveCount(1)

    await page.getByTestId('dev-stack-apply').click()

    await expect(page.locator('[data-testid="dev-deck-card"]').first()).toHaveAttribute('data-card-name', 'freeze')
    await expect(page.locator('[data-testid="dev-staged-card"]')).toHaveCount(0)
  })

  test('a hand written onto a seat is the hand that seat is holding', async ({ app, page }) => {
    await page.getByTestId('dev-toggle').click()
    await page.getByTestId('dev-quick-1').click()
    await app.table.waitForPlay()

    await page.getByTestId('dev-tab-players').click()
    const seat = page.locator('[data-testid="dev-player"]').first()
    const playerId = await seat.getAttribute('data-player-id')
    const cards = seat.locator('[data-testid="dev-hand-card"]')
    // Whatever the opening deal left there; the panel adds to a hand rather
    // than emptying it first.
    const dealt = await cards.count()

    await seat.getByTestId('dev-hand-add').click()
    await seat.locator('[data-testid="dev-pick-card"]').first().click()
    await seat.getByTestId('dev-hand-add').click()
    await seat.locator('[data-testid="dev-pick-card"]').nth(1).click()

    // Two more in the panel, and the same two on the table.
    await expect(cards).toHaveCount(dealt + 2)
    await expect
      .poll(async () => {
        const snapshot = await app.table.snapshot()
        return snapshot.seats.find((each) => each.id === playerId)?.handSize
      }, { message: 'the seat to be holding what was written onto it' })
      .toBe(dealt + 2)
  })

  test('the round plays out from the situation it was given', async ({ app, page }) => {
    await page.getByTestId('dev-toggle').click()
    await page.getByTestId('dev-quick-1').click()
    await app.table.waitForPlay()

    await page.getByTestId('dev-tab-table').click()
    // An opening card can be an action card, and the table is then stopped on
    // the pick rather than on anybody's turn.
    await page.getByTestId('dev-clear-prompt').click()
    // A card the local player is already holding, put on top of the deck.
    await page.getByTestId('dev-scenario-bust-next').click()

    await expect
      .poll(async () => {
        const snapshot = await app.table.snapshot()
        return snapshot.myTurn && snapshot.buttonsVisible
      }, { message: 'the turn to come back to the seat the setup handed it to' })
      .toBe(true)
    await app.table.hit()

    await expect
      .poll(async () => (await app.table.me())?.status, {
        message: 'the drawn duplicate to bust the hand it was written for',
        timeout: 30_000,
      })
      .toBe('bust')
  })

  test('an antimatter bust is paid for rather than written off', async ({ app, page }) => {
    test.slow()

    await page.getByTestId('dev-toggle').click()
    await page.getByTestId('dev-quick-1').click()
    await app.table.waitForPlay()

    await page.getByTestId('dev-tab-table').click()
    await page.getByTestId('dev-clear-prompt').click()

    const opening = await app.table.snapshot()
    const me = opening.seats.find((seat) => seat.isSelf)!
    const bot = opening.seats.find((seat) => !seat.isSelf)!

    await page.getByTestId('dev-tab-players').click()
    const mine = page.locator(`[data-testid="dev-player"][data-player-id="${me.id}"]`)

    // One card, and the biggest face this deck prints. Said outright rather
    // than played towards: this deck deals a nought, and a hand that is worth
    // nothing is worth nothing turned over too — a round that scores zero
    // either way would pass whatever the server did with it.
    const held = mine.locator('[data-testid="dev-hand-card"]')
    for (let count = await held.count(); count > 0; count -= 1) {
      await held.first().click()
      await expect(held).toHaveCount(count - 1)
    }
    await mine.getByTestId('dev-hand-add').click()
    const biggest = mine.locator('[data-testid="dev-pick-card"]').last()
    const face = (await biggest.getAttribute('data-card-name'))!
    await biggest.click()
    await expect(held).toHaveCount(1)

    // The card that says you may not stop, in front of me...
    await mine.getByTestId('dev-passives-add').click()
    await mine.getByTestId('dev-picker-passives').click()
    await mine.locator('[data-testid="dev-pick-card"][data-card-name="antimatter"]').click()
    await expect(mine.locator('[data-testid="dev-passives-card"][data-card-name="antimatter"]')).toHaveCount(1)

    // ...everybody else already out, so my bust is the last thing that happens
    // in the round. A bot playing on can reach a busted seat — a steal or a
    // swap takes the modifier row too — and this spec is about what an
    // antimatter is worth to the seat that had it, not about who ends up with it.
    await page.locator(`[data-testid="dev-player"][data-player-id="${bot.id}"]`)
      .getByTestId('dev-status-stayed').click()

    // ...and the second one on top of the deck. Which used to be the way *out*
    // of it: a bust wrote the round off and the hole with it, so the harshest
    // card in the game paid nothing.
    await page.getByTestId('dev-tab-cards').click()
    await page.getByTestId('dev-stage-card').click()
    await page.locator(`[data-testid="dev-stack-picker"] [data-testid="dev-pick-card"][data-card-name="${face}"]`).click()
    await page.getByTestId('dev-stack-apply').click()
    await expect(page.locator('[data-testid="dev-deck-card"]').first()).toHaveAttribute('data-card-name', face)

    await page.getByTestId('dev-tab-table').click()
    await page.getByTestId('dev-turn-to').filter({ hasText: me.name }).click()
    await page.getByTestId('dev-toggle').click()

    await expect
      .poll(async () => {
        const snapshot = await app.table.snapshot()
        return snapshot.myTurn && snapshot.buttonsVisible
      }, { message: 'the turn to come back to the seat the setup handed it to' })
      .toBe(true)
    await app.table.hit()

    const summary = await app.table.playRound({ policy: () => 'hit' })
    expect(summary.screen).toBe('summary')

    const row = page.locator(`[data-testid="summary-row"][data-player-id="${me.id}"]`)
    await expect(row).toHaveAttribute('data-busted', 'true')
    const points = Number(await row.getAttribute('data-points'))
    expect(points, 'the bust cost them nothing').toBeLessThan(0)

    // And the felt says so. Every other busted seat is shown what the hand
    // added up to, struck through — "this is what it would have been worth".
    // There is no would-have-been here; the number beside the cards is the
    // number coming off the scoreboard.
    await expect(row).toContainText(`= ${points}`)
  })

  test('a card played on a discordia takes points off the seat holding it', async ({ app, page }) => {
    await page.getByTestId('dev-toggle').click()
    await page.getByTestId('dev-quick-1').click()
    await app.table.waitForPlay()

    // An opening card can be an action card, and the table is then stopped on a
    // pick rather than on anybody's turn.
    await page.getByTestId('dev-tab-table').click()
    await page.getByTestId('dev-clear-prompt').click()

    const opening = await app.table.snapshot()
    const me = opening.seats.find((seat) => seat.isSelf)!
    const bot = opening.seats.find((seat) => !seat.isSelf)!

    // The bot is holding a discordia...
    await page.getByTestId('dev-tab-players').click()
    const botPanel = page.locator(`[data-testid="dev-player"][data-player-id="${bot.id}"]`)
    await botPanel.getByTestId('dev-passives-add').click()
    await botPanel.getByTestId('dev-picker-passives').click()
    await botPanel.locator('[data-testid="dev-pick-card"][data-card-name="discordia"]').click()
    await expect(botPanel.locator('[data-testid="dev-passives-card"][data-card-name="discordia"]')).toHaveCount(1)

    // ...and a freeze is next off the deck, for me.
    await page.getByTestId('dev-tab-cards').click()
    await page.getByTestId('dev-stage-card').click()
    await page.getByTestId('dev-picker-actions').click()
    await page.locator('[data-testid="dev-stack-picker"] [data-testid="dev-pick-card"][data-card-name="freeze"]').click()
    await page.getByTestId('dev-stack-apply').click()

    await page.getByTestId('dev-tab-table').click()
    await page.getByTestId('dev-turn-to').filter({ hasText: me.name }).click()
    await page.getByTestId('dev-toggle').click()

    // Draw it, and point it at the seat that will regret being interesting.
    // Drawing rather than banking: the default policy goes out once it is
    // holding two, and a hand that never draws never reaches the stacked card.
    const drawn = await app.table.playUntil(
      (snapshot) => snapshot.pending?.mine === true,
      { policy: () => 'hit', description: 'the stacked freeze to come off the deck' },
    )
    expect(drawn.pending?.cardDefId, 'the stack did not deal me the freeze').toBe('freeze')
    await app.table.pickTarget(bot.id)

    // Go out rather than play on: a busted round says why it scored nothing
    // instead of what it collected.
    const summary = await app.table.playRound({ policy: () => 'stay' })
    expect(summary.screen).toBe('summary')

    const mine = page.locator(`[data-testid="summary-row"][data-player-id="${me.id}"]`)
    await expect(mine.getByTestId('summary-adjustment')).toHaveAttribute('data-adjustment', '10')
  })

  test('the scoreboard makes something of a player closing on the target', async ({ app, page }) => {
    await page.getByTestId('dev-toggle').click()
    await page.getByTestId('dev-quick-1').click()
    await app.table.waitForPlay()

    await page.getByTestId('dev-tab-table').click()
    await page.getByTestId('dev-clear-prompt').click()

    const opening = await app.table.snapshot()
    const me = opening.seats.find((seat) => seat.isSelf)!
    // What a dev table plays to — see `defaultGameConfig`.
    const target = 200

    // Nobody is near it yet, so nothing on the board is shouting.
    const mine = page.locator(`[data-testid="score-row"][data-player-id="${me.id}"]`)
    await expect(mine).toHaveAttribute('data-close', 'false')
    await expect(mine).toHaveAttribute('data-brink', 'false')

    await page.getByTestId('dev-tab-players').click()
    const seat = page.locator(`[data-testid="dev-player"][data-player-id="${me.id}"]`)
    const score = seat.getByTestId('dev-score')

    // Most of the way there: the line warms up.
    await score.fill(String(Math.round(target * 0.75)))
    await score.press('Enter')
    await expect(mine).toHaveAttribute('data-close', 'true')
    await expect(mine).toHaveAttribute('data-brink', 'false')

    // ...and on the brink it says so out loud.
    await score.fill(String(target - 6))
    await score.press('Enter')
    await expect(mine).toHaveAttribute('data-brink', 'true')
    await expect(mine.getByTestId('match-point')).toBeVisible()
  })
})
