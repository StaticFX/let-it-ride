import { create } from 'zustand'
import type {
  ActionCardInfo,
  Catalog,
  GameEvent,
  GameStateView,
  LobbyRuleInfo,
  PassiveCardInfo,
  ServerMessage,
} from '../game/types'

export type ConnectionStatus = 'idle' | 'connecting' | 'connected' | 'disconnected'

export interface GameStore {
  catalog: Catalog | null
  state: GameStateView | null
  /** The most recent batch of events, with a counter so effects can tell batches apart. */
  events: GameEvent[]
  eventSeq: number
  localPlayerId: string | null
  roomCode: string | null
  isHost: boolean
  connection: ConnectionStatus
  error: string | null
  kicked: boolean

  setCatalog: (catalog: Catalog) => void
  setConnection: (status: ConnectionStatus, error?: string | null) => void
  setError: (error: string | null) => void
  applyServerMessage: (message: ServerMessage) => void
  reset: () => void
}

const emptySession = {
  state: null,
  events: [] as GameEvent[],
  localPlayerId: null,
  roomCode: null,
  isHost: false,
  connection: 'idle' as ConnectionStatus,
  error: null,
  kicked: false,
}

export const useGameStore = create<GameStore>()((set, get) => ({
  catalog: null,
  eventSeq: 0,
  ...emptySession,

  setCatalog: (catalog) => set({ catalog }),

  setConnection: (connection, error) =>
    set((s) => ({ connection, error: error === undefined ? s.error : error })),

  setError: (error) => set({ error }),

  applyServerMessage: (message) => {
    switch (message.type) {
      case 'WELCOME':
        set({
          localPlayerId: message.playerId,
          roomCode: message.roomCode,
          isHost: message.isHost,
          connection: 'connected',
          error: null,
          kicked: false,
        })
        break
      case 'STATE':
        set((s) => ({
          state: message.state,
          isHost: message.state.hostId === s.localPlayerId,
          events: message.events,
          eventSeq: s.eventSeq + 1,
        }))
        break
      case 'ERROR':
        set({ error: message.message })
        break
      case 'KICKED':
        set({ kicked: true, error: 'the host removed you from the game', connection: 'disconnected' })
        break
      case 'PONG':
        break
    }
  },

  reset: () => set({ ...emptySession, eventSeq: get().eventSeq + 1 }),
}))

// ─── Catalog lookups ───
// The backend owns card behaviour; the client only ever needs the face.

export function useCatalog(): Catalog | null {
  return useGameStore((s) => s.catalog)
}

export function findAction(catalog: Catalog | null, defId?: string): ActionCardInfo | undefined {
  if (!catalog || !defId) return undefined
  return catalog.actions.find((a) => a.id === defId)
}

export function findPassive(catalog: Catalog | null, defId?: string): PassiveCardInfo | undefined {
  if (!catalog || !defId) return undefined
  return catalog.passives.find((p) => p.id === defId)
}

export function findRule(catalog: Catalog | null, id: string): LobbyRuleInfo | undefined {
  return catalog?.rules.find((r) => r.id === id)
}

export function findDeck(catalog: Catalog | null, id?: string) {
  if (!catalog) return undefined
  return catalog.decks.find((d) => d.id === id) ?? catalog.decks[0]
}
