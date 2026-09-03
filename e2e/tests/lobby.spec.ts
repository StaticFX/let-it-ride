import { test, expect } from '../support/fixtures'

test.describe('the front door', () => {
  test('will not let you sit down without a name', async ({ app, page }) => {
    await expect(page.getByTestId('host-game')).toBeDisabled()
    await expect(page.getByTestId('join-game')).toBeDisabled()
    await expect(page.getByTestId('play-vs-bots')).toBeDisabled()

    await app.enterName('devin')

    await expect(page.getByTestId('host-game')).toBeEnabled()
    await expect(page.getByTestId('join-game')).toBeEnabled()
    await expect(page.getByTestId('play-vs-bots')).toBeEnabled()
  })

  test('remembers your name for next time', async ({ app, page }) => {
    await app.enterName('devin')
    await page.reload()
    await expect(page.getByTestId('name-input')).toHaveValue('devin')
  })

  test('caps a name at sixteen characters', async ({ app, page }) => {
    await app.enterName('an-extremely-long-name-indeed')
    await expect(page.getByTestId('name-input')).toHaveValue('an-extremely-lon')
  })

  test('hosting opens a table with a shareable code', async ({ app, page }) => {
    const code = await app.host('devin')

    expect(code).toMatch(/^[A-Z0-9]{4}$/)
    await expect(page.getByTestId('waiting-room')).toHaveAttribute('data-host', 'true')
    await expect(app.playerRow('devin')).toBeVisible()
    await expect(app.players).toHaveCount(1)
    // Two players minimum, so a lone host cannot start.
    await expect(page.getByTestId('start-game')).toBeDisabled()
    await expect(page.getByTestId('start-game')).toHaveText(/need 1 more/)
  })

  test('"play vs bots" seats three of them and is ready to go', async ({ app, page }) => {
    await app.hostVersusBots('devin')
    await expect(app.players).toHaveCount(4)
    await expect(page.getByTestId('start-game')).toBeEnabled()
    await expect(page.getByTestId('start-game')).toHaveText(/let it ride/)
  })

  test('the host can add bots up to the table limit', async ({ app, page }) => {
    await app.host('devin')
    await app.addBotsUntil(5)

    await expect(app.players).toHaveCount(5)
    // Five seats is the whole table; there is nowhere left to add one.
    await expect(page.getByTestId('add-bot')).toBeHidden()
  })

  test('the host can kick a bot back out', async ({ app }) => {
    await app.hostVersusBots('devin')
    const name = await app.players.nth(1).getAttribute('data-player-name')
    await app.kick(name!)
    await expect(app.players).toHaveCount(3)
    await expect(app.playerRow(name!)).toHaveCount(0)
  })

  test('the host cannot kick themselves', async ({ app }) => {
    await app.hostVersusBots('devin')
    await expect(app.playerRow('devin').getByTestId('kick-player')).toHaveCount(0)
  })

  test('leaving puts you back at the title card', async ({ app, page }) => {
    await app.host('devin')
    await app.leaveRoom()
    await expect(page.getByTestId('title-screen')).toBeVisible()
    await expect(page.getByTestId('name-input')).toHaveValue('devin')
  })
})

test.describe('joining', () => {
  test('a code that is not a room says so', async ({ app, page, consoleGuard }) => {
    consoleGuard.allow(/\/api\/rooms\/ZZZZ/)
    await app.join('devin', 'ZZZZ')
    await expect(page.getByTestId('lobby-error')).toBeVisible()
    await expect(page.getByTestId('join-screen')).toBeVisible()
  })

  test('going back from the join screen keeps your name', async ({ app, page }) => {
    await app.enterName('devin')
    await page.getByTestId('join-game').click()
    await page.getByTestId('join-back').click()
    await expect(page.getByTestId('name-input')).toHaveValue('devin')
  })

  test('the code is not case sensitive', async ({ app, api, page }) => {
    const room = await api.createRoom('someone')
    await app.join('devin', room.roomCode.toLowerCase())
    await expect(page.getByTestId('waiting-room')).toBeVisible()
    expect(await app.roomCode()).toBe(room.roomCode)
  })
})

test.describe('table settings', () => {
  test('the host picks the deck and the table shows it', async ({ app, page }) => {
    await app.host('devin')
    await app.openSettings()
    await app.chooseDeck('chaos')
    await app.closeSettings()

    await expect(page.getByTestId('table-deck-name')).toHaveText('Chaos')
  })

  test('house rules turn on and are listed on the table', async ({ app, page }) => {
    await app.host('devin')
    await app.openSettings()
    await app.toggleRule('blackjacking')
    await app.toggleRule('doubleDraw')
    await app.closeSettings()

    await expect(page.getByTestId('table-house-rules')).toContainText('blackjacking')
    await expect(page.getByTestId('table-house-rules')).toContainText('double draw')
  })

  test('the win condition and the clock can both be changed', async ({ app, page }) => {
    await app.host('devin')
    await app.configure({ rounds: 3, turnSeconds: 45 })

    await expect(page.getByTestId('table-goal')).toHaveText('best of 3 rounds')
    await expect(page.getByTestId('table-timer')).toHaveText('45s')

    await app.configure({ targetScore: 100 })
    await expect(page.getByTestId('table-goal')).toHaveText('first to 100')
  })

  test('settings survive a reconnect because the server owns them', async ({ app, api, page }) => {
    // Set the table up, then walk out and back in through the same door.
    const room = await api.createRoom('devin')
    await app.join('devin', room.roomCode)
    await app.configure({ deck: 'pure', rounds: 7 })
    await app.leaveRoom()

    await app.join('devin', room.roomCode)
    await expect(page.getByTestId('table-deck-name')).toHaveText('Pure')
    await expect(page.getByTestId('table-goal')).toHaveText('best of 7 rounds')
  })

  test('the deck listing shows every card in the chosen deck', async ({ app, page, api }) => {
    const catalog = await api.catalog()
    const chaos = catalog.decks.find((deck) => deck.id === 'chaos')!

    await app.host('devin')
    await app.openSettings()
    await app.chooseDeck('chaos')
    await app.closeSettings()

    await page.getByTestId('toggle-deck-cards').click()
    // One tile per distinct face, each labelled with how many are in the deck.
    const tiles = page.locator('[data-testid="waiting-room"] .sketch-box-light > div')
    await expect(tiles).toHaveCount(chaos.contents.length)
  })
})

test.describe('the rules book', () => {
  test('opens from the title card and pages through', async ({ app, page }) => {
    await app.openRules()
    await expect(page.getByTestId('rules-page')).toHaveAttribute('data-page', '1')

    for (const expected of ['2', '3', '4']) {
      await page.getByTestId('rules-next').click()
      await expect(page.getByTestId('rules-page')).toHaveAttribute('data-page', expected)
    }

    // The last page is the last page — there is nothing to page on to.
    await expect(page.getByTestId('rules-next')).toBeDisabled()

    await page.getByTestId('rules-prev').click()
    await expect(page.getByTestId('rules-page')).toHaveAttribute('data-page', '3')

    await app.closeRules()
    await expect(page.getByTestId('title-screen')).toBeVisible()
  })

  test('explains the scoring the engine actually uses', async ({ app, page, api }) => {
    const catalog = await api.catalog()
    await app.openRules()
    await page.getByTestId('rules-next').click()

    await expect(page.getByTestId('rules-page')).toContainText(String(catalog.flip7Target))
    await expect(page.getByTestId('rules-page')).toContainText(String(catalog.flip7Bonus))
  })
})

test.describe('sound', () => {
  test('mutes, stays muted across a reload, and unmutes', async ({ app, page }) => {
    const toggle = page.getByRole('button', { name: 'turn sound off' })
    await expect(toggle).toBeVisible()

    await toggle.click()
    await expect(page.getByRole('button', { name: 'turn sound on' })).toBeVisible()
    expect(await page.evaluate(() => localStorage.getItem('let-it-ride:muted'))).toBe('1')

    await page.reload()
    await expect(page.getByRole('button', { name: 'turn sound on' })).toBeVisible()

    await page.getByRole('button', { name: 'turn sound on' }).click()
    await expect(page.getByRole('button', { name: 'turn sound off' })).toBeVisible()
    void app
  })
})

test.describe('a server that is not there', () => {
  test('says so rather than hanging on a blank page', async ({ page, context, consoleGuard }) => {
    consoleGuard.allow(/\/api\/catalog/)
    await context.route('**/api/catalog', (route) => route.abort())
    await page.goto('/')
    await expect(page.getByTestId('catalog-error')).toBeVisible()
    await expect(page.getByTestId('catalog-error')).toContainText('is the server up?')
  })
})

test.describe('@mobile the title card on a phone', () => {
  test('fits, and the whole flow to a table works', async ({ app, page }) => {
    await expect(page.getByTestId('title-screen')).toBeVisible()

    // Nothing may push the page sideways on a narrow window.
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, 'the title card scrolls horizontally on a phone').toBeLessThanOrEqual(1)

    await app.hostVersusBots('devin')
    await expect(page.getByTestId('start-game')).toBeEnabled()
  })
})
