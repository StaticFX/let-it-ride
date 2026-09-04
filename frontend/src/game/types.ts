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
  /**
   * The next cards off the deck, in draw order — only ever sent by a server
   * running the testing mode, and the reason the dev panel can show what is
   * coming. Absent everywhere else, which is every server anybody plays on.
   */
  devDeck?: Card[]
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
   * `points` moved from one player to another mid-round — a toll rather than
   * anything the hands did. Both halves are already in the round's adjustments;
   * this is the announcement, so the table can watch the points cross it.
   */
  | { type: 'pointsTransferred'; fromPlayerId: string; toPlayerId: string; points: number }
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
  skipNextTurn?: boolean
}

/** Everything one dev command can change, applied to the table in one go. */
export interface DevSetup {
  /** The next cards off the deck, in the order they will be drawn. */
  stack?: string[]
  players?: DevPlayerPatch[]
  round?: number
  turnPlayerId?: string
  /** Drops whatever the table is stopped on — a prompt, a run of forced draws. */
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
  | { type: 'SET_CONFIG'; config: GameConfig }
  | { type: 'START_GAME' }
  | { type: 'NEXT_ROUND' }
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

export interface PassiveCardInfo {
  id: string
  name: string
  description: string
  sigil: string
  bonusPoints: number
  scoring: 'flat' | 'double' | 'none' | 'voidUnlessFlip' | 'halve'
  /** The ink this card prints in. Older servers omit it; the house green stands in. */
  accent?: string
  /** The stamp its sigil is struck in. Older servers omit it; a plain ring stands in. */
  seal?: 'circle' | 'hexagon' | 'shield' | 'scallop' | 'spike'
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

export interface Catalog {
  actions: ActionCardInfo[]
  passives: PassiveCardInfo[]
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
