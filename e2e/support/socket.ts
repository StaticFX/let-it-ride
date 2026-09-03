import type { ClientMessage, GameStateView, ServerMessage } from './protocol'

/**
 * A bare websocket player. The browser specs cover what a person sees; this
 * covers what the server says — including the cases a real client never
 * produces, like joining a room that has already started.
 */
export class SocketPlayer {
  readonly received: ServerMessage[] = []

  private readonly listeners = new Set<(message: ServerMessage) => void>()
  private closeInfo: { code: number; reason: string } | null = null
  private readonly closeListeners = new Set<(info: { code: number; reason: string }) => void>()

  /**
   * A real client acks an animation gate the moment its animation ends; this
   * one has no animations, so it acks on arrival. Without it every gated step
   * would wait out the server's timeout and a spec would take minutes.
   *
   * Turn it off to assert on the timeout itself, or to hold the table open.
   */
  autoAck = true

  private constructor(
    private readonly socket: WebSocket,
    readonly playerId: string,
    readonly name: string,
  ) {}

  /**
   * Opens a socket and resolves once it is up. It resolves even when the server
   * refuses the player — the refusal itself is what several specs assert on —
   * so callers check [closed] or wait for the message they expect.
   */
  static async open(
    baseURL: string,
    roomCode: string,
    options: { playerId: string; name: string; autoAck?: boolean },
  ): Promise<SocketPlayer> {
    const url = new URL(`/ws/${encodeURIComponent(roomCode)}`, baseURL)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.searchParams.set('playerId', options.playerId)
    url.searchParams.set('name', options.name)

    const socket = new WebSocket(url)
    const player = new SocketPlayer(socket, options.playerId, options.name)
    player.autoAck = options.autoAck ?? true

    socket.addEventListener('message', (event) => {
      const message = JSON.parse(String(event.data)) as ServerMessage
      player.received.push(message)
      player.ackAnimation(message)
      for (const listener of player.listeners) listener(message)
    })
    socket.addEventListener('close', (event) => {
      player.closeInfo = { code: event.code, reason: event.reason }
      for (const listener of player.closeListeners) listener(player.closeInfo)
    })

    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out opening ${url}`)), 15_000)
      socket.addEventListener('open', () => { clearTimeout(timer); resolve() }, { once: true })
      // A room that refuses the player closes instead of ever opening.
      socket.addEventListener('close', () => { clearTimeout(timer); resolve() }, { once: true })
      socket.addEventListener('error', () => { clearTimeout(timer); resolve() }, { once: true })
    })

    return player
  }

  send(message: ClientMessage): void {
    this.socket.send(JSON.stringify(message))
  }

  /** Releases a gate this player owns, as a client with no animation would. */
  private ackAnimation(message: ServerMessage): void {
    if (!this.autoAck || message.type !== 'STATE') return
    const gate = message.state.animationGate
    if (!gate || gate.ackPlayerId !== this.playerId) return
    if (this.socket.readyState !== WebSocket.OPEN) return
    this.send({ type: 'ANIM_DONE', gateId: gate.id })
  }

  /** Sends a payload the typed protocol would not allow — for the specs that probe it. */
  sendRaw(payload: string): void {
    this.socket.send(payload)
  }

  get closed(): { code: number; reason: string } | null {
    return this.closeInfo
  }

  /** The most recent state this player was sent, if any. */
  get state(): GameStateView | null {
    for (let i = this.received.length - 1; i >= 0; i--) {
      const message = this.received[i]
      if (message.type === 'STATE') return message.state
    }
    return null
  }

  /**
   * Resolves with the first message — already received or still to come — that
   * matches. Checking the backlog first is what makes this safe to call after
   * an action rather than having to arm it beforehand.
   */
  waitFor<T extends ServerMessage>(
    predicate: (message: ServerMessage) => message is T,
    options?: { timeoutMs?: number; description?: string },
  ): Promise<T>
  waitFor(
    predicate: (message: ServerMessage) => boolean,
    options?: { timeoutMs?: number; description?: string },
  ): Promise<ServerMessage>
  waitFor(
    predicate: (message: ServerMessage) => boolean,
    options: { timeoutMs?: number; description?: string } = {},
  ): Promise<ServerMessage> {
    const { timeoutMs = 30_000, description = 'a matching message' } = options

    const existing = this.received.find(predicate)
    if (existing) return Promise.resolve(existing)

    return new Promise((resolve, reject) => {
      const finish = (result: ServerMessage) => {
        clearTimeout(timer)
        this.listeners.delete(listener)
        resolve(result)
      }
      const listener = (message: ServerMessage) => {
        if (predicate(message)) finish(message)
      }
      const timer = setTimeout(() => {
        this.listeners.delete(listener)
        reject(new Error(
          `${this.name} waited ${timeoutMs}ms for ${description} and never saw it. ` +
          `Last message: ${JSON.stringify(this.received.at(-1)?.type ?? 'none')}`,
        ))
      }, timeoutMs)
      this.listeners.add(listener)
    })
  }

  /** Waits for a state that satisfies [predicate]. */
  async waitForState(
    predicate: (state: GameStateView) => boolean,
    options: { timeoutMs?: number; description?: string } = {},
  ): Promise<GameStateView> {
    const message = await this.waitFor(
      (m): m is Extract<ServerMessage, { type: 'STATE' }> => m.type === 'STATE' && predicate(m.state),
      { description: options.description ?? 'a matching state', timeoutMs: options.timeoutMs },
    )
    return message.state
  }

  async waitForClose(timeoutMs = 15_000): Promise<{ code: number; reason: string }> {
    if (this.closeInfo) return this.closeInfo
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.closeListeners.delete(listener)
        reject(new Error(`${this.name}'s socket stayed open for ${timeoutMs}ms`))
      }, timeoutMs)
      const listener = (info: { code: number; reason: string }) => {
        clearTimeout(timer)
        this.closeListeners.delete(listener)
        resolve(info)
      }
      this.closeListeners.add(listener)
    })
  }

  close(): void {
    this.listeners.clear()
    this.closeListeners.clear()
    if (this.socket.readyState <= WebSocket.OPEN) this.socket.close()
  }
}
