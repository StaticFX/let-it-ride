/**
 * Mirrors the wire types the Kotlin backend serialises. The backend owns every
 * rule; nothing in here decides anything about the game.
 */

// ─── Cards ───

export type CardKind = 'number' | 'action' | 'passive'

export interface Card {
  id: string
  kind: CardKind
  /** What is printed on the card, and the key duplicates are matched on. */
  label: string
  value: number
  defId?: string
  suit?: string
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
  /**
   * Effects this player is under for the rest of the round — see `MarkInfo`.
   * A mark is not a card: it cannot be stolen, swapped or scored, and it is
   * wiped when the next round is dealt. Older servers omit the field.
   */
  marks?: string[]
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
}

export interface GameConfig {
  deckPresetId: string
  deck: DeckConfig
  ruleIds: string[]
  winCondition: WinCondition
  totalRounds: number
  targetScore: number
  turnTimeSeconds: number
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
  forcedDraws?: ForcedDraws
  dealQueue: string[]
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
}

// ─── Events ───

export type GameEvent =
  | { type: 'draw'; playerId: string; card: Card }
  | { type: 'passive'; playerId: string; card: Card }
  | { type: 'bust'; playerId: string; reason: string; card?: Card; matched?: Card }
  | { type: 'stay'; playerId: string }
  | { type: 'skip'; playerId: string }
  | { type: 'discard'; playerId: string; card: Card }
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
   * `playerId` came under `markId` for the rest of the round. Only sent when
   * the mark is new — marking someone who already carries it announces nothing.
   */
  | { type: 'marked'; playerId: string; markId: string }
  | { type: 'actionPlayed'; cardDefId: string; fromPlayerId: string; targetPlayerId: string }
  | { type: 'secondChance'; playerId: string; card: Card; matched?: Card }
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
   * until now. `challengerWon` settles it — a draw is neither.
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
  | { type: 'bought'; playerId: string; card: Card; price: number }
  | { type: 'roundScored'; deltas: Record<string, number>; winnerId?: string }

// ─── Socket protocol ───

export type ClientMessage =
  | { type: 'HIT' }
  | { type: 'STAY' }
  /**
   * `choice` answers the card's `PendingActionView.options`; omitted when it
   * asks nothing. `cards` carries the picks for a card that points at cards.
   */
  | { type: 'PLAY_ACTION'; targetPlayerId: string; cardDefId: string; choice?: string; cards?: string[] }
  | { type: 'SET_CONFIG'; config: GameConfig }
  | { type: 'START_GAME' }
  | { type: 'NEXT_ROUND' }
  | { type: 'KICK'; playerId: string }
  | { type: 'ADD_BOT' }
  | { type: 'PING' }
  | { type: 'ANIM_DONE'; gateId: number }

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

export interface PassiveCardInfo {
  id: string
  name: string
  description: string
  sigil: string
  bonusPoints: number
  scoring: 'flat' | 'double' | 'none'
  /** The ink this card prints in. Older servers omit it; the house green stands in. */
  accent?: string
  /** The stamp its sigil is struck in. Older servers omit it; a plain ring stands in. */
  seal?: 'circle' | 'hexagon' | 'shield' | 'scallop'
  /** What it costs to buy outright — see the "mutate" card. */
  price?: number
}

export interface LobbyRuleInfo {
  id: string
  name: string
  description: string
}

/**
 * An effect a player carries for the rest of a round. Marks arrive on the
 * player rather than as cards, so their faces come from the catalog the same
 * way card faces do.
 */
export interface MarkInfo {
  id: string
  name: string
  description: string
  sigil: string
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

export interface Catalog {
  actions: ActionCardInfo[]
  passives: PassiveCardInfo[]
  rules: LobbyRuleInfo[]
  /** Older servers omit this; nothing renders a mark it cannot name. */
  marks?: MarkInfo[]
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
    minNumberShare: number
  }
  /**
   * How fast the server is running the table, as a multiplier on every
   * animation the client times. 1 always, except under the end-to-end suite.
   * Older servers omit it.
   */
  pace?: number
}
