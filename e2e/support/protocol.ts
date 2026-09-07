/**
 * The wire contract, restated independently of the client.
 *
 * These are deliberately not imported from `frontend/src/game/types.ts`: the
 * point of the protocol specs is to check that the *server* still speaks the
 * shape both sides agreed on. Sharing the client's types would let a change to
 * both sides pass unnoticed.
 */

export type GamePhase = 'LOBBY' | 'PLAYING' | 'ROUND_END' | 'GAME_END'
export type PlayerStatus = 'active' | 'stayed' | 'bust'
export type CardKind = 'number' | 'action' | 'passive'
export type WinCondition = 'rounds' | 'first_to_score'

export interface Card {
  id: string
  kind: CardKind
  label: string
  value: number
  defId?: string
  suit?: string
}

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
  /** Round-long effects this player is under — see the catalog's `marks`. */
  marks?: string[]
}

export interface DeckConfig {
  numberCards: { value: number; count: number; label?: string; suits?: string[] }[]
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
  /** Seconds before the next round deals itself, or null to wait for the host. */
  autoNextRoundSeconds?: number | null
}

export interface PendingActionView {
  cardDefId: string
  playerId: string
  cardId: string
  validTargets: string[]
  /** The question the card asks its drawer, if it asks one. */
  options?: string[]
  /** What is being pointed at: a seat, or cards off the table. */
  kind?: 'player' | 'card' | 'catalog'
  /** The cards that may be picked, when `kind` is `card`. */
  validCards?: string[]
  /** How many picks are owed before the card resolves. */
  picks?: number
  /** What is for sale, when `kind` is `catalog`. */
  offers?: { id: string; price: number; card: Card }[]
  /** Why the table is stopped; "play" is a card that was just drawn. */
  phase?: string
  /** Everybody who owes an answer — one name for nearly every prompt. */
  responders?: string[]
  /** Who has answered. What they said is never sent while the prompt is open. */
  answered?: string[]
}

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
  forcedDraws?: { playerId: string; remaining: number; source?: string }
  dealQueue: string[]
  roundWinnerId?: string
  gameWinnerId?: string
  flip7PlayerId?: string
  roundDeltas: Record<string, number>
  /** Points moved by something other than hand scoring; already inside the deltas. */
  roundAdjustments?: Record<string, number>
  turnDeadline?: number
  roundIntroUntil?: number
  roundOutroFrom?: number
  roundOutroUntil?: number
  animationGate?: AnimationGate
  /** Epoch millis the next round deals itself, under the host's autostart setting. */
  nextRoundAt?: number

  // ─── Rolling rules ───
  /**
   * How many gambler cards each seat is carrying. Public on every seat; the
   * faces are not, and the absence of anybody else's is the point.
   */
  gamblerCounts?: Record<string, number>
  gamblerLimits?: Record<string, number>
  /** The viewer's own, face up. Every other seat's copy of this state has theirs. */
  myGamblers?: Card[]
  /** Which of them the server says are legal right now. The client decides nothing. */
  playableGamblers?: string[]
  gamblerReserveCount?: number
  /** Cards in flight, oldest first. */
  responseStack?: {
    id: number
    cardDefId: string
    card: Card
    playerId: string
    targetId: string
    cancelled?: boolean
  }[]
  /** Names only — never what anybody said, and never who is holding what. */
  responseWindow?: { frameId: number; responders?: string[]; awaiting?: string[] }
}

export type ServerMessage =
  | { type: 'WELCOME'; playerId: string; roomCode: string; isHost: boolean }
  | { type: 'STATE'; state: GameStateView; events: GameEvent[] }
  | { type: 'ERROR'; message: string }
  | { type: 'KICKED' }
  | { type: 'PONG' }

export interface GameEvent {
  type: string
  [key: string]: unknown
}

export type ClientMessage =
  | { type: 'HIT' }
  | { type: 'STAY' }
  | { type: 'PLAY_ACTION'; targetPlayerId: string; cardDefId: string; choice?: string; cards?: string[] }
  | { type: 'SET_CONFIG'; config: GameConfig }
  | { type: 'START_GAME' }
  | { type: 'NEXT_ROUND' }
  | { type: 'KICK'; playerId: string }
  | { type: 'ADD_BOT' }
  | { type: 'PING' }
  | { type: 'ANIM_DONE'; gateId: number }
  // ─── Rolling rules. Restated here, like everything else, so that a change to
  // both sides at once cannot pass unnoticed. ───
  | { type: 'PLAY_GAMBLER'; cardId: string }
  | { type: 'PASS' }

export interface CatalogResponse {
  actions: {
    id: string
    name: string
    description: string
    sigil: string
    selfTarget: boolean
    /** False for a card no deck may hold. Older servers omit it, and all were deckable. */
    deckable?: boolean
  }[]
  passives: {
    id: string
    name: string
    description: string
    sigil: string
    bonusPoints: number
    scoring: string
    /**
     * False for an effect minted by whatever causes it rather than dealt — the
     * flip you cannot take, the bomb you are carrying. It still comes down with
     * a face to draw, because everything in this game is a card. Older servers
     * omit it, and everything was deckable.
     */
    deckable?: boolean
  }[]
  rules: { id: string; name: string; description: string }[]
  /** Round-long effects a player can be put under. Older servers omit it. */
  marks?: { id: string; name: string; description: string; sigil: string }[]
  decks: {
    id: string
    name: string
    description: string
    cardCount: number
    deck: DeckConfig
    contents: { card: Card; count: number }[]
  }[]
  flip7Bonus: number
  flip7Target: number
  minPlayers: number
  maxPlayers: number
}
