import type { APIRequestContext } from '@playwright/test'
import type { CatalogResponse, GamePhase } from './protocol'

export interface HealthResponse {
  status: string
  rooms: number
  testHooks: boolean
}

export interface CreateRoomResponse {
  roomCode: string
  playerId: string
}

export interface RoomInfoResponse {
  roomCode: string
  players: number
  phase: GamePhase
  joinable: boolean
}

export class Api {
  constructor(private readonly request: APIRequestContext) {}

  async health(): Promise<HealthResponse> {
    const response = await this.request.get('/api/health')
    if (!response.ok()) throw new Error(`/api/health returned ${response.status()}`)
    return response.json() as Promise<HealthResponse>
  }

  async catalog(): Promise<CatalogResponse> {
    const response = await this.request.get('/api/catalog')
    if (!response.ok()) throw new Error(`/api/catalog returned ${response.status()}`)
    return response.json() as Promise<CatalogResponse>
  }

  /**
   * Opens a room. [seed] fixes its shuffles, which the server only honours when
   * it was started with LETITRIDE_TEST_HOOKS=1 — as this suite's server is.
   */
  async createRoom(name: string, seed?: number): Promise<CreateRoomResponse> {
    const response = await this.request.post('/api/rooms', {
      data: seed === undefined ? { name } : { name, seed },
    })
    if (!response.ok()) throw new Error(`POST /api/rooms returned ${response.status()}`)
    return response.json() as Promise<CreateRoomResponse>
  }

  async roomInfo(code: string): Promise<RoomInfoResponse> {
    const response = await this.request.get(`/api/rooms/${encodeURIComponent(code)}`)
    if (!response.ok()) throw new Error(`GET /api/rooms/${code} returned ${response.status()}`)
    return response.json() as Promise<RoomInfoResponse>
  }
}
