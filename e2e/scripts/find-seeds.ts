/**
 * Finds seeds that reproduce a scenario, so a spec can assert on it instead of
 * hoping for it.
 *
 * A room's shuffles come from its seed, and every other input is fixed — the
 * players sit down in the same order and the local player follows the same
 * policy — so a seed that produced a bust once produces it every time.
 *
 * Run it against a server with the test hooks on (the suite's own harness):
 *
 *   E2E_SKIP_BUILD=1 bun run test tests/api.spec.ts   # to get a server up, or
 *   PORT=8099 LETITRIDE_TEST_HOOKS=1 java -jar backend/build/libs/let-it-ride.jar
 *   node --experimental-strip-types scripts/find-seeds.ts
 *
 * The seeds it prints belong in support/seeds.ts. This is a tool, not a test:
 * nothing in the suite runs it.
 */
import type { ClientMessage, GameStateView, ServerMessage } from '../support/protocol'

const BASE = process.env.E2E_BASE_URL ?? `http://127.0.0.1:${process.env.E2E_PORT ?? 8099}`
const BOTS = 3

type Outcome = {
  seed: number
  localDrewAction: boolean
  /**
   * What the local player's *first* prompt asked for. A spec that clicks a seat
   * needs 'seat'; one that answers a question needs 'choice'. Null when the
   * round never handed them one.
   */
  localFirstPrompt: 'seat' | 'choice' | 'cards' | 'shop' | null
  /** A bot was left holding a card that points at a seat, with the round still on. */
  botDrewSeatAction: boolean
  localBusted: boolean
  anyoneBusted: boolean
  flip7: boolean
  roundEnded: boolean
}

async function createRoom(seed: number): Promise<string> {
  const response = await fetch(`${BASE}/api/rooms`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'devin', seed }),
  })
  if (!response.ok) throw new Error(`POST /api/rooms → ${response.status}`)
  return (await response.json()).roomCode
}

/** Plays one round the way the browser specs do and reports what happened. */
function playOneRound(seed: number, deck: string, code: string): Promise<Outcome> {
  return new Promise((resolve, reject) => {
    const url = new URL(`/ws/${code}`, BASE)
    url.protocol = 'ws:'
    url.searchParams.set('playerId', 'seed-finder')
    url.searchParams.set('name', 'devin')

    const socket = new WebSocket(url)
    const send = (message: ClientMessage) => socket.send(JSON.stringify(message))
    const outcome: Outcome = {
      seed,
      localDrewAction: false,
      localFirstPrompt: null,
      botDrewSeatAction: false,
      localBusted: false,
      anyoneBusted: false,
      flip7: false,
      roundEnded: false,
    }

    let bots = 0
    let started = false
    const timer = setTimeout(() => { socket.close(); resolve(outcome) }, 120_000)

    socket.addEventListener('message', (event) => {
      const message = JSON.parse(String(event.data)) as ServerMessage
      if (message.type !== 'STATE') return
      const state: GameStateView = message.state

      for (const gameEvent of message.events) {
        if (gameEvent.type === 'bust') {
          outcome.anyoneBusted = true
          if (gameEvent.playerId === 'seed-finder') outcome.localBusted = true
        }
        if (gameEvent.type === 'flip7') outcome.flip7 = true
      }

      if (state.phase === 'LOBBY') {
        if (bots < BOTS) {
          bots++
          send({ type: 'ADD_BOT' })
          return
        }
        if (state.config.deckPresetId !== deck) {
          const preset = presets.get(deck)
          if (!preset) throw new Error(`unknown deck ${deck}`)
          send({ type: 'SET_CONFIG', config: { ...state.config, deckPresetId: deck, deck: preset, turnTimeSeconds: 120 } })
          return
        }
        if (!started) { started = true; send({ type: 'START_GAME' }) }
        return
      }

      if (state.phase !== 'PLAYING') {
        outcome.roundEnded = true
        clearTimeout(timer)
        socket.close()
        resolve(outcome)
        return
      }

      const pending = state.pendingAction
      if (pending?.playerId === 'seed-finder') {
        outcome.localDrewAction = true
        outcome.localFirstPrompt ??=
          pending.kind === 'card' ? 'cards'
            : pending.kind === 'catalog' ? 'shop'
              : pending.options?.length ? 'choice' : 'seat'
        const target = pending.validTargets.find((id) => id !== 'seed-finder') ?? pending.validTargets[0]
        if (target) send({ type: 'PLAY_ACTION', targetPlayerId: target, cardDefId: pending.cardDefId })
        return
      }
      // Somebody else is on the picker. Only a seat-targeting card counts: a
      // card-picking one puts a different prompt on screen.
      if (pending && pending.kind !== 'card') outcome.botDrewSeatAction = true
      if (pending || state.forcedDraws || state.dealQueue.length > 0) return
      if (state.roundIntroUntil && Date.now() < state.roundIntroUntil) return

      // Always hit: the fastest way to find out what the deck is holding.
      if (state.players[state.turnIndex]?.id === 'seed-finder') send({ type: 'HIT' })
    })

    socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error(`socket error on ${code}`)) })
    socket.addEventListener('close', () => { clearTimeout(timer); resolve(outcome) })
  })
}

const presets = new Map<string, unknown>()

async function loadPresets(): Promise<void> {
  const catalog = await (await fetch(`${BASE}/api/catalog`)).json()
  for (const deck of catalog.decks) presets.set(deck.id, deck.deck)
}

async function main(): Promise<void> {
  const deck = process.argv[2] ?? 'chaos'
  const first = Number(process.argv[3] ?? 1)
  const count = Number(process.argv[4] ?? 24)
  const batch = 8

  await loadPresets()
  console.log(`searching seeds ${first}..${first + count - 1} on the ${deck} deck\n`)

  const results: Outcome[] = []
  for (let start = first; start < first + count; start += batch) {
    const seeds = Array.from({ length: Math.min(batch, first + count - start) }, (_, i) => start + i)
    const outcomes = await Promise.all(seeds.map(async (seed) => {
      const code = await createRoom(seed)
      return playOneRound(seed, deck, code)
    }))
    for (const outcome of outcomes) {
      results.push(outcome)
      console.log(
        `seed ${String(outcome.seed).padStart(5)}  ` +
        `localFirst=${(outcome.localFirstPrompt ?? '-').padEnd(6)}  ` +
        `botSeatAction=${outcome.botDrewSeatAction ? 'yes' : ' no'}  ` +
        `localBust=${outcome.localBusted ? 'yes' : ' no'}  ` +
        `anyBust=${outcome.anyoneBusted ? 'yes' : ' no'}  ` +
        `flip7=${outcome.flip7 ? 'yes' : ' no'}`,
      )
    }
  }

  const pick = (label: string, predicate: (o: Outcome) => boolean) => {
    const hits = results.filter(predicate).map((o) => o.seed)
    console.log(`\n${label}: ${hits.length ? hits.join(', ') : 'none in this range'}`)
  }

  pick('the local player draws an action card in round 1', (o) => o.localDrewAction)
  pick("the local player's first prompt is a seat pick", (o) => o.localFirstPrompt === 'seat')
  pick("...and it's a card pick", (o) => o.localFirstPrompt === 'cards')
  pick('...and it is the shop', (o) => o.localFirstPrompt === 'shop')
  pick('a bot is left picking a seat', (o) => o.botDrewSeatAction)
  pick(
    'seat pick for me, a card for a bot, and nobody busts',
    (o) => o.localFirstPrompt === 'seat' && o.botDrewSeatAction && !o.anyoneBusted,
  )
  pick('the local player busts in round 1', (o) => o.localBusted)
  pick('somebody busts in round 1', (o) => o.anyoneBusted)
  pick('a flip 7 lands in round 1', (o) => o.flip7)
}

await main()
