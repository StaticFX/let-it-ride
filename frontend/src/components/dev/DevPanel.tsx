import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useGameStore } from '../../state/gameStore'
import { connect, createRoom, leaveGame, send } from '../../net/client'
import type { Card, DevPlayerPatch, DevSetup, Player, PlayerStatus } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'
import { DevCardPicker } from './DevCardPicker'
import { buildPalette, buildScenarios, cardName } from './devSetup'

/**
 * The local testing panel: set the table up however you need to see something,
 * and say which cards come next.
 *
 * It exists only against a server started with `LETITRIDE_TEST_HOOKS=1` — the
 * catalog says whether that server takes dev commands, and nothing below is
 * rendered when it does not. A published build still ships the code; what it
 * cannot do is find a server that will listen to it.
 *
 * Everything it sends is one `DEV` message, and the server is what turns it into
 * a table. See `DevSetup` and the backend's `DevMode`.
 */

const OPEN_KEY = 'let-it-ride:dev-panel'
const NAME_KEY = 'let-it-ride:name'
/** Table sizes worth one click — a table of five is the most the game seats. */
const BOT_COUNTS = [1, 2, 3, 4]

type Tab = 'cards' | 'players' | 'table'

/** Which control has the card picker open, if any. */
type Picking =
  | { kind: 'stack' }
  | { kind: 'hand'; playerId: string }
  | { kind: 'passives'; playerId: string }
  | null

const STATUSES: PlayerStatus[] = ['active', 'stayed', 'bust']

export function DevPanel() {
  const available = useGameStore((s) => s.catalog?.testHooks === true)
  const catalog = useGameStore((s) => s.catalog)
  const state = useGameStore((s) => s.state)
  const connection = useGameStore((s) => s.connection)
  const meId = useGameStore((s) => s.localPlayerId)

  const [open, setOpen] = useState(() => localStorage.getItem(OPEN_KEY) === '1')
  const [tab, setTab] = useState<Tab>('cards')
  const [staged, setStaged] = useState<string[]>([])
  const [picking, setPicking] = useState<Picking>(null)
  /** How many seats a table being opened is filling to; zero when none is. */
  const seatsWanted = useRef(0)

  const phase = state?.phase
  const seats = state?.players.length ?? 0
  const opening = connection === 'connecting'

  useEffect(() => {
    localStorage.setItem(OPEN_KEY, open ? '1' : '0')
  }, [open])

  // Backtick, because nothing else in the game wants it and it is one key away
  // from where a hand already is.
  useEffect(() => {
    if (!available) return
    function onKey(event: KeyboardEvent) {
      if (event.key !== '`') return
      const target = event.target as HTMLElement | null
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return
      event.preventDefault()
      setOpen((was) => !was)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [available])

  // A table fills one seat per state update, the way the lobby does it: the
  // server confirms each bot before the next is asked for, and the game starts
  // once they are all sitting down.
  useEffect(() => {
    // The welcome lands before the first state does, so "no phase yet" is a
    // table that has not spoken rather than one that is already playing.
    if (seatsWanted.current <= 0 || connection !== 'connected' || !phase) return
    if (phase !== 'LOBBY') {
      seatsWanted.current = 0
      return
    }
    if (seats < seatsWanted.current) {
      send({ type: 'ADD_BOT' })
      return
    }
    seatsWanted.current = 0
    send({ type: 'START_GAME' })
  }, [connection, phase, seats])

  const apply = useCallback((setup: DevSetup) => send({ type: 'DEV', setup }), [])

  const patchPlayer = useCallback(
    (playerId: string, patch: Omit<DevPlayerPatch, 'playerId'>) =>
      apply({ players: [{ playerId, ...patch }] }),
    [apply],
  )

  // The table's own deck where there is one, so a "K" is worth what this deck
  // says; the first preset before that, only so the picker has faces to show.
  const deck = state?.config.deck
  const palette = useMemo(
    () => (catalog ? buildPalette(deck ?? catalog.decks[0]?.deck, catalog) : null),
    [catalog, deck],
  )
  const scenarios = useMemo(() => (state ? buildScenarios(state, meId) : []), [state, meId])
  // The cards no deck contains — a bomb, an unlucky 7 — which is exactly the
  // set worth one click per seat rather than a trip through the card picker.
  const effects = useMemo(
    () => (catalog?.passives ?? []).filter((passive) => passive.deckable === false),
    [catalog],
  )

  async function quickTable(bots: number) {
    const name = localStorage.getItem(NAME_KEY)?.trim() || 'dev'
    // Off the old table first: the seats being filled are counted against the
    // state in the store, and a game already running would answer for the one
    // being sat down.
    leaveGame()
    seatsWanted.current = 0
    const room = await createRoom(name)
    seatsWanted.current = 1 + bots
    connect(room.roomCode, room.playerId, name)
  }

  function pick(name: string) {
    if (!picking) return
    if (picking.kind === 'stack') {
      setStaged((was) => [...was, name])
      return
    }
    const player = state?.players.find((p) => p.id === picking.playerId)
    if (!player) return
    if (picking.kind === 'hand') {
      patchPlayer(player.id, { hand: [...player.hand.map(cardName), name] })
    } else {
      patchPlayer(player.id, { passives: [...player.passives.map(cardName), name] })
    }
  }

  if (!available) return null

  return (
    <>
      <button
        onClick={() => setOpen(!open)}
        data-testid="dev-toggle"
        // Out of the drawer's way while it is open, rather than on top of it.
        className={`display fixed bottom-3 z-[80] cursor-pointer rounded border-2 border-dashed border-[var(--ink)] bg-[var(--card-face)] px-2 py-1 text-sm text-[var(--ink)] ${
          open ? 'right-[calc(min(400px,94vw)+12px)]' : 'right-3'
        }`}
      >
        {open ? 'dev ×' : 'dev'}
      </button>

      {open && (
        <div
          data-testid="dev-panel"
          className="fixed right-0 top-0 z-[75] flex h-full w-[400px] max-w-[94vw] flex-col border-l-2 border-[var(--ink)] bg-[var(--card-face)]"
        >
          <div className="border-b border-dashed border-[var(--ink-soft)] px-3 py-2">
            <div className="flex items-baseline gap-2">
              <h3 className="-rotate-1">~ testing ~</h3>
              <small className="ml-auto" data-testid="dev-summary">
                {state
                  ? `${state.roomCode} · ${state.phase.toLowerCase()} · round ${state.round} · deck ${state.deckCount}`
                  : 'no table'}
              </small>
            </div>
            <div className="mt-2 flex gap-2">
              {(['cards', 'players', 'table'] as Tab[]).map((each) => (
                <button
                  key={each}
                  onClick={() => setTab(each)}
                  data-testid={`dev-tab-${each}`}
                  className={`display flex-1 cursor-pointer rounded border px-2 py-1 text-base ${
                    tab === each
                      ? 'border-[var(--ink)] bg-[var(--ink)] text-[var(--card-face)]'
                      : 'border-[var(--ink-soft)] bg-transparent text-[var(--ink-soft)]'
                  }`}
                >
                  {each}
                </button>
              ))}
            </div>
          </div>

          <div className="flex-1 overflow-y-auto px-3 py-3">
            {!state ? (
              <NoTable onQuickTable={quickTable} opening={opening} />
            ) : tab === 'cards' ? (
              <CardsTab
                deck={state.devDeck}
                staged={staged}
                onStage={() => setPicking({ kind: 'stack' })}
                onDrop={(index) => setStaged((was) => was.filter((_, i) => i !== index))}
                onClear={() => setStaged([])}
                onStack={() => {
                  apply({ stack: staged, skipWait: true })
                  setStaged([])
                }}
                picker={
                  picking?.kind === 'stack' && palette ? (
                    <DevCardPicker palette={palette} onPick={pick} onClose={() => setPicking(null)} testId="dev-stack-picker" />
                  ) : null
                }
              />
            ) : tab === 'players' ? (
              <PlayersTab
                players={state.players}
                meId={meId}
                effects={effects}
                onPatch={patchPlayer}
                picking={picking}
                onPickerOpen={setPicking}
                picker={
                  picking && picking.kind !== 'stack' && palette ? (
                    <DevCardPicker palette={palette} onPick={pick} onClose={() => setPicking(null)} testId="dev-hand-picker" />
                  ) : null
                }
              />
            ) : (
              <TableTab
                scenarios={scenarios}
                players={state.players}
                phase={state.phase}
                round={state.round}
                onApply={apply}
                onQuickTable={quickTable}
                opening={opening}
              />
            )}
          </div>
        </div>
      )}
    </>
  )
}

// ─── Sections ───

function Heading({ children }: { children: React.ReactNode }) {
  return <h4 className="display mb-1 mt-4 text-base text-[var(--ink-soft)] first:mt-0">{children}</h4>
}

function MiniButton({
  children,
  onClick,
  testId,
  disabled,
  active,
}: {
  children: React.ReactNode
  onClick: () => void
  testId?: string
  disabled?: boolean
  active?: boolean
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      data-testid={testId}
      className={`display cursor-pointer rounded border px-2 py-1 text-base disabled:cursor-not-allowed disabled:opacity-40 ${
        active
          ? 'border-[var(--ink)] bg-[var(--ink)] text-[var(--card-face)]'
          : 'border-[var(--ink)] bg-transparent text-[var(--ink)]'
      }`}
    >
      {children}
    </button>
  )
}

/**
 * A number that is only sent once you have finished typing it.
 *
 * Uncontrolled, and keyed on what the table says, so it takes the server's
 * answer whenever that changes and otherwise leaves what is being typed alone —
 * a controlled input would fight every keystroke against the state coming back.
 */
function NumberField({
  value,
  onCommit,
  testId,
}: {
  value: number
  onCommit: (value: number) => void
  testId?: string
}) {
  function commit(input: HTMLInputElement) {
    const parsed = Number.parseInt(input.value, 10)
    if (Number.isNaN(parsed) || parsed === value) {
      input.value = String(value)
      return
    }
    onCommit(parsed)
  }

  return (
    <input
      key={value}
      type="number"
      defaultValue={value}
      data-testid={testId}
      onBlur={(e) => commit(e.currentTarget)}
      onKeyDown={(e) => e.key === 'Enter' && commit(e.currentTarget)}
      className="display w-16 rounded border border-[var(--ink-soft)] bg-transparent px-1 py-0.5 text-base text-[var(--ink)]"
    />
  )
}

function NoTable({ onQuickTable, opening }: { onQuickTable: (bots: number) => void; opening: boolean }) {
  return (
    <div data-testid="dev-no-table">
      <p className="text-muted mb-3">
        no table yet. open one and it deals itself — the panel does the hosting, the bots and the start.
      </p>
      <div className="flex flex-wrap gap-2">
        {BOT_COUNTS.map((bots) => (
          <MiniButton key={bots} testId={`dev-quick-${bots}`} disabled={opening} onClick={() => onQuickTable(bots)}>
            {bots} bot{bots === 1 ? '' : 's'}
          </MiniButton>
        ))}
      </div>
      {opening && <p className="text-muted mt-2 italic">sitting everybody down…</p>}
    </div>
  )
}

function CardsTab({
  deck,
  staged,
  onStage,
  onDrop,
  onClear,
  onStack,
  picker,
}: {
  deck?: Card[]
  staged: string[]
  onStage: () => void
  onDrop: (index: number) => void
  onClear: () => void
  onStack: () => void
  picker: React.ReactNode
}) {
  return (
    <div data-testid="dev-cards-tab">
      <Heading>coming off the deck</Heading>
      {deck && deck.length > 0 ? (
        <div className="flex flex-wrap gap-1" data-testid="dev-deck-peek">
          {deck.map((card, index) => (
            <div key={card.id} className="relative" data-testid="dev-deck-card" data-card-name={cardName(card)}>
              <PlayingCard card={card} size="small" />
              <span className="display absolute -bottom-0.5 -right-0.5 z-10 min-w-[16px] rounded-full bg-[var(--ink)] px-1 text-center text-[10px] leading-[14px] text-[var(--card-face)]">
                {index + 1}
              </span>
            </div>
          ))}
        </div>
      ) : (
        <p className="text-muted italic">nothing dealt yet — the deck is built when the game starts</p>
      )}

      <Heading>next, in this order</Heading>
      <div className="mb-2 flex flex-wrap items-center gap-1" data-testid="dev-staged">
        {staged.map((name, index) => (
          <button
            key={`${name}-${index}`}
            onClick={() => onDrop(index)}
            data-testid="dev-staged-card"
            data-card-name={name}
            title="drop it"
            className="display cursor-pointer rounded border border-[var(--ink)] bg-transparent px-2 py-0.5 text-base text-[var(--ink)]"
          >
            {index + 1}. {name} ×
          </button>
        ))}
        <MiniButton onClick={onStage} testId="dev-stage-card">
          + card
        </MiniButton>
      </div>
      {picker}
      <div className="mt-2 flex gap-2">
        <MiniButton onClick={onStack} disabled={staged.length === 0} testId="dev-stack-apply">
          put on top
        </MiniButton>
        <MiniButton onClick={onClear} disabled={staged.length === 0} testId="dev-stack-clear">
          clear
        </MiniButton>
      </div>
      <p className="text-muted mt-2">
        a card the deck holds is lifted out of it and moved to the top; one it does not is minted, so a deck can be
        tested against a card it never contained.
      </p>
    </div>
  )
}

function PlayersTab({
  players,
  meId,
  effects,
  onPatch,
  picking,
  onPickerOpen,
  picker,
}: {
  players: Player[]
  meId: string | null
  /** The cards nothing deals — see the effect cards in `CardDefs`. */
  effects: { id: string; name: string }[]
  onPatch: (playerId: string, patch: Omit<DevPlayerPatch, 'playerId'>) => void
  picking: Picking
  onPickerOpen: (picking: Picking) => void
  picker: React.ReactNode
}) {
  return (
    <div data-testid="dev-players-tab">
      {players.map((player) => {
        const hand = player.hand.map(cardName)
        const passives = player.passives.map(cardName)
        return (
          <div
            key={player.id}
            data-testid="dev-player"
            data-player-id={player.id}
            className="mb-3 rounded border border-[var(--ink-soft)] p-2"
          >
            <div className="flex items-center gap-2">
              <span className="display text-lg">{player.name}</span>
              {player.id === meId && <small>you</small>}
              {player.isBot && <small>bot</small>}
              <span className="text-muted ml-auto">score</span>
              <NumberField
                value={player.score}
                testId="dev-score"
                onCommit={(score) => onPatch(player.id, { score })}
              />
            </div>

            <div className="mt-2 flex gap-1">
              {STATUSES.map((status) => (
                <MiniButton
                  key={status}
                  testId={`dev-status-${status}`}
                  active={player.status === status}
                  onClick={() => onPatch(player.id, { status })}
                >
                  {status}
                </MiniButton>
              ))}
            </div>

            <CardRow
              label="hand"
              cards={player.hand}
              onRemove={(index) => onPatch(player.id, { hand: hand.filter((_, i) => i !== index) })}
              onAdd={() => onPickerOpen({ kind: 'hand', playerId: player.id })}
              testId="dev-hand"
            />
            {picking?.kind === 'hand' && picking.playerId === player.id && picker}

            <CardRow
              label="modifiers"
              cards={player.passives}
              onRemove={(index) => onPatch(player.id, { passives: passives.filter((_, i) => i !== index) })}
              onAdd={() => onPickerOpen({ kind: 'passives', playerId: player.id })}
              testId="dev-passives"
            />
            {picking?.kind === 'passives' && picking.playerId === player.id && picker}

            {effects.length > 0 && (
              <div className="mt-2 flex flex-wrap items-center gap-1">
                <span className="text-muted mr-1">effects</span>
                {effects.map((effect) => (
                  <MiniButton
                    key={effect.id}
                    testId={`dev-effect-${effect.id}`}
                    active={passives.includes(effect.id)}
                    onClick={() =>
                      onPatch(player.id, {
                        passives: passives.includes(effect.id)
                          ? passives.filter((id) => id !== effect.id)
                          : [...passives, effect.id],
                      })
                    }
                  >
                    {effect.name}
                  </MiniButton>
                ))}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

function CardRow({
  label,
  cards,
  onRemove,
  onAdd,
  testId,
}: {
  label: string
  cards: Card[]
  onRemove: (index: number) => void
  onAdd: () => void
  testId: string
}) {
  return (
    <div className="mt-2 flex flex-wrap items-center gap-1" data-testid={testId}>
      <span className="text-muted mr-1">{label}</span>
      {cards.map((card, index) => (
        <button
          key={card.id}
          onClick={() => onRemove(index)}
          title="take it away"
          data-testid={`${testId}-card`}
          data-card-name={cardName(card)}
          className="cursor-pointer border-none bg-transparent p-0"
        >
          <PlayingCard card={card} size="small" />
        </button>
      ))}
      <MiniButton onClick={onAdd} testId={`${testId}-add`}>
        +
      </MiniButton>
    </div>
  )
}

function TableTab({
  scenarios,
  players,
  phase,
  round,
  onApply,
  onQuickTable,
  opening,
}: {
  scenarios: { id: string; label: string; hint: string; setup: DevSetup }[]
  players: Player[]
  phase: string
  round: number
  onApply: (setup: DevSetup) => void
  onQuickTable: (bots: number) => void
  opening: boolean
}) {
  return (
    <div data-testid="dev-table-tab">
      <Heading>one click</Heading>
      <div className="flex flex-col gap-1">
        {scenarios.map((scenario) => (
          <button
            key={scenario.id}
            onClick={() => onApply(scenario.setup)}
            data-testid={`dev-scenario-${scenario.id}`}
            className="cursor-pointer rounded border border-[var(--ink)] bg-transparent px-2 py-1 text-left"
          >
            <span className="display text-base text-[var(--ink)]">{scenario.label}</span>
            <small className="block">{scenario.hint}</small>
          </button>
        ))}
        {scenarios.length === 0 && <p className="text-muted italic">nothing to set up until a round is running</p>}
      </div>

      <Heading>the table</Heading>
      <div className="flex flex-wrap gap-2">
        <MiniButton onClick={() => onApply({ skipWait: true })} testId="dev-skip-wait">
          skip the wait
        </MiniButton>
        <MiniButton onClick={() => onApply({ clearPrompt: true, skipWait: true })} testId="dev-clear-prompt">
          clear the prompt
        </MiniButton>
        <MiniButton onClick={() => send({ type: 'NEXT_ROUND' })} testId="dev-next-round">
          next round
        </MiniButton>
        {phase === 'LOBBY' && (
          <>
            <MiniButton onClick={() => send({ type: 'ADD_BOT' })} testId="dev-add-bot">
              + bot
            </MiniButton>
            <MiniButton onClick={() => send({ type: 'START_GAME' })} testId="dev-start">
              start
            </MiniButton>
          </>
        )}
      </div>

      {phase !== 'LOBBY' && (
        <>
          <Heading>round</Heading>
          <NumberField value={round} testId="dev-round" onCommit={(next) => onApply({ round: next })} />

          <Heading>whose turn</Heading>
          <div className="flex flex-wrap gap-1">
            {players.map((player) => (
              <MiniButton
                key={player.id}
                testId="dev-turn-to"
                onClick={() => onApply({ turnPlayerId: player.id, skipWait: true })}
              >
                {player.name}
              </MiniButton>
            ))}
          </div>
        </>
      )}

      <Heading>another table</Heading>
      <div className="flex flex-wrap gap-2">
        {BOT_COUNTS.map((bots) => (
          <MiniButton key={bots} testId={`dev-quick-${bots}`} disabled={opening} onClick={() => onQuickTable(bots)}>
            {bots} bot{bots === 1 ? '' : 's'}
          </MiniButton>
        ))}
      </div>
      <p className="text-muted mt-2">leaves this one and deals a fresh table, already started.</p>
    </div>
  )
}
