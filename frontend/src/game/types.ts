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
}

// ─── State ───

export type GamePhase = 'LOBBY' | 'PLAYING' | 'ROUND_END' | 'GAME_END'

export interface PendingActionView {
  cardDefId: string
  playerId: string
}

export interface ForcedDraws {
  playerId: string
  remaining: number
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
  roundDeltas: Record<string, number>
  /** Epoch millis the current actor runs out of time, if they are on a clock. */
  turnDeadline?: number
}

// ─── Events ───

export type GameEvent =
  | { type: 'draw'; playerId: string; card: Card }
  | { type: 'passive'; playerId: string; card: Card }
  | { type: 'bust'; playerId: string; reason: string }
  | { type: 'stay'; playerId: string }
  | { type: 'skip'; playerId: string }
  | { type: 'discard'; playerId: string; card: Card }
  | { type: 'steal'; fromPlayerId: string; toPlayerId: string; card: Card }
  | { type: 'swap'; fromPlayerId: string; toPlayerId: string }
  | { type: 'freeze'; playerId: string }
  | { type: 'actionPlayed'; cardDefId: string; fromPlayerId: string; targetPlayerId: string }
  | { type: 'secondChance'; playerId: string; card: Card }
  | { type: 'secondChancePassed'; fromPlayerId: string; toPlayerId: string }
  | { type: 'flip7'; playerId: string }
  | { type: 'doubleOrNothing'; playerId: string; won: boolean }
  | { type: 'slots'; playerId: string }
  | { type: 'timeout'; playerId: string }
  | { type: 'deckReshuffled'; cards: number }
  | { type: 'roundScored'; deltas: Record<string, number>; winnerId?: string }

// ─── Socket protocol ───

export type ClientMessage =
  | { type: 'HIT' }
  | { type: 'STAY' }
  | { type: 'PLAY_ACTION'; targetPlayerId: string; cardDefId: string }
  | { type: 'SET_CONFIG'; config: GameConfig }
  | { type: 'START_GAME' }
  | { type: 'NEXT_ROUND' }
  | { type: 'KICK'; playerId: string }
  | { type: 'ADD_BOT' }
  | { type: 'PING' }

export type ServerMessage =
  | { type: 'WELCOME'; playerId: string; roomCode: string; isHost: boolean }
  | { type: 'STATE'; state: GameStateView; events: GameEvent[] }
  | { type: 'ERROR'; message: string }
  | { type: 'KICKED' }
  | { type: 'PONG' }

// ─── Catalog ───

export interface ActionCardInfo {
  id: string
  name: string
  description: string
  sigil: string
  selfTarget: boolean
}

export interface PassiveCardInfo {
  id: string
  name: string
  description: string
  sigil: string
  bonusPoints: number
  scoring: 'flat' | 'double' | 'none'
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
}
