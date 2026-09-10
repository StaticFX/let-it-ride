/**
 * Mirrors the wire types the Kotlin backend serialises. The backend owns every
 * rule; nothing in here decides anything about the game.
 */

// ─── Cards ───

/**
 * `gambler` is rolling rules only: a card that goes into the hand nobody else
 * can see, and comes out when its holder chooses. Older servers never send one.
 */
export type CardKind = 'number' | 'action' | 'passive' | 'gambler'

export interface Card {
  id: string
  kind: CardKind
  /** What is printed on the card, and the key duplicates are matched on. */
  label: string
  value: number
  defId?: string
  suit?: string
  /**
   * A card you are not being shown — see the "redacted" card, and
   * `GameStateView.hiddenHandIds`.
   *
   * The id and the kind are all that arrive; the label, the value, the suit and
   * the definition have been cut out by the server. Draw a back for it wherever
   * it turns up — `PlayingCard` does that once, for every one of them — and
   * never read anything else off it. Older servers omit it and nothing is
   * hidden.
   */
  hidden?: boolean
}

// ─── Players ───

export type PlayerStatus = 'active' | 'stayed' | 'bust'

export interface Player {
  id: string
  name: string
  hand: Card[]
  passives: Card[]
  handValue: number
  status: PlayerStatus
  score: number
  bustReason?: string
  skipNextTurn: boolean
  connected: boolean
  isBot: boolean
}

// ─── Config ───

/**
 * The id a deck that is nobody's preset goes by. A config carrying this keeps
 * its own `deck` instead of having a preset's copied over it — so anything that
 * looks a deck up by id has to expect not to find one.
 */
export const CUSTOM_DECK_ID = 'custom'

export type WinCondition = 'rounds' | 'first_to_score'

export interface NumberCardEntry {
  value: number
  count: number
  label?: string
  suits?: string[]
}

export interface DeckConfig {
  numberCards: NumberCardEntry[]
  actionCards: string[]
  passiveCards: string[]
  /** Gambler cards shuffled in — rolling rules. Older servers omit it. */
  gamblerCards?: string[]
}

/**
 * Which game a table is playing. A mode, not a house rule: it changes the
 * screens you see rather than a number the engine reads. Older servers omit it
 * and the game is the classic one.
 */
export type GameMode = 'classic' | 'rollingRules'

/** The mode a config is playing, with the fallback in one place. */
export function modeOf(config?: GameConfig | null): GameMode {
  return config?.mode ?? 'classic'
}

/** The three rarities of gambler card, rarest last. */
export type GamblerRarity = 'common' | 'rare' | 'jackpot'

/**
 * When a gambler card may be played. The client shows it on the face; whether a
 * window is actually open is the server's answer — see
 * `GameStateView.playableGamblers`.
 */
export type GamblerWindow =
  | 'onTurn'
  | 'onOtherTurn'
  | 'always'
  | 'onOut'
  | 'inResponse'
  | 'passive'
  /** Only between rounds, with the shop open — see "back to the shop". */
  | 'interlude'

/**
 * When each window is, in the table's words rather than the enum's.
 *
 * Copy, not rules: the server decides whether a window is actually open and says
 * so in `playableGamblers`. This is only how the answer is spelled.
 *
 * Two phrasings because they are read in two places. The short one is printed on
 * the card itself, where it has to fit under a sigil and be taken in at a glance
 * — and it has to be *there*, because a card whose whole cost is "you may only
 * play this at one moment" and which does not say which moment is a card you
 * find out about by being refused.
 */
export const GAMBLER_WINDOWS: Record<GamblerWindow, string> = {
  onTurn: 'on your turn',
  onOtherTurn: 'on somebody else’s turn',
  always: 'any time',
  onOut: 'once you are out',
  inResponse: 'to answer another card',
  passive: 'never — it works by being held',
  interlude: 'between rounds, at the shop',
}

export const GAMBLER_WINDOWS_SHORT: Record<GamblerWindow, string> = {
  onTurn: 'your turn',
  onOtherTurn: 'their turn',
  always: 'any time',
  onOut: 'when out',
  inResponse: 'in response',
  passive: 'while held',
  interlude: 'at the shop',
}

export interface GameConfig {
  deckPresetId: string
  deck: DeckConfig
  /** Older servers omit it; see `modeOf`. */
  mode?: GameMode
  ruleIds: string[]
  winCondition: WinCondition
  totalRounds: number
  targetScore: number
  turnTimeSeconds: number
  /**
   * How long the shop stays open between rounds — rolling rules. A ceiling
   * rather than a schedule: it shuts when everybody says they are finished.
   * Older servers omit it.
   */
  shopSeconds?: number
  /**
   * Seconds the scoreboard stays up before the next round deals itself, or
   * null/absent to wait for the host. The countdown belongs to the server —
   * see `GameStateView.nextRoundAt`.
   */
  autoNextRoundSeconds?: number | null
}

// ─── State ───

export type GamePhase = 'LOBBY' | 'PLAYING' | 'ROUND_END' | 'GAME_END'

/**
 * One card on sale and what it costs — see the "mutate" card. The server prices
 * it and decides who can afford it; the client draws the face and the number.
 */
export interface Offer {
  id: string
  price: number
  card: Card
}

/**
 * The shop between rounds, as this player sees it.
 *
 * The shelf is your own and nobody else's is sent — private stock is private on
 * the wire, not just in the rules. `done` is names only.
 */
export interface InterludeView {
  offers?: Offer[]
  /** The jackpot on the block, with its list price as a guide. */
  lot?: Offer
  /** What you bid, once you have. Nobody else's, ever, until the hammer. */
  myBid?: number
  /** How it came out. Absent while the window is open — the bids are sealed. */
  sale?: SaleView
  /** Which of them you have taken. */
  bought?: string[]
  /** What you came in holding, so the window can say what the evening cost. */
  openingScore?: number
  /** Free gambler slots, so a full tray can be greyed out and say *why*. */
  slotsFree?: number
  /** Who has finished. */
  done?: string[]
  /** True once the window has shut and the next round is on its way. */
  closed?: boolean
}

/** How an auction came out. Only ever sent after the hammer. */
export interface SaleView {
  card: Card
  /** Every bid, revealed together. Empty while the window was open.  */
  bids?: Record<string, number>
  winnerId?: string
  price?: number
  /** Anybody whose "rigged bid" fired after the close. */
  rigged?: string[]
}

/** One gambler card in flight. See `GameStateView.responseStack`. */
export interface ResponseFrame {
  id: number
  cardDefId: string
  card: Card
  playerId: string
  targetId: string
  /** Struck through — the card above it stopped it. */
  cancelled?: boolean
}

/**
 * Does anybody want to answer the card on top of the stack?
 *
 * `responders` is everybody who was asked and `awaiting` is who has not spoken
 * yet — names only, never what anybody said. Everybody still in is asked
 * whenever the window opens at all, so being on the list says nothing about
 * what you are holding.
 */
export interface ResponseWindow {
  frameId: number
  responders?: string[]
  awaiting?: string[]
}

export interface PendingActionView {
  cardDefId: string
  playerId: string
  /** The physical card's id — unique per copy, unlike cardDefId. Older servers omit it. */
  cardId?: string
  /** The only seats this card may be pointed at. Older servers omit it. */
  validTargets?: string[]
  /**
   * The question the card asks its drawer — heads/tails, left/right. Non-empty
   * means the table is waiting on an answer even when there is only one seat to
   * point at, and the pick has to carry a `choice` back. Empty, or missing on an
   * older server, for every card that only wants a target.
   */
  options?: string[]
  /**
   * What the drawer is pointing at. `card` means the pick is made off the table
   * itself and the seats stay out of it. Older servers omit it, and everything
   * before this was a seat.
   */
  kind?: 'player' | 'card' | 'catalog'
  /** The cards that may be picked, when `kind` is `card`. */
  validCards?: string[]
  /** How many picks are owed before the card resolves. */
  picks?: number
  /**
   * Whether each pick has to come off a different seat. True for a card that
   * trades two — two picks on one hand is a hand that has not changed — and
   * false for one that gives cards of your own away, where every pick is
   * necessarily yours. Older servers omit it, and every card that picked cards
   * was a trade.
   */
  oneCardPerSeat?: boolean
  /** What is for sale, when `kind` is `catalog`. */
  offers?: Offer[]
  /**
   * Why the table is stopped. `play` — the default, and what older servers
   * imply — is a card that was just drawn. Anything else is a question
   * something set up earlier, which needs its own wording because no card
   * arrived to explain it.
   */
  phase?: string
  /**
   * Everybody who owes an answer. One name for nearly every prompt; the handful
   * that ask the table at once name everybody. Older servers omit it, and the
   * drawer was the only answerer before this.
   */
  responders?: string[]
  /**
   * Who has answered so far — the names only. What they said is never sent
   * while the prompt is open, which is what keeps a simultaneous prompt secret.
   */
  answered?: string[]
}

/**
 * A card that has landed and is about to take effect. Some cards *are* their
 * animation — a coin turning over, a bottle slowing down — so they are
 * announced in one push and settled in the next, and the table is busy in
 * between. Older servers omit it and resolved both at once.
 */
export interface PendingOutcome {
  cardDefId: string
  playerId: string
  targetId: string
  /** What the card settled on while it was being watched — the face it landed on. */
  result?: string
  choice?: string
}

export interface ForcedDraws {
  playerId: string
  remaining: number
  /** Which card queued these, e.g. 'slots'. */
  source?: string
}

/**
 * A batch of events this table is still animating. The server will not move
 * again until `ackPlayerId`'s client sends `ANIM_DONE` for this id — or until
 * `timeoutAt` passes, so a tab that never answers cannot hold the room.
 *
 * Durations live here, not on the server: it waits to be told, it does not
 * guess. Older servers omit the field entirely, and nothing gates.
 */
export interface AnimationGate {
  id: number
  ackPlayerId: string
  timeoutAt: number
}

export interface GameStateView {
  roomCode: string
  hostId?: string
  phase: GamePhase
  round: number
  players: Player[]
  turnIndex: number
  roundStartPlayer: number
  config: GameConfig
  deckCount: number
  discardCount: number
  pendingAction?: PendingActionView
  /** Cards that have landed and are about to take effect. */
  pendingOutcomes?: PendingOutcome[]
  forcedDraws?: ForcedDraws
  dealQueue: string[]
  /**
   * Seats that may not choose to go out — see the "antimatter" card. The server
   * works this out; the client only hides a button. Older servers omit it.
   */
  cannotStayIds?: string[]
  /**
   * Seats a bust does not write off — see the "antimatter" card. A busted seat
   * is struck through and shown a dead number everywhere on the felt, which is
   * the truth for every player but this one: they take the hole whether they
   * stopped, flipped or busted. The server says which; older servers omit it,
   * and the old reading — every bust is written off — stands in.
   */
  bustStillCountsIds?: string[]
  /**
   * What each player's number cards are worth to them as it stands — the same
   * total the seat has always shown, except that a card can turn it over (see
   * "antimatter"). `Player.handValue` is the physical sum and is what busts a
   * hand; this is what to print. Older servers omit it.
   */
  handWorth?: Record<string, number>
  /**
   * Seats whose hand you are not being shown — see the "redacted" card.
   *
   * Their cards are still in the player list and still countable, but every one
   * of them arrives face down (`Card.hidden`) and the seat is absent from
   * `handWorth`: a total is the one number that gives a hand away entirely. This
   * is what lets the seat print a "?" rather than a nought. Never your own seat,
   * and older servers omit it.
   */
  hiddenHandIds?: string[]
  roundWinnerId?: string
  gameWinnerId?: string
  flip7PlayerId?: string
  /**
   * Unique cards this room actually plays to — 7 normally, 9 with "flip 9" on.
   * The catalog's copy is only the ruleless default, so anything showing a live
   * game's progress reads this one.
   */
  flip7Target: number
  roundDeltas: Record<string, number>
  /**
   * Points moved during the round by something other than hand scoring — an
   * anti-flip deduction. Already inside `roundDeltas`; this is the itemisation,
   * so a player who scored nothing can be told why. Older servers omit it.
   */
  roundAdjustments?: Record<string, number>
  /** Epoch millis the current actor runs out of time, if they are on a clock. */
  turnDeadline?: number
  /** Epoch millis the round's title card stops showing; nothing is dealt until then. */
  roundIntroUntil?: number
  /** Epoch millis the round's closing card appears. */
  roundOutroFrom?: number
  /** Epoch millis the closing card gives way to the scoreboard. */
  roundOutroUntil?: number
  /** The animation the table is currently held on, if any. */
  animationGate?: AnimationGate
  /**
   * Epoch millis the next round deals itself under the host's autostart
   * setting. Absent when the table is waiting to be told, and always absent
   * once the game is settled. Pressing the button early still wins.
   */
  nextRoundAt?: number
  /**
   * How many gambler cards each seat is carrying — rolling rules. Public on
   * every seat, and only ever a number: the five-slot cap is public, so you have
   * to be able to see that somebody is full, and the faces are the one thing in
   * this game nobody else may see. Older servers omit it.
   */
  gamblerCounts?: Record<string, number>
  /** How many each seat could carry — five, plus room a "pouch" made. */
  gamblerLimits?: Record<string, number>
  /**
   * Your own gambler cards, face up. Every other seat's copy of this state has
   * their own here instead — this is the one field that is not the same for
   * everybody at the table.
   */
  myGamblers?: Card[]
  /**
   * Which of `myGamblers` you may play right now, by card id.
   *
   * Whether a window is open is a rule and rules are the server's, exactly as
   * `cannotStayIds` is. The client lights these and works nothing out.
   */
  playableGamblers?: string[]
  /**
   * Gambler cards somebody is holding that the shop minted rather than the deck
   * dealt. A count, so anything counting the deck can leave them out of it —
   * they were never in it.
   */
  mintedGamblers?: number
  /**
   * Cards off the top of the deck you have been shown — see "foreseer" and
   * "stacked deck". Empty for everybody else, always: the same per-viewer rule
   * as the hidden hand, said about the deck.
   */
  foresight?: Card[]
  /** The shop between rounds, while it is open. Your own shelf; never anybody else's. */
  interlude?: InterludeView
  /** Epoch millis the shop shuts. Absent when no window is open. */
  interludeUntil?: number
  /**
   * Gambler cards in flight, oldest first — cards answer cards, so a counter
   * sits on top of what it is answering. Every one of them is face up: it was
   * turned over when it was played. Empty whenever nothing is in flight.
   */
  responseStack?: ResponseFrame[]
  /** The open question, when the table is stopped on one. */
  responseWindow?: ResponseWindow
  /**
   * The next cards off the deck, in draw order — only ever sent by a server
   * running the testing mode, and the reason the dev panel can show what is
   * coming. Absent everywhere else, which is every server anybody plays on.
   */
  devDeck?: Card[]
  /**
   * Every seat's hidden tray, by player id — the testing mode again, and absent
   * everywhere else. `myGamblers` is what a player is shown; this is what a
   * panel is shown, and the difference is the whole point of the mode.
   */
  devGamblers?: Record<string, Card[]>
}

// ─── Events ───

export type GameEvent =
  | { type: 'draw'; playerId: string; card: Card }
  | { type: 'passive'; playerId: string; card: Card }
  | { type: 'bust'; playerId: string; reason: string; card?: Card; matched?: Card }
  | { type: 'stay'; playerId: string }
  | { type: 'skip'; playerId: string }
  | { type: 'discard'; playerId: string; card: Card }
  /**
   * One card crossed the table from one seat to another. Named for the card
   * that first sent one; a circlejerk hands one over and a reverse circlejerk
   * asks for one, and none of that is any different to watch.
   */
  | { type: 'steal'; fromPlayerId: string; toPlayerId: string; card: Card }
  | { type: 'swap'; fromPlayerId: string; toPlayerId: string }
  /**
   * Two cards changed hands: `firstCard` went from `firstPlayerId` to
   * `secondPlayerId` and `secondCard` came back the other way.
   */
  | {
      type: 'cardsSwapped'
      firstPlayerId: string
      firstCard: Card
      secondPlayerId: string
      secondCard: Card
    }
  | { type: 'freeze'; playerId: string }
  /**
   * `points` moved from one player to another mid-round — a toll rather than
   * anything the hands did. Both halves are already in the round's adjustments;
   * this is the announcement, so the table can watch the points cross it.
   */
  | { type: 'pointsTransferred'; fromPlayerId: string; toPlayerId: string; points: number }
  | { type: 'actionPlayed'; cardDefId: string; fromPlayerId: string; targetPlayerId: string }
  /**
   * A duplicate eaten instead of a bust. `card` is what would have ended the
   * round, `matched` is what it clashed with, and `saver` is the second life
   * that was spent stopping it — which is already off the modifier row in the
   * state that arrives with this, so the event is the only place it exists.
   * Older servers omit it.
   */
  | { type: 'secondChance'; playerId: string; card: Card; matched?: Card; saver?: Card }
  | { type: 'secondChancePassed'; fromPlayerId: string; toPlayerId: string }
  | { type: 'fizzled'; cardDefId: string; playerId: string }
  | { type: 'flip7'; playerId: string }
  /**
   * The coin was called and thrown. `call` is what the player said, `result` is
   * the face it landed on — both travel together so the coin can land on the
   * announced face instead of the client guessing it from the outcome. They
   * match exactly when the player won.
   */
  | { type: 'coinFlip'; playerId: string; call: string; result: string }
  /**
   * Assassination's bottle stopped on `victimId`. The server spins it — four
   * clients rolling their own would each show a different bottle — and the bust
   * that follows is the same event every other bust sends.
   */
  | { type: 'bottleSpin'; victimId: string }
  /**
   * Every hand in `playerIds` moved one seat. The list is in seat order and only
   * holds the seats that took part; for `right` each player's hand went to the
   * next id in the list (wrapping), for `left` to the previous one.
   */
  | { type: 'tableSpun'; direction: string; playerIds: string[] }
  | { type: 'slots'; playerId: string; card?: Card }
  | { type: 'timeout'; playerId: string }
  | { type: 'deckReshuffled'; cards: number }
  /**
   * `playerId` gave up their flip bonus to take `points` off `targetPlayerId`.
   * Both halves are already in the round's deltas; this is the announcement.
   */
  | { type: 'antiFlip'; playerId: string; targetPlayerId: string; points: number }
  /**
   * "Comeback": both throws at once, because neither could see the other's
   * until now. `challengerWon` settles it, and two of the same is a draw —
   * which sends them both back for another throw rather than ending anything.
   * The server says so by asking again; the two throws being equal is how this
   * event says it, and there is deliberately no second field that could
   * disagree.
   */
  | {
      type: 'throws'
      challengerId: string
      challengerThrow: string
      leaderId: string
      leaderThrow: string
      challengerWon: boolean
    }
  /** Two players' banked scores changed places. */
  | {
      type: 'scoresSwapped'
      firstPlayerId: string
      firstScore: number
      secondPlayerId: string
      secondScore: number
    }
  /** "All in": every bet turned face up at once; `halvedIds` bet the extremes. */
  | { type: 'allIn'; bets: Record<string, Card>; halvedIds: string[] }
  /** `playerId` bought `card`, and the round is `price` the poorer for it. */
  /**
   * `hidden` is set for a card bought into a hand nobody may see: the buyer and
   * the price are public, and the face is cut out for everybody but the buyer.
   */
  | { type: 'bought'; playerId: string; card: Card; price: number; hidden?: boolean }
  /** The shop opened between rounds, and what is on the block. */
  | { type: 'shopOpened'; lot?: Card }
  /** Every sealed bid turned over at once, and what the lot went for. */
  | {
      type: 'auction'
      lot: Card
      bids?: Record<string, number>
      winnerId?: string
      price?: number
      rigged?: string[]
    }
  /** Gambler cards went back to the dealer — see "back to the shop". */
  | { type: 'soldBack'; playerId: string; cards: Card[]; points: number }
  /** A tithe collected off everybody else's round — see "taxes". */
  | { type: 'taxed'; playerId: string; points: number }
  /** A loan came due. */
  | { type: 'loanRepaid'; playerId: string; points: number }
  /** Somebody who was out of the round is back in it. */
  | { type: 'revived'; playerId: string }
  /**
   * Somebody drew a gambler card. `card` is filled in only in that player's own
   * copy of the batch — everybody else is told that a card went into a hidden
   * hand, and not which. `kept` is false when there was no room and it went to
   * the discard pile instead.
   */
  | { type: 'gamblerDrawn'; playerId: string; card?: Card; kept?: boolean }
  /**
   * A gambler card came out of a hidden hand and went face up. Public, and
   * completely so — playing one is how the table finds out what you had.
   *
   * `firstSeen` is true the first time this card has been played at this table,
   * and is what decides how long the reveal is held for: a card nobody has seen
   * has to be *read*, not just recognised. See `ANIMATION_TTL_MS`.
   */
  | { type: 'gamblerPlayed'; playerId: string; card: Card; firstSeen?: boolean }
  /** A drawn card was handed straight on to somebody else — see "redirect". */
  | { type: 'redirected'; fromPlayerId: string; toPlayerId: string; card: Card }
  /**
   * A gambler card was stopped by another one. `returned` is the difference
   * between the two cards that do it: a nullified card is spent, one stopped by
   * a "nahhh" goes back to the hand it came from and can be played again.
   */
  | { type: 'gamblerCountered'; playerId: string; card: Card; returned?: boolean }
  /** ...and that is it arriving home. */
  | { type: 'gamblerReturned'; playerId: string; card: Card }
  /** A card aimed at somebody was turned round on whoever threw it. */
  | { type: 'gamblerDeflected'; playerId: string; toPlayerId: string; card: Card }
  /** Somebody shuffled the deck on purpose, rather than it running dry. */
  | { type: 'deckShuffled'; cards: number }
  | { type: 'roundScored'; deltas: Record<string, number>; winnerId?: string }

// ─── Socket protocol ───

// ─── Testing mode ───
// Only a server started with LETITRIDE_TEST_HOOKS=1 has any of this; everything
// below is inert against a real one, which ignores the message and sends no deck.

/**
 * One seat, as a patch. Only the fields that are sent are written, so moving a
 * score does not have to send back a hand it never touched. The seat is named by
 * `playerId`, or by `seat`, or by where it sits in the list.
 */
export interface DevPlayerPatch {
  playerId?: string
  seat?: number
  name?: string
  score?: number
  status?: PlayerStatus
  /** The whole hand, by card name — a number's face ("7"), or a def id ("freeze", "plus4"). */
  hand?: string[]
  /** The modifier row, same naming — the effect cards ("bomber") included. */
  passives?: string[]
  /** The hidden tray, same naming again ("nullify", "coolerRevive"). */
  gamblers?: string[]
  skipNextTurn?: boolean
}

/** Everything one dev command can change, applied to the table in one go. */
export interface DevSetup {
  /** The next cards off the deck, in the order they will be drawn. */
  stack?: string[]
  players?: DevPlayerPatch[]
  round?: number
  turnPlayerId?: string
  /** Drops whatever the table is stopped on — a prompt, a landed card, forced draws. */
  clearPrompt?: boolean
  /** Everybody still in goes out and the engine scores the round as it stands. */
  endRound?: boolean
  /** Cuts short the title card, the closing card and any animation being waited on. */
  skipWait?: boolean
}

export type ClientMessage =
  | { type: 'HIT' }
  | { type: 'STAY' }
  /**
   * `choice` answers the card's `PendingActionView.options`; omitted when it
   * asks nothing. `cards` carries the picks for a card that points at cards.
   */
  | { type: 'PLAY_ACTION'; targetPlayerId: string; cardDefId: string; choice?: string; cards?: string[] }
  /**
   * Offers a gambler card out of your own hidden hand. No target: if the card
   * wants one the server answers with an ordinary prompt, which every picker
   * here already knows how to answer.
   */
  | { type: 'PLAY_GAMBLER'; cardId: string }
  /** Declines an open response window — "let it stand". */
  | { type: 'PASS' }
  /** Takes one card off your own shelf, between rounds. */
  | { type: 'BUY'; offerId: string }
  /** "I'm finished" — and, once the auction lands, the sealed bid with it. */
  | { type: 'SHOP_DONE'; bid?: number }
  | { type: 'SET_CONFIG'; config: GameConfig }
  | { type: 'START_GAME' }
  | { type: 'NEXT_ROUND' }
  /** "again!" from the results screen — the host's, and it puts the whole
   *  table back in its own lobby rather than sending anybody home. */
  | { type: 'PLAY_AGAIN' }
  | { type: 'KICK'; playerId: string }
  | { type: 'ADD_BOT' }
  | { type: 'PING' }
  | { type: 'ANIM_DONE'; gateId: number }
  | { type: 'DEV'; setup: DevSetup }

export type ServerMessage =
  | { type: 'WELCOME'; playerId: string; roomCode: string; isHost: boolean }
  | { type: 'STATE'; state: GameStateView; events: GameEvent[] }
  | { type: 'ERROR'; message: string }
  | { type: 'KICKED' }
  | { type: 'PONG' }

/**
 * A round's points as they should read. Normally a gain, so a `+` is worth
 * printing — but "extreme" lets a round cost more than it paid, and `+-11` is
 * not a number anybody reads.
 */
export function signedPoints(points: number): string {
  return points < 0 ? `− ${-points}` : `+${points}`
}

// ─── Catalog ───

export interface ActionCardInfo {
  id: string
  name: string
  description: string
  sigil: string
  selfTarget: boolean
  /** The question this card asks its drawer, if any — see `PendingActionView.options`. */
  options?: string[]
  /**
   * False for a definition that is not a card — a house rule that asks a
   * question. It comes down so the prompt can be drawn, but it is never listed
   * among the cards and no deck contains it. Older servers omit it.
   */
  deckable?: boolean
  /** What it costs to buy outright — see the "mutate" card. */
  price?: number
}

/**
 * The stamp a sigil is struck in. Shape carries meaning before the writing is
 * read: a shield guards, a token pays, a scalloped stamp is the one on a
 * keepsake, and a spiked one is a card nobody wants.
 */
export type SealShape = 'circle' | 'hexagon' | 'shield' | 'scallop' | 'spike'

export interface PassiveCardInfo {
  id: string
  name: string
  description: string
  sigil: string
  bonusPoints: number
  scoring: 'flat' | 'double' | 'none' | 'voidUnlessFlip' | 'halve' | 'negate'
  /** The ink this card prints in. Older servers omit it; the house green stands in. */
  accent?: string
  /** The stamp its sigil is struck in. Older servers omit it; a plain ring stands in. */
  seal?: SealShape
  /** What it costs to buy outright — see the "mutate" card. Nought is not for sale. */
  price?: number
  /**
   * What the holder pays anybody who plays an action card on them — see the
   * "discordia" card. Nought for every card that is simply worth having.
   */
  spite?: number
  /**
   * False for a card no deck may contain: an effect minted by whatever causes
   * it. It comes down so the client can draw the face, and the deck builder
   * must not offer it. Older servers omit it, and everything was deckable.
   */
  deckable?: boolean
  /** False for a card whose holder may not choose to go out — see "antimatter". */
  allowsStaying?: boolean
}

export interface LobbyRuleInfo {
  id: string
  name: string
  description: string
}

export interface DeckEntryInfo {
  card: Card
  count: number
}

export interface DeckPresetInfo {
  id: string
  name: string
  description: string
  cardCount: number
  deck: DeckConfig
  contents: DeckEntryInfo[]
}

/**
 * A gambler card's face — rolling rules.
 *
 * Its own list rather than more entries among the passives: a gambler card is
 * not a modifier, and folding it in would change what everything already
 * reading `passives` is looking at.
 */
export interface GamblerCardInfo {
  id: string
  name: string
  description: string
  sigil: string
  rarity: GamblerRarity
  window: GamblerWindow
  /** The ink it prints in, and the stamp its sigil is struck in. */
  accent: string
  seal: SealShape
  /** What the shop charges for it. */
  price: number
  /** What playing it costs on top of that. Nought for all but one of them. */
  cost?: number
  selfTarget?: boolean
  options?: string[]
  /**
   * Whether a deck may hold it and a shop may sell it — `deckable`, said about a
   * gambler card. Older servers omit it, and omitting it means yes.
   */
  obtainable?: boolean
}

export interface Catalog {
  actions: ActionCardInfo[]
  passives: PassiveCardInfo[]
  /** Rolling rules' cards. Empty or absent on a server without the mode. */
  gamblers?: GamblerCardInfo[]
  rules: LobbyRuleInfo[]
  decks: DeckPresetInfo[]
  flip7Bonus: number
  flip7Target: number
  minPlayers: number
  maxPlayers: number
  /**
   * What a deck somebody builds has to be before a table will play it. The
   * server keeps these and enforces them; the builder only repeats them.
   * Older servers omit it, and the builder simply says nothing.
   */
  deckLimits?: {
    minNumberCards: number
    maxCards: number
    maxCopies: number
    maxSpecials: number
    /** Gambler cards a deck may hold, far fewer than the other specials. Older servers omit it. */
    maxGamblers?: number
    minNumberShare: number
  }
  /**
   * How fast the server is running the table, as a multiplier on every
   * animation the client times. 1 always, except under the end-to-end suite.
   * Older servers omit it.
   */
  pace?: number
  /**
   * Whether this server takes dev commands, and so whether the testing panel is
   * worth showing. False or absent everywhere but a local server started with
   * the hooks on.
   */
  testHooks?: boolean
}

// ─── Accounts ───
//
// All of this is optional in the strongest sense: a server with no identity
// provider configured answers `enabled: false` and nothing below ever arrives.
// Guests play the same game.

export interface Account {
  /** A short public handle. Not the identity provider's own id for somebody. */
  id: string
  name: string
  username?: string
  picture?: string
}

export interface AuthView {
  /** Whether this server can sign anybody in at all. */
  enabled: boolean
  /** What to put on the button — the operator's name for their provider. */
  provider: string
  /** Absent when nobody is signed in, which is the ordinary case. */
  account?: Account
}

/** A card and how often it turned up. `card` is a label or a definition id. */
export interface CardTally {
  card: string
  kind: CardKind
  count: number
}

export interface PlayerStats {
  account: Account
  games: number
  wins: number
  /** Games with somebody else at the table — the only ones the leaderboard counts. */
  rankedGames: number
  rankedWins: number
  /** Already worked out by the server; nothing here divides anything. */
  winRate: number
  averageScore: number
  bestScore: number
  rounds: number
  busts: number
  stays: number
  flip7s: number
  bustRate: number
  cardsDrawn: number
  bestRound: number
  mostDrawn: CardTally[]
  mostBustedTo: CardTally[]
  mostPlayed: CardTally[]
}

export interface LeaderboardRow {
  account: Account
  games: number
  wins: number
  winRate: number
  averageScore: number
  bestScore: number
}
