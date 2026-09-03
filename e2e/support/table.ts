import { expect, type Locator, type Page } from '@playwright/test'

/** Everything a spec needs to know about the screen, read in one pass. */
export interface Seat {
  id: string
  name: string
  status: 'active' | 'stayed' | 'bust'
  handValue: number
  handSize: number
  passiveCount: number
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
  | 'summary'
  | 'gameOver'
  | 'unknown'

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
      const summary = $('[data-testid="round-summary"]')
      const gameOver = $('[data-testid="game-over"]')
      const pending = $('[data-testid="pending-action"]')
      const countdown = $('[data-testid="countdown"]')

      const screen: string = board
        ? 'board'
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
        targetable: attr(seat, 'data-targetable') === 'true',
        isBot: attr(seat, 'data-bot') === 'true',
        isSelf: attr(seat, 'data-self') === 'true',
      }))

      const actionButtons = $('[data-testid="action-buttons"]')

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
        seats,
        showingIntro: !!$('[data-testid="round-intro"]'),
        showingOutro: !!$('[data-testid="round-outro"]'),
        countdown: countdown ? num(attr(countdown, 'data-count')) : null,
        reconnecting: !!$('[data-testid="reconnecting"]'),
        disconnected: !!$('[data-testid="disconnected"]'),
        kicked: attr($('[data-testid="disconnected"]'), 'data-kicked') === 'true',
        // The engine stamps a minted card's id with `tmp-`.
        ephemeralCards: document.querySelectorAll('[data-card-id^="tmp-"]').length,
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
   */
  static cardsAccountedFor(snapshot: Snapshot): number {
    const inHands = snapshot.seats.reduce((total, seat) => total + seat.handSize + seat.passiveCount, 0)
    return snapshot.deckCount + snapshot.discardCount + inHands + (snapshot.pending ? 1 : 0) - snapshot.ephemeralCards
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
    options: { policy?: Policy; timeoutMs?: number; description?: string } = {},
  ): Promise<Snapshot> {
    const { policy = stayAfter(2), timeoutMs = 150_000, description = 'the table to get there' } = options
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
        if (snapshot.pending?.mine && !snapshot.pending.chosen) {
          await this.answerTargetPrompt(snapshot)
        } else if (snapshot.myTurn && snapshot.buttonsVisible) {
          await this.takeTurn(snapshot, policy)
        }
      }

      await this.page.waitForTimeout(200)
      snapshot = await this.snapshot()
    }

    return snapshot
  }

  /** Plays to the end of the current round. */
  async playRound(options: { policy?: Policy; timeoutMs?: number } = {}): Promise<Snapshot> {
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

  private async answerTargetPrompt(snapshot: Snapshot): Promise<void> {
    const targets = snapshot.seats.filter((seat) => seat.targetable)
    if (targets.length === 0) return
    // Prefer somebody else, the way a person would.
    const target = targets.find((seat) => !seat.isSelf) ?? targets[0]
    await this.pickTarget(target.id).catch(() => undefined)
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
