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
}

export interface PendingActionView {
  cardDefId: string
  playerId: string
  cardId: string
  validTargets: string[]
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
  turnDeadline?: number
  roundIntroUntil?: number
  roundOutroFrom?: number
  roundOutroUntil?: number
  animationGate?: AnimationGate
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
  | { type: 'PLAY_ACTION'; targetPlayerId: string; cardDefId: string }
  | { type: 'SET_CONFIG'; config: GameConfig }
  | { type: 'START_GAME' }
  | { type: 'NEXT_ROUND' }
  | { type: 'KICK'; playerId: string }
  | { type: 'ADD_BOT' }
  | { type: 'PING' }
  | { type: 'ANIM_DONE'; gateId: number }

export interface CatalogResponse {
  actions: { id: string; name: string; description: string; sigil: string; selfTarget: boolean }[]
  passives: {
    id: string
    name: string
    description: string
    sigil: string
    bonusPoints: number
    scoring: string
  }[]
  rules: { id: string; name: string; description: string }[]
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
