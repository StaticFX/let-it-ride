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
    await app.table.playUntil((snapshot) => snapshot.pending?.mine === true, {
      description: 'the stacked freeze to come off the deck',
    })
    await app.table.pickTarget(bot.id)

    // Go out rather than play on: a busted round says why it scored nothing
    // instead of what it collected.
    const summary = await app.table.playRound({ policy: () => 'stay' })
    expect(summary.screen).toBe('summary')

    const mine = page.locator(`[data-testid="summary-row"][data-player-id="${me.id}"]`)
    await expect(mine.getByTestId('summary-adjustment')).toHaveAttribute('data-adjustment', '10')
  })
})
