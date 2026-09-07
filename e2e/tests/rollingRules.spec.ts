import { test, expect } from '../support/fixtures'
import { Table } from '../support/table'

/**
 * Rolling rules, driven through the DOM like a player.
 *
 * The mode's whole claim is that you are carrying something nobody else can
 * see, so most of what is worth checking here is *negative*: what the other
 * browser does not have, what the client is not allowed to decide, and what
 * happens to a classic table when the mode exists but is not on.
 */
test.describe('rolling rules', () => {
  test('the mode is chosen in the lobby and the table says it is on', async ({ app, page }) => {
    await app.hostRollingRules('devin')
    await expect(page.getByTestId('table-mode')).toHaveText('rolling rules')
    // ...and it brings the house rule it always underlays, shown on rather
    // than applied quietly.
    await app.openSettings()
    await expect(page.getByTestId('rule-extreme')).toHaveAttribute('data-forced', 'true')
    await expect(page.getByTestId('rule-extreme')).toHaveAttribute('data-active', 'true')
    await app.closeSettings()

    await app.start()
    await expect(app.table.board).toHaveAttribute('data-mode', 'rollingRules')
  })

  test('a gambler card off the deck goes into a hand nobody else can see', async ({ app, page, openPlayer }) => {
    // Two humans, so there is somebody whose view can be checked. Entry 0 is
    // the host's opening card; a gambler card there goes to their hidden hand
    // and entry 1 is dealt to them instead.
    const code = await app.hostRollingRules('devin', ['shuffle', '4', '9'], { bots: 0 })
    const guest = await openPlayer()
    await guest.app.join('robin', code)
    await app.start()
    await app.table.waitForPlay()

    const mine = page.locator('[data-testid="gambler-card"]')
    await expect(mine).toHaveCount(1)
    await expect(mine.first()).toHaveAttribute('data-card-def-id', 'shuffle')

    // The other browser is told how many, and nothing else. Not "the faces are
    // absent from a list somewhere" — absent from the page.
    const theirView = guest.app.table.seat('devin')
    await expect(theirView).toHaveAttribute('data-gambler-count', '1')
    await expect(guest.page.locator('[data-testid="gambler-card"]')).toHaveCount(0)
    await expect(guest.page.locator('[data-card-def-id="shuffle"]')).toHaveCount(0)
    // ...but they can see that something is there.
    await expect(guest.page.locator('[data-testid="gambler-backs"]')).toHaveCount(1)
  })

  test('a card you may not play now cannot be clicked, only read', async ({ app, page }) => {
    // "redirect" is an on-turn card, so it is dead the moment the turn moves on.
    await app.hostRollingRules('devin', ['redirect', '4', '9', '11'], { bots: 1 })
    await app.start()
    await app.table.waitForPlay()

    const card = page.locator('[data-testid="gambler-card"]').first()
    await expect(card).toHaveAttribute('data-card-def-id', 'redirect')
    await expect(card).toHaveAttribute('data-playable', 'true')

    // Go out, and the window shuts — which is the server's answer, not this
    // client's, and is the whole reason the attribute exists.
    await app.table.stay()
    await expect(card).toHaveAttribute('data-playable', 'false')

    // It is still a card you can read. Clicking an unplayable one inspects it.
    await card.click()
    await expect(page.getByTestId('card-inspect')).toBeVisible()
  })

  test('playing one turns it face up for the whole table', async ({ app, page, openPlayer }) => {
    const code = await app.hostRollingRules('devin', ['shuffle', '4', '9'], { bots: 0 })
    const guest = await openPlayer()
    await guest.app.join('robin', code)
    await app.start()
    await app.table.waitForPlay()

    const cardId = await page
      .locator('[data-testid="gambler-card"]')
      .first()
      .getAttribute('data-card-id')
    await app.table.playGamblerCard(cardId!)

    // The other browser — which never saw the face — sees it now.
    await expect(guest.page.getByTestId('gambler-reveal')).toHaveAttribute('data-card-def-id', 'shuffle')
    // ...and a card nobody has seen is held long enough to be read.
    await expect(guest.page.getByTestId('gambler-reveal')).toHaveAttribute('data-first-seen', 'true')

    await expect(page.locator('[data-testid="gambler-card"]')).toHaveCount(0)
    await expect(guest.app.table.seat('devin')).toHaveAttribute('data-gambler-count', '0')
  })

  test('the deck is still the deck while gambler cards are moving', async ({ app, api }) => {
    const catalog = await api.catalog()
    const deck = catalog.decks.find((d) => d.id === 'rollingrules')!

    await app.hostRollingRules('devin', [], { bots: 2 })
    await app.start()
    await app.table.waitForPlay()

    // A card going into a hidden hand, sitting there across a turn, coming back
    // out face up and landing in the discard pile is the same card the whole
    // way. Counted at every step, on the board only — the piles read zero
    // anywhere else, which would look like cards vanishing.
    const counted: number[] = []
    await app.table.playUntil(
      (snapshot) => {
        if (snapshot.screen === 'board') counted.push(Table.cardsAccountedFor(snapshot))
        return snapshot.screen === 'summary' || snapshot.screen === 'gameOver'
      },
      {
        // Spending them is the point: a card that never leaves a hand never
        // exercises the half of this that could go wrong.
        respond: (s) => s.gamblers.find((g) => g.playable)?.id ?? null,
        description: 'a whole round of rolling rules',
      },
    )

    expect(counted.length).toBeGreaterThan(3)
    for (const total of counted) {
      expect(total, 'the table has lost track of a card').toBe(deck.cardCount)
    }
  })

  test('the points are readable the whole way through a round', async ({ app }) => {
    await app.hostRollingRules('devin', [], { bots: 2 })
    await app.start()
    await app.table.waitForPlay()

    // Spec line 14: "the current points are always displayed ingame, somewhere
    // nicely readable". A purse that is only there some of the time is a purse
    // nobody trusts.
    await app.table.playUntil(
      (s) => {
        expect(Number.isFinite(s.purse)).toBe(true)
        return s.screen !== 'board'
      },
      { description: 'the round to end while the purse stays readable' },
    )
  })

  test('the table stops and asks, and letting it stand lets the card through', async ({ app, page, openPlayer }) => {
    // devin draws a shuffle, robin a nahhh — so playing the shuffle asks robin
    // whether they want to stop it.
    const code = await app.hostRollingRules('devin', ['shuffle', '4', 'nahhh', '9'], { bots: 0 })
    const guest = await openPlayer()
    await guest.app.join('robin', code)
    await app.start()
    await app.table.waitForPlay()

    const cardId = await page
      .locator('[data-testid="gambler-card"]')
      .first()
      .getAttribute('data-card-id')
    await app.table.playGamblerCard(cardId!)

    // robin is asked; devin, who played it, is not.
    await expect(guest.page.getByTestId('pass')).toBeVisible()
    await expect(page.getByTestId('pass')).toHaveCount(0)
    await expect(guest.page.getByTestId('response-stack')).toHaveAttribute('data-depth', '1')

    await guest.app.table.passResponse()
    await expect(guest.page.getByTestId('response-stack')).toHaveCount(0)
    await expect(page.getByTestId('response-stack')).toHaveCount(0)
  })

  test('a counter takes the card off the stack, and can itself be countered', async ({ app, page, openPlayer }) => {
    // devin: shuffle then a nullify. robin: a nahhh. devin plays the shuffle,
    // robin stops it, and devin stops the stopping — three cards deep.
    const code = await app.hostRollingRules('devin', ['shuffle', 'nullify', '4', 'nahhh', '9'], { bots: 0 })
    const guest = await openPlayer()
    await guest.app.join('robin', code)
    await app.start()
    await app.table.waitForPlay()

    const shuffle = page.locator('[data-testid="gambler-card"][data-card-def-id="shuffle"]')
    await app.table.playGamblerCard((await shuffle.getAttribute('data-card-id'))!)

    // robin answers with the nahhh...
    await expect(guest.page.getByTestId('pass')).toBeVisible()
    const nahhh = guest.page.locator('[data-testid="gambler-card"][data-card-def-id="nahhh"]')
    await expect(nahhh).toHaveAttribute('data-playable', 'true')
    await guest.app.table.playGamblerCard((await nahhh.getAttribute('data-card-id'))!)

    // ...and now devin is asked about *that*, because a counter is aimed at
    // whoever played the card it stops. The pile is two deep.
    await expect(page.getByTestId('response-stack')).toHaveAttribute('data-depth', '2')
    const nullify = page.locator('[data-testid="gambler-card"][data-card-def-id="nullify"]')
    await expect(nullify).toHaveAttribute('data-playable', 'true')
    await app.table.playGamblerCard((await nullify.getAttribute('data-card-id'))!)

    // Everything unwinds: the nahhh was stopped, so the shuffle went through.
    await expect(page.getByTestId('response-stack')).toHaveCount(0)
    await expect(page.locator('[data-testid="gambler-card"]')).toHaveCount(0)
  })

  test('nobody is told who can answer', async ({ app, page, openPlayer }) => {
    // The leak test, at the prompt. Whoever gets asked must not depend on who
    // is holding the answer, or "waiting on one more" names the holder.
    const code = await app.hostRollingRules('devin', ['shuffle', '4', 'nahhh', '9'], { bots: 0 })
    const guest = await openPlayer()
    await guest.app.join('robin', code)
    await app.start()
    await app.table.waitForPlay()

    const cardId = await page
      .locator('[data-testid="gambler-card"]')
      .first()
      .getAttribute('data-card-id')
    await app.table.playGamblerCard(cardId!)
    await expect(guest.page.getByTestId('pass')).toBeVisible()

    // robin is asked, and their own page says whether they can actually do
    // anything about it — but devin's page never learns either way.
    await expect(guest.page.getByTestId('pass')).toContainText('let it stand')
    await expect(page.locator('[data-card-def-id="nahhh"]')).toHaveCount(0)
  })

  test('an unanswered window closes on the clock', async ({ app, page }) => {
    // The worst failure the mode has is a window that hangs a table. Nobody
    // answers this one: the bot must let it stand on its own, and the round
    // must carry on without anybody clicking anything.
    await app.hostRollingRules('devin', ['shuffle', '4', 'nahhh', '9'], { bots: 1 })
    await app.start()
    await app.table.waitForPlay()

    const cardId = await page
      .locator('[data-testid="gambler-card"]')
      .first()
      .getAttribute('data-card-id')
    await app.table.playGamblerCard(cardId!)

    await expect(page.getByTestId('response-stack')).toHaveCount(0, { timeout: 30_000 })
    const after = await app.table.snapshot()
    expect(after.screen).toBe('board')
  })

  test('the round after this one is on the other side of a shop', async ({ app, page }) => {
    await app.hostRollingRules('devin', [], { bots: 1 })
    await app.start()
    await app.table.playRound({ policy: () => 'stay' })
    await app.nextRound()

    // The scoreboard first, then the shop — not instead of it.
    const shop = await app.table.waitForInterlude()
    expect(shop.screen).toBe('interlude')
    await expect(page.getByTestId('interlude-clock')).toBeVisible()
    expect(shop.purse).toBeGreaterThanOrEqual(0)

    // Saying you are done is what shuts it, and the round is on the far side.
    await app.table.readyUp()
    await app.waitForScreen(['board'])
  })

  test('the purse is the score, and buying moves it', async ({ app, page }) => {
    await app.hostRollingRules('devin', [], { bots: 1 })
    // A target far enough off that being funded does not simply win the game —
    // which is what happens when the money and the score are the same number,
    // and is the very thing this test is about.
    await app.configure({ targetScore: 1000 })
    await app.start()
    await app.table.waitForPlay()

    // Funded up front rather than by playing well: a round that happened to pay
    // badly would skip the test instead of proving it.
    await page.getByTestId('dev-toggle').click()
    await page.getByTestId('dev-tab-players').click()
    const meId = (await app.table.snapshot()).seats.find((seat) => seat.isSelf)!.id
    const mine = page.locator(`[data-testid="dev-player"][data-player-id="${meId}"]`)
    await mine.getByTestId('dev-score').fill('300')
    await mine.getByTestId('dev-score').press('Enter')
    await page.getByTestId('dev-toggle').click()

    await app.table.playRound({ policy: () => 'stay' })
    await app.nextRound()
    const shop = await app.table.waitForInterlude()

    const affordable = shop.offers.filter((offer) => offer.affordable && !offer.sold)
    expect(affordable.length, 'three hundred points should buy something').toBeGreaterThan(0)

    const cheapest = affordable.sort((a, b) => a.price - b.price)[0]
    const before = shop.purse
    await app.table.buyOffer(cheapest.id)

    // The money *is* the score. Spending it moves the number you win with.
    await expect(page.getByTestId('interlude')).toHaveAttribute(
      'data-purse',
      String(before - cheapest.price),
    )
    await expect(
      page.locator(`[data-testid="shop-offer"][data-offer-id="${cheapest.id}"]`),
    ).toHaveAttribute('data-sold', 'true')
  })

  test('the shelf is yours alone', async ({ app, openPlayer }) => {
    const code = await app.hostRollingRules('devin', [], { bots: 0 })
    const guest = await openPlayer()
    await guest.app.join('robin', code)
    await app.start()
    // Both browsers play. A second human who never acts sits out the whole turn
    // clock, which is two real minutes of a spec doing nothing.
    await Promise.all([
      app.table.playRound({ policy: () => 'stay' }),
      guest.app.table.playRound({ policy: () => 'stay' }),
    ])
    await app.nextRound()

    const mine = await app.table.waitForInterlude()
    const theirs = await guest.app.table.waitForInterlude()
    expect(mine.offers.length).toBeGreaterThan(0)

    // Two shelves may hold the same card, but neither browser has the other's
    // offer *ids* anywhere in it — private stock is private on the wire.
    for (const offer of theirs.offers) {
      await expect(
        app.page.locator(`[data-testid="shop-offer"][data-offer-id="${offer.id}"]`),
      ).toHaveCount(mine.offers.some((o) => o.id === offer.id) ? 1 : 0)
    }
  })

  test('one jackpot, sealed bids, turned over at once', async ({ app, page, openPlayer }) => {
    const code = await app.hostRollingRules('devin', [], { bots: 0 })
    await app.configure({ targetScore: 1000 })
    const guest = await openPlayer()
    await guest.app.join('robin', code)
    await app.start()
    await app.table.waitForPlay()

    // Fund both seats so there is an auction worth having.
    await page.getByTestId('dev-toggle').click()
    await page.getByTestId('dev-tab-players').click()
    for (const seat of (await app.table.snapshot()).seats) {
      const row = page.locator(`[data-testid="dev-player"][data-player-id="${seat.id}"]`)
      await row.getByTestId('dev-score').fill('400')
      await row.getByTestId('dev-score').press('Enter')
    }
    await page.getByTestId('dev-toggle').click()

    await Promise.all([
      app.table.playRound({ policy: () => 'stay' }),
      guest.app.table.playRound({ policy: () => 'stay' }),
    ])
    await app.nextRound()

    const mine = await app.table.waitForInterlude()
    await guest.app.table.waitForInterlude()
    expect(mine.auction, 'this build has jackpots, so something is on the block').not.toBeNull()

    // devin bids high, robin low. Neither page shows the other's number.
    await app.table.placeBid(250)
    await guest.app.table.placeBid(80)
    await expect(guest.page.getByTestId('auction')).toHaveAttribute('data-my-bid', '')
    await app.table.readyUp()
    await expect(page.getByTestId('auction')).toHaveAttribute('data-my-bid', '250')
    // robin's page still knows nothing of devin's bid.
    await expect(guest.page.getByTestId('auction')).toHaveAttribute('data-my-bid', '')

    await guest.app.table.readyUp()

    // Both bids, turned over together — and the higher one takes it.
    await expect(page.getByTestId('showdown')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('showdown')).toContainText('sold!')
    await expect(page.getByTestId('showdown')).toContainText('250')
    await expect(guest.page.getByTestId('showdown')).toContainText('80')

    // ...and the winner pays for it, out of the same score they win with.
    await app.waitForScreen(['board'])
    const after = await app.table.snapshot()
    const devin = after.seats.find((seat) => seat.isSelf)!
    expect(devin.gamblerCount).toBeGreaterThan(0)
  })

  test('a classic table never sees any of it', async ({ app, page }) => {
    // The test that stops the mode leaking into every other spec.
    await app.host('devin')
    await app.addBotsUntil(3)
    await app.configure({ deck: 'friendly', rounds: 1, turnSeconds: 120 })
    await app.start()
    await app.table.waitForPlay()

    await expect(app.table.board).toHaveAttribute('data-mode', 'classic')
    await expect(page.locator('[data-testid="gambler-hand"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="gambler-backs"]')).toHaveCount(0)

    const snapshot = await app.table.playRound()
    expect(snapshot.seats.every((seat) => seat.gamblerCount === 0)).toBe(true)

    // ...and the next round deals straight away, with no window in between.
    await app.nextRound()
    expect(await app.waitForScreen(['board', 'gameOver'])).not.toBe('interlude')
  })
})
