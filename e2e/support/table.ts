import { expect, type Locator, type Page } from '@playwright/test'

/** Everything a spec needs to know about the screen, read in one pass. */
export interface Seat {
  id: string
  name: string
  status: 'active' | 'stayed' | 'bust'
  handValue: number
  handSize: number
  passiveCount: number
  /**
   * How many gambler cards this seat is carrying. Public on every seat — the
   * slot cap is public information — while the faces are not: only the local
   * player's own tray is ever rendered face up. Zero in the classic game.
   */
  gamblerCount: number
  /** How many it could carry. Five, or more behind a "pouch". */
  gamblerSlots: number
  targetable: boolean
  isBot: boolean
  isSelf: boolean
}

export type Screen =
  | 'loading'
  | 'catalogError'
  | 'title'
  | 'join'
  | 'connecting'
  | 'waiting'
  | 'settings'
  | 'rules'
  | 'board'
  | 'interlude'
  | 'summary'
  | 'gameOver'
  | 'unknown'

/** One gambler card in the local player's tray. */
export interface GamblerCard {
  id: string
  defId: string
  rarity: string
  window: string
  /** Whether the server says it may be played right now. The client never decides this. */
  playable: boolean
}

/** One card on the shop's shelf, or the lot under the hammer. */
export interface ShopOffer {
  id: string
  price: number
  affordable: boolean
  sold: boolean
}

export interface Snapshot {
  screen: Screen
  round: number
  myTurn: boolean
  dealing: boolean
  pickingTarget: boolean
  myStatus: string
  buttonsVisible: boolean
  deckCount: number
  discardCount: number
  pending: { cardDefId: string; cardId: string; mine: boolean; chosen: string } | null
  /**
   * Cards on the table the local player may still point the prompt at — some
   * cards ask for cards rather than for a seat. Empty whenever nothing is being
   * picked, or when the pick is not the local player's to make.
   */
  pickableCards: string[]
  /** ...and the ones already picked, so a two-card pick can be seen mid-way. */
  pickedCards: string[]
  /** ...and the ones a second click would take back out of the answer. */
  takeableCards: string[]
  seats: Seat[]
  showingIntro: boolean
  showingOutro: boolean
  countdown: number | null
  reconnecting: boolean
  disconnected: boolean
  kicked: boolean
  /**
   * Cards minted mid-round rather than dealt — double-or-nothing's reward.
   * They never join the deck or the discard pile, so any count of the deck has
   * to leave them out.
   */
  ephemeralCards: number

  // ─── Rolling rules. All inert in the classic game. ───

  /** Which game this table is playing. 'classic' on a server that has no modes. */
  mode: 'classic' | 'rollingRules'
  /** What the local player has left to spend. Their score, in the mode that spends it. */
  purse: number
  /** The local player's own gambler cards, face up. Nobody else's are ever rendered. */
  gamblers: GamblerCard[]
  /** How many free slots the shop says the local player's tray has. */
  slotsFree: number
  /**
   * Gambler cards in a tray that were minted rather than dealt — a loan's debt.
   * Counted for display like any other card, and left out of the deck's sum for
   * the same reason [ephemeralCards] is.
   */
  mintedGamblers: number
  /** The cards in flight, oldest first. A counter pushes onto the end of it. */
  responseStack: { cardDefId: string; playerId: string; countered: boolean }[]
  /** Whether the open prompt can be answered by declining it. */
  canPass: boolean
  /** The window between rounds, while it is open. */
  interlude: { seconds: number; ready: boolean } | null
  /** The local player's own shelf. Nobody else's is sent. */
  offers: ShopOffer[]
  /** The jackpot under the hammer, and what the local player has bid for it. */
  auction: { lot: string; myBid: number | null; revealed: boolean } | null
}

export type Move = 'hit' | 'stay'

/** Decides what the local player does when it is their turn. */
export type Policy = (snapshot: Snapshot, me: Seat | undefined) => Move

/** Draws until the round takes it away — the quickest way to end a round. */
export const alwaysHit: Policy = () => 'hit'

/** Banks once the hand has `n` cards. Never stays on an empty hand: the rules forbid it. */
export function stayAfter(n: number): Policy {
  return (_snapshot, me) => ((me?.handSize ?? 0) >= Math.max(1, n) ? 'stay' : 'hit')
}

/**
 * Decides what the local player does when the table asks whether to counter
 * something. Returns the id of a gambler card to play, or null to let it stand.
 *
 * Its own type rather than a wider [Policy], because a turn and a response are
 * different questions asked at different moments — folding them together would
 * make every existing policy know about a mode it does not play.
 */
export type ResponsePolicy = (snapshot: Snapshot) => string | null

/** Lets everything stand. What a spec does unless it is about countering. */
export const neverCounters: ResponsePolicy = () => null

/** Answers with whatever is to hand — for proving the stack resolves, not for playing well. */
export const countersWithAnything: ResponsePolicy = (snapshot) =>
  snapshot.gamblers.find((card) => card.playable)?.id ?? null

/** Decides what the local player does in the window between rounds. */
export type ShopPolicy = (snapshot: Snapshot) => { buy?: string[]; bid?: number }

/**
 * Walks straight through the shop. What a spec does unless it is about the
 * shop — and pressing "done" regardless is what stops every other spec sitting
 * out the whole window.
 */
export const buysNothing: ShopPolicy = () => ({})

/** Takes the `n` cheapest things it can afford. */
export function buysCheapest(n = 1): ShopPolicy {
  return (snapshot) => ({
    buy: snapshot.offers
      .filter((offer) => offer.affordable && !offer.sold)
      .sort((a, b) => a.price - b.price)
      .slice(0, n)
      .map((offer) => offer.id),
  })
}

/** Bids a flat number for the lot, and buys nothing. */
export function bids(points: number): ShopPolicy {
  return () => ({ bid: points })
}

/**
 * Drives the table the way a player does — through the DOM, one decision at a
 * time — rather than by pushing intents down the socket. Everything the server
 * paces (the title card, the deal, bots thinking, the turn clock) is simply
 * waited out.
 */
export class Table {
  constructor(readonly page: Page) {}

  get board(): Locator { return this.page.getByTestId('game-board') }
  get hitButton(): Locator { return this.page.getByTestId('hit') }
  get stayButton(): Locator { return this.page.getByTestId('stay') }
  get drawPile(): Locator { return this.page.getByTestId('draw-pile') }
  get summary(): Locator { return this.page.getByTestId('round-summary') }
  get gameOver(): Locator { return this.page.getByTestId('game-over') }

  seat(playerName: string): Locator {
    return this.page.locator(`[data-testid="seat"][data-player-name="${playerName}"]`)
  }

  get mySeat(): Locator {
    return this.page.locator('[data-testid="seat"][data-self="true"]')
  }

  /** One consistent read of the whole screen. */
  async snapshot(): Promise<Snapshot> {
    return this.page.evaluate(() => {
      const $ = (selector: string) => document.querySelector(selector)
      const attr = (element: Element | null, name: string) => element?.getAttribute(name) ?? null
      const num = (value: string | null, fallback = 0) => {
        const parsed = Number(value)
        return Number.isFinite(parsed) ? parsed : fallback
      }

      const board = $('[data-testid="game-board"]')
      const interlude = $('[data-testid="interlude"]')
      const summary = $('[data-testid="round-summary"]')
      const gameOver = $('[data-testid="game-over"]')
      const pending = $('[data-testid="pending-action"]')
      const countdown = $('[data-testid="countdown"]')

      const screen: string = board
        ? 'board'
        // Before the summary: the window opens on top of the scoreboard, and
        // the two are told apart by whether anything is being asked of you.
        : interlude
          ? 'interlude'
        : summary
          ? 'summary'
          : gameOver
            ? 'gameOver'
            : $('[data-testid="rules-page"]')
              ? 'rules'
              : $('[data-testid="settings-screen"]')
                ? 'settings'
                : $('[data-testid="waiting-room"]')
                  ? 'waiting'
                  : $('[data-testid="connecting-screen"]')
                    ? 'connecting'
                    : $('[data-testid="join-screen"]')
                      ? 'join'
                      : $('[data-testid="title-screen"]')
                        ? 'title'
                        : $('[data-testid="catalog-error"]')
                          ? 'catalogError'
                          : $('[data-testid="catalog-loading"]')
                            ? 'loading'
                            : 'unknown'

      const seats = Array.from(document.querySelectorAll('[data-testid="seat"]')).map((seat) => ({
        id: attr(seat, 'data-player-id') ?? '',
        name: attr(seat, 'data-player-name') ?? '',
        status: (attr(seat, 'data-status') ?? 'active') as Seat['status'],
        handValue: num(attr(seat, 'data-hand-value')),
        handSize: num(attr(seat, 'data-hand-size')),
        passiveCount: num(attr(seat, 'data-passive-count')),
        gamblerCount: num(attr(seat, 'data-gambler-count')),
        gamblerSlots: num(attr(seat, 'data-gambler-slots')),
        targetable: attr(seat, 'data-targetable') === 'true',
        isBot: attr(seat, 'data-bot') === 'true',
        isSelf: attr(seat, 'data-self') === 'true',
      }))

      const actionButtons = $('[data-testid="action-buttons"]')
      const cardIds = (selector: string) =>
        Array.from(document.querySelectorAll(selector))
          .map((card) => attr(card, 'data-card-id') ?? '')
          .filter(Boolean)

      return {
        screen,
        round: num(attr(board ?? summary, 'data-round')),
        myTurn: attr(board, 'data-my-turn') === 'true',
        dealing: attr(board, 'data-dealing') === 'true',
        pickingTarget: attr(board, 'data-picking-target') === 'true',
        myStatus: attr(board, 'data-my-status') ?? 'none',
        buttonsVisible: attr(actionButtons, 'data-visible') === 'true',
        deckCount: num(attr($('[data-testid="draw-pile"]'), 'data-count')),
        discardCount: num(attr($('[data-testid="discard-pile"]'), 'data-count')),
        pending: pending
          ? {
              cardDefId: attr(pending, 'data-card-def-id') ?? '',
              cardId: attr(pending, 'data-card-id') ?? '',
              mine: attr(pending, 'data-mine') === 'true',
              chosen: attr(pending, 'data-chosen') ?? '',
            }
          : null,
        pickableCards: cardIds('[data-pickable="true"]'),
        pickedCards: cardIds('[data-picked="true"]'),
        takeableCards: cardIds('[data-unpickable="true"]'),
        seats,
        showingIntro: !!$('[data-testid="round-intro"]'),
        showingOutro: !!$('[data-testid="round-outro"]'),
        countdown: countdown ? num(attr(countdown, 'data-count')) : null,
        reconnecting: !!$('[data-testid="reconnecting"]'),
        disconnected: !!$('[data-testid="disconnected"]'),
        kicked: attr($('[data-testid="disconnected"]'), 'data-kicked') === 'true',
        // The engine stamps a minted card's id with `tmp-`.
        //
        // Scoped to the table, and to cards that came off the deck. The testing
        // panel and the shop's shelf both draw cards that were never in play,
        // and an unscoped count of them would be subtracted from a deck they
        // were never part of. Gambler cards are excluded for the opposite
        // reason: a minted one — a loan's debt — is counted where it is held,
        // by [mintedGamblers], and would otherwise be taken off twice.
        ephemeralCards: document.querySelectorAll(
          '[data-testid="game-board"] [data-card-id^="tmp-"]:not([data-gambler="true"])',
        ).length,

        mode: (attr(board, 'data-mode') ?? 'classic') as Snapshot['mode'],
        purse: num(attr(board ?? interlude, 'data-purse')),
        gamblers: Array.from(document.querySelectorAll('[data-testid="gambler-card"]')).map((card) => ({
          id: attr(card, 'data-card-id') ?? '',
          defId: attr(card, 'data-card-def-id') ?? '',
          rarity: attr(card, 'data-rarity') ?? '',
          window: attr(card, 'data-window') ?? '',
          playable: attr(card, 'data-playable') === 'true',
        })),
        mintedGamblers: num(attr(board ?? interlude, 'data-minted-gamblers')),
        responseStack: Array.from(document.querySelectorAll('[data-testid="stack-card"]')).map((card) => ({
          cardDefId: attr(card, 'data-card-def-id') ?? '',
          playerId: attr(card, 'data-player-id') ?? '',
          countered: attr(card, 'data-countered') === 'true',
        })),
        canPass: !!$('[data-testid="pass"]'),
        slotsFree: num(attr(interlude, 'data-slots-free')),
        interlude: interlude
          ? {
              seconds: num(attr(interlude, 'data-seconds')),
              ready: attr(interlude, 'data-ready') === 'true',
            }
          : null,
        offers: Array.from(document.querySelectorAll('[data-testid="shop-offer"]')).map((offer) => ({
          id: attr(offer, 'data-offer-id') ?? '',
          price: num(attr(offer, 'data-price')),
          // The in-round shop only ever offers what the buyer can pay for, so
          // an older sheet that says nothing is saying yes.
          affordable: attr(offer, 'data-affordable') !== 'false',
          sold: attr(offer, 'data-sold') === 'true',
        })),
        auction: $('[data-testid="auction"]')
          ? {
              lot: attr($('[data-testid="auction"]'), 'data-lot') ?? '',
              myBid: (attr($('[data-testid="auction"]'), 'data-my-bid') ?? '') === ''
                ? null
                : num(attr($('[data-testid="auction"]'), 'data-my-bid')),
              revealed: attr($('[data-testid="auction"]'), 'data-revealed') === 'true',
            }
          : null,
      } as Snapshot
    })
  }

  async me(): Promise<Seat | undefined> {
    return (await this.snapshot()).seats.find((seat) => seat.isSelf)
  }

  /**
   * Every card from the deck the client can account for: the two piles, the
   * hands, and the one card a player may be holding over somebody's head.
   * Should always come to the size of the deck that was chosen — a client that
   * has lost track of a card is showing a table that does not exist.
   *
   * Cards minted mid-round are left out on purpose: they were never in the deck
   * and are dropped at the end of the round rather than discarded.
   *
   * The rolling-rules terms are all zero in the classic game. A gambler card off
   * the deck has to be counted wherever it has got to — a tray, or in the air on
   * the response stack — and the sum must not move when it moves between them.
   * The shop's cards are the other way round: minted rather than dealt, so they
   * are subtracted straight back out. The deck never had them.
   */
  static cardsAccountedFor(snapshot: Snapshot): number {
    const inHands = snapshot.seats.reduce((total, seat) => total + seat.handSize + seat.passiveCount, 0)
    const inTrays = snapshot.seats.reduce((total, seat) => total + seat.gamblerCount, 0)
    return snapshot.deckCount +
      snapshot.discardCount +
      inHands +
      (snapshot.pending ? 1 : 0) -
      snapshot.ephemeralCards +
      inTrays +
      snapshot.responseStack.length -
      snapshot.mintedGamblers
  }

  async hit(): Promise<void> {
    await this.hitButton.click()
  }

  async stay(): Promise<void> {
    await this.stayButton.click()
  }

  /** The draw pile is the other way to take a card; the buttons are not the only path. */
  async hitByClickingTheDeck(): Promise<void> {
    await this.drawPile.click()
  }

  async pickTarget(playerId: string): Promise<void> {
    await this.page.locator(`[data-testid="seat"][data-player-id="${playerId}"]`).click()
  }

  /**
   * Plays on until [done] is true, answering every prompt the table raises: a
   * turn, or an action card waiting on a target.
   *
   * Returns the snapshot that satisfied [done].
   */
  async playUntil(
    done: (snapshot: Snapshot) => boolean,
    options: {
      policy?: Policy
      respond?: ResponsePolicy
      shop?: ShopPolicy
      timeoutMs?: number
      description?: string
    } = {},
  ): Promise<Snapshot> {
    const {
      policy = stayAfter(2),
      respond = neverCounters,
      shop = buysNothing,
      timeoutMs = 150_000,
      description = 'the table to get there',
    } = options
    const deadline = Date.now() + timeoutMs
    let snapshot = await this.snapshot()

    while (!done(snapshot)) {
      if (Date.now() > deadline) {
        throw new Error(
          `waited ${timeoutMs}ms for ${description}. ` +
          `Last seen: ${JSON.stringify({
            screen: snapshot.screen,
            round: snapshot.round,
            myTurn: snapshot.myTurn,
            myStatus: snapshot.myStatus,
            pending: snapshot.pending,
            seats: snapshot.seats.map((s) => `${s.name}:${s.status}:${s.handSize}`),
          })}`,
        )
      }

      if (snapshot.screen === 'board') {
        // First, because it is not a prompt: a card in flight stops the table
        // for everybody, and the question is asked of people who are not on
        // turn and have nothing pending. Left unanswered it sits out its whole
        // clock, every single time.
        if (snapshot.canPass) {
          await this.answerResponse(snapshot, respond)
        } else if (snapshot.pending?.mine && (!snapshot.pending.chosen || snapshot.pickableCards.length > 0)) {
          // A card-picking prompt is answered over several clicks, so it stays
          // answerable while there is anything still on offer.
          await this.answerPrompt(snapshot)
        } else if (snapshot.myTurn && snapshot.buttonsVisible) {
          await this.takeTurn(snapshot, policy)
        }
      } else if (snapshot.screen === 'interlude') {
        await this.doInterlude(snapshot, shop)
      }

      await this.page.waitForTimeout(200)
      snapshot = await this.snapshot()
    }

    return snapshot
  }

  /** Plays to the end of the current round. */
  async playRound(
    options: { policy?: Policy; respond?: ResponsePolicy; shop?: ShopPolicy; timeoutMs?: number } = {},
  ): Promise<Snapshot> {
    return this.playUntil(
      (snapshot) => snapshot.screen === 'summary' || snapshot.screen === 'gameOver',
      { ...options, description: 'the round to be scored' },
    )
  }

  private async takeTurn(snapshot: Snapshot, policy: Policy): Promise<void> {
    const me = snapshot.seats.find((seat) => seat.isSelf)
    const move = policy(snapshot, me)
    // Going out on an empty hand is illegal and the button is disabled for it.
    const canStay = (me?.handSize ?? 0) > 0
    const button = move === 'stay' && canStay ? this.stayButton : this.hitButton

    // The prompt can pass to somebody else between the read and the click —
    // a bot moving, or a card resolving — and that is not a failure.
    await button.click({ timeout: 8_000 }).catch(() => undefined)
  }

  /**
   * Answers whatever the table is asking the local player for: cards off the
   * table, a seat, or a question. One click per pass — a card that wants two
   * picks comes back round on the next snapshot.
   *
   * A prompt can also pass to somebody else between the read and the click, so
   * every one of these is allowed to miss.
   */
  /**
   * Answers a card in flight: with one of your own, or by letting it stand.
   *
   * Declining is what the harness does unless a spec asked otherwise — a
   * counter played on the suite's own initiative would change the round a spec
   * meant only to sit through.
   */
  private async answerResponse(snapshot: Snapshot, respond: ResponsePolicy): Promise<void> {
    const cardId = respond(snapshot)
    // The prompt can pass to somebody else between the read and the click.
    if (cardId) await this.playGamblerCard(cardId).catch(() => undefined)
    else await this.passResponse().catch(() => undefined)
  }

  private async answerPrompt(snapshot: Snapshot): Promise<void> {
    if (snapshot.pickableCards.length > 0) {
      await this.pickCard(snapshot.pickableCards[0]).catch(() => undefined)
      return
    }

    const targets = snapshot.seats.filter((seat) => seat.targetable)
    if (targets.length > 0) {
      // Prefer somebody else, the way a person would.
      const target = targets.find((seat) => !seat.isSelf) ?? targets[0]
      await this.pickTarget(target.id).catch(() => undefined)
      return
    }

    // Heads or tails, left or right: a card that asks rather than points.
    const option = this.page.locator('[data-testid="choice-option"][data-picked="false"]').first()
    if (await option.count()) await option.click({ timeout: 4_000 }).catch(() => undefined)
  }

  /**
   * Spends the window between rounds and says so.
   *
   * The "done" click at the end is not optional. The window has a deadline of
   * its own and only shuts early once everybody has said they have finished, so
   * a spec that walked away from it would sit out the whole thing every round.
   */
  private async doInterlude(snapshot: Snapshot, shop: ShopPolicy): Promise<void> {
    if (snapshot.interlude?.ready) return

    const { buy = [], bid } = shop(snapshot)
    for (const offerId of buy) {
      if (snapshot.offers.find((offer) => offer.id === offerId)?.sold) continue
      await this.buyOffer(offerId).catch(() => undefined)
    }
    if (bid !== undefined && snapshot.auction) {
      await this.placeBid(bid).catch(() => undefined)
    }

    await this.readyUp().catch(() => undefined)
    // The window shuts when everybody has said so, so there is nothing more to
    // do here — one pass, and the next snapshot is on the other side of it.
  }

  /** Offers a gambler card to the table. The server decides what happens next. */
  async playGamblerCard(cardId: string): Promise<void> {
    await this.page
      .locator(`[data-testid="gambler-card"][data-card-id="${cardId}"][data-playable="true"]`)
      .first()
      .click({ timeout: 8_000 })
  }

  /** Declines a response window — "let it stand". */
  async passResponse(): Promise<void> {
    await this.page.getByTestId('pass').click({ timeout: 4_000 })
  }

  /**
   * Buys an offer, from either shop.
   *
   * The two of them ask differently. The mid-round sheet's card *is* the buy
   * button; the shelf between rounds separates them, so that the card can be
   * picked up and read without that being an answer. Both keep the same
   * `shop-offer` wrapper and the same attributes, so a spec only has to know
   * which offer it wants.
   */
  async buyOffer(offerId: string): Promise<void> {
    const offer = this.page.locator(`[data-testid="shop-offer"][data-offer-id="${offerId}"]`).first()
    const buy = offer.locator('[data-testid="shop-buy"]')
    const target = (await buy.count()) > 0 ? buy.first() : offer
    await target.click({ timeout: 8_000 })
  }

  /**
   * Writes a bid down. It is not sent here — it goes over with "i'm done",
   * because a bid you could still place after the shop shut would be a bid
   * against a purse you had already spent.
   */
  async placeBid(points: number): Promise<void> {
    await this.page.getByTestId('bid-input').fill(String(points))
  }

  async readyUp(): Promise<void> {
    await this.page.getByTestId('interlude-ready').click({ timeout: 4_000 })
  }

  /**
   * Waits for the window between rounds to open, and hands it over untouched —
   * [playUntil] asks whether it has arrived before it acts, so the window is
   * still open and nothing has been bought or agreed to.
   */
  async waitForInterlude(): Promise<Snapshot> {
    return this.playUntil((snapshot) => snapshot.screen === 'interlude', {
      timeoutMs: 90_000,
      description: 'the window between rounds to open',
    })
  }

  async pickCard(cardId: string): Promise<void> {
    await this.page.locator(`[data-card-id="${cardId}"][data-pickable="true"]`).first().click({ timeout: 8_000 })
  }

  /** Clicks a card that has already been picked, which takes it back. */
  async unpickCard(cardId: string): Promise<void> {
    await this.page.locator(`[data-card-id="${cardId}"][data-unpickable="true"]`).first().click({ timeout: 8_000 })
  }

  /**
   * Waits for the round's title card to lift and the opening deal to finish.
   *
   * It answers a target prompt on the way, because the deal genuinely stops for
   * one: an opening card that turns out to be an action card is played before
   * the next player is dealt in. It never takes a *turn* — nobody has one while
   * the deal is still running.
   */
  async waitForPlay(): Promise<Snapshot> {
    await expect(this.board).toBeVisible({ timeout: 45_000 })
    return this.playUntil(
      (snapshot) => snapshot.screen !== 'board' || (!snapshot.showingIntro && !snapshot.dealing),
      { timeoutMs: 90_000, description: 'the opening deal to finish' },
    )
  }
}
