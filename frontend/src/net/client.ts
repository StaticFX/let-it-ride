import { useGameStore } from '../state/gameStore'
import type { Catalog, ClientMessage, ServerMessage } from '../game/types'

/**
 * Talks to the Kotlin backend. In production the backend also serves this
 * bundle, so everything is same-origin; in dev Vite proxies /api and /ws.
 */

const RECONNECT_DELAYS_MS = [500, 1000, 2000, 4000, 8000]

let socket: WebSocket | null = null
let session: { roomCode: string; playerId: string; name: string } | null = null
let reconnectAttempt = 0
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let intentionalClose = false

async function json<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error((body as { error?: string } | null)?.error ?? 'something went wrong')
  }
  return body as T
}

export async function fetchCatalog(): Promise<Catalog> {
  const catalog = await json<Catalog>(await fetch('/api/catalog'))
  useGameStore.getState().setCatalog(catalog)
  return catalog
}

/**
 * Opens a table. `seed` and `stack` are the testing mode's — a real server drops
 * both on the floor, so passing them costs nothing and changes nothing there.
 */
export async function createRoom(
  name: string,
  options: { seed?: number; stack?: string[] } = {},
): Promise<{ roomCode: string; playerId: string }> {
  return json(
    await fetch('/api/rooms', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, ...options }),
    }),
  )
}

export async function lookupRoom(roomCode: string) {
  return json<{ roomCode: string; players: number; phase: string; joinable: boolean }>(
    await fetch(`/api/rooms/${encodeURIComponent(roomCode)}`),
  )
}

function socketUrl(roomCode: string, playerId: string, name: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const params = new URLSearchParams({ playerId, name })
  return `${protocol}//${window.location.host}/ws/${encodeURIComponent(roomCode)}?${params}`
}

export function connect(roomCode: string, playerId: string, name: string): void {
  disconnect()
  session = { roomCode, playerId, name }
  intentionalClose = false
  reconnectAttempt = 0
  watchVisibility()
  open()
}

/**
 * Tries again from the top of the ladder.
 *
 * The ladder is five attempts over about fifteen seconds and then permanent,
 * which is right for a desktop that has genuinely lost the server and wrong for
 * a phone, where fifteen seconds is a tunnel, a lift, or somebody answering a
 * message. The seat is still held at the far end — [Rooms] keeps it for a
 * player who comes back — so giving up is a decision about this browser and not
 * about the table, and it should be undoable.
 */
export function retryConnection(): void {
  if (!session || socket?.readyState === WebSocket.OPEN) return
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  intentionalClose = false
  reconnectAttempt = 0
  open()
}

/**
 * A phone that comes back to the front of the queue tries again immediately.
 *
 * Backgrounding a tab on iOS suspends its socket outright, and the timers that
 * would have retried along with it — so a player who switched apps for half a
 * minute came back to a dead table with a "lost the connection" card over it
 * and the ladder already spent. The moment the page is visible again is the
 * only moment worth retrying on, and it is free.
 *
 * Installed once for the life of the page rather than per connection; there is
 * only ever one socket, and it reads its state at the time it fires.
 */
let watchingVisibility = false
function watchVisibility(): void {
  if (watchingVisibility || typeof document === 'undefined') return
  watchingVisibility = true
  document.addEventListener('visibilitychange', () => {
    if (document.hidden || !session || intentionalClose) return
    if (useGameStore.getState().kicked) return
    if (socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) return
    retryConnection()
  })
}

function open(): void {
  if (!session) return
  const store = useGameStore.getState()
  store.setConnection('connecting', null)

  const ws = new WebSocket(socketUrl(session.roomCode, session.playerId, session.name))
  socket = ws

  ws.onopen = () => {
    reconnectAttempt = 0
  }

  ws.onmessage = (event) => {
    let message: ServerMessage
    try {
      message = JSON.parse(event.data as string) as ServerMessage
    } catch {
      return
    }
    useGameStore.getState().applyServerMessage(message)
  }

  ws.onclose = () => {
    if (socket === ws) socket = null
    if (intentionalClose || useGameStore.getState().kicked) {
      useGameStore.getState().setConnection('disconnected')
      return
    }
    scheduleReconnect()
  }

  ws.onerror = () => {
    // `onclose` always follows, and that is where reconnecting is handled.
  }
}

function scheduleReconnect(): void {
  const store = useGameStore.getState()
  const delay = RECONNECT_DELAYS_MS[Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1)]
  reconnectAttempt += 1

  if (reconnectAttempt > RECONNECT_DELAYS_MS.length) {
    store.setConnection('disconnected', 'lost the connection to the table')
    return
  }

  store.setConnection('connecting', null)
  reconnectTimer = setTimeout(open, delay)
}

export function send(message: ClientMessage): void {
  if (socket?.readyState !== WebSocket.OPEN) return
  socket.send(JSON.stringify(message))
}

export function disconnect(): void {
  intentionalClose = true
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  socket?.close()
  socket = null
  session = null
}

export function leaveGame(): void {
  disconnect()
  useGameStore.getState().reset()
}
