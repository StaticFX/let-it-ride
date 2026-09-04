import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useGameStore, findRule } from '../../state/gameStore'
import { connect, createRoom, leaveGame, lookupRoom, send } from '../../net/client'
import type { GameConfig } from '../../game/types'
import { CardBack } from '../cards/CardBack'
import { PlayingCard } from '../cards/PlayingCard'
import { SketchButton } from '../ui/Button'
import { LobbyConfig } from './LobbyConfig'
import { deckSize, describeDeck } from '../../game/deck'
import { RulesPage } from '../rules/RulesPage'
import { Countdown } from '../overlays/Countdown'
import { SoundToggle } from '../ui/SoundToggle'
import { SketchInput } from '../ui/SketchInput'

const NAME_KEY = 'let-it-ride:name'
const DEFAULT_BOTS = 3

type Screen = 'choose' | 'join' | 'room' | 'settings'

/**
 * `crypto.randomUUID` only exists on secure origins, and a homelab box on plain
 * http over a LAN address is not one — so do not depend on it.
 */
function newPlayerId(): string {
  const bytes = new Uint8Array(8)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // Falls through to the manual selection path below.
  }
  return false
}

export function Lobby() {
  const state = useGameStore((s) => s.state)
  const isHost = useGameStore((s) => s.isHost)
  const roomCode = useGameStore((s) => s.roomCode)
  const connection = useGameStore((s) => s.connection)
  const error = useGameStore((s) => s.error)
  const catalog = useGameStore((s) => s.catalog)

  const [screen, setScreen] = useState<Screen>('choose')
  const [playerName, setPlayerName] = useState(() => localStorage.getItem(NAME_KEY) ?? '')
  const [joinCode, setJoinCode] = useState('')
  const [copied, setCopied] = useState(false)
  const [showRules, setShowRules] = useState(false)
  const [showDeckCards, setShowDeckCards] = useState(false)
  const [countdown, setCountdown] = useState(false)
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)
  const botsWanted = useRef(0)

  const players = state?.players ?? []
  const config = state?.config
  // Undefined for a deck somebody built. Deliberately not falling back to the
  // first preset: a table playing its own deck would otherwise be described by
  // one it is not playing, down to the card list.
  const preset = catalog?.decks.find((d) => d.id === config?.deckPresetId)
  // ...so a deck with no preset behind it has to describe itself from what the
  // config actually holds.
  const builtCardCount = config ? deckSize(config.deck) : 0
  const builtContents = useMemo(
    () => (preset || !config || !catalog ? [] : describeDeck(config.deck, catalog)),
    [preset, config, catalog],
  )

  // Which screen actually shows is a function of the connection: in a session
  // you are in the room (or its settings), out of one you are at the front
  // door — so leaving from anywhere, including the in-game menu, lands right.
  const inSession = connection === 'connected' || connection === 'connecting'
  const view: Screen = inSession
    ? screen === 'settings' ? 'settings' : 'room'
    : screen === 'join' ? 'join' : 'choose'

  // Leaving lands on the front door however you go: the pause menu and the
  // disconnect overlay both call `leaveGame()` without going through `leave()`,
  // and somebody who came in by code would otherwise be handed back the join
  // screen by a button that said "back to menu".
  const wasInSession = useRef(false)
  useEffect(() => {
    if (inSession) {
      wasInSession.current = true
    } else if (wasInSession.current) {
      wasInSession.current = false
      setScreen('choose')
    }
  }, [inSession])

  // Filling a bot table is two steps: host the room, then ask for bots — one
  // per state update, so the server confirms each seat before the next.
  useEffect(() => {
    if (botsWanted.current <= 0 || connection !== 'connected' || !isHost) return
    botsWanted.current -= 1
    send({ type: 'ADD_BOT' })
  }, [connection, isHost, players.length])

  const rememberName = useCallback((name: string) => {
    setPlayerName(name)
    localStorage.setItem(NAME_KEY, name)
  }, [])

  async function host(bots = 0) {
    const name = playerName.trim()
    if (!name || busy) return
    setBusy(true)
    setLocalError(null)
    try {
      botsWanted.current = bots
      const room = await createRoom(name)
      connect(room.roomCode, room.playerId, name)
    } catch (e) {
      setLocalError(e instanceof Error ? e.message : 'could not open a table')
    } finally {
      setBusy(false)
    }
  }

  async function join() {
    const name = playerName.trim()
    const code = joinCode.trim().toUpperCase()
    if (!name || code.length < 4 || busy) return
    setBusy(true)
    setLocalError(null)
    try {
      const info = await lookupRoom(code)
      if (!info.joinable) {
        setLocalError('that game is full or already underway')
        return
      }
      connect(code, newPlayerId(), name)
    } catch (e) {
      setLocalError(e instanceof Error ? e.message : 'no game with that code')
    } finally {
      setBusy(false)
    }
  }

  function updateConfig(next: GameConfig) {
    send({ type: 'SET_CONFIG', config: next })
  }

  function leave() {
    botsWanted.current = 0
    leaveGame()
    setScreen('choose')
    setLocalError(null)
  }

  const startGame = useCallback(() => {
    setCountdown(false)
    send({ type: 'START_GAME' })
  }, [])

  // Once there is a room, its own flip target answers for the house rules the
  // host has switched on; before that there is only the catalog's default.
  if (showRules) {
    return <RulesPage onClose={() => setShowRules(false)} config={config} flip7Target={state?.flip7Target} />
  }

  // ── Settings ──
  if (view === 'settings' && config) {
    return (
      <div className="page-shell justify-start pt-12" data-testid="settings-screen" data-host={isHost}>
        <div className="content-width">
          <h1 className="text-4xl mb-1 text-center sway-slow">~ settings ~</h1>
          <p className="text-muted text-center mb-6">
            {isHost ? 'pick your deck, win condition & house rules' : 'the host decides these'}
          </p>
          <div className={`mb-6 ${isHost ? '' : 'pointer-events-none opacity-70'}`}>
            <LobbyConfig config={config} onChange={updateConfig} />
          </div>
          <SketchButton variant="ghost" testId="settings-done" onClick={() => setScreen('room')}>← done</SketchButton>
        </div>
      </div>
    )
  }

  // ── Start ──
  if (view === 'choose' && connection !== 'connected' && connection !== 'connecting') {
    return (
      <div className="page-shell justify-center" data-testid="title-screen">
        <div className="max-w-[400px] w-full text-center">
          <div className="flex justify-center mb-4">
            <CardBack size="deck" style={{ transform: 'rotate(-12deg)', opacity: 0.6 }} />
            <CardBack size="deck" style={{ transform: 'rotate(3deg)', marginLeft: -40, opacity: 0.8 }} />
            <CardBack size="deck" style={{ transform: 'rotate(12deg)', marginLeft: -40 }} />
          </div>
          <h1 className="text-[52px] mb-1 sway-slow">let it ride</h1>

          <div className="text-left mb-6">
            <label>what's your name?</label>
            <SketchInput
              type="text"
              data-testid="name-input"
              value={playerName}
              onChange={(e) => rememberName(e.target.value)}
              placeholder="scribble it here…"
              maxLength={16}
              className="mt-1"
            />
          </div>

          {(localError || error) && <p className="text-[var(--accent)] mb-4" data-testid="lobby-error">{localError ?? error}</p>}

          <div className="flex gap-3.5 mb-4">
            <SketchButton variant="primary" testId="host-game" onClick={() => host()} disabled={!playerName.trim() || busy}>
              host a game
            </SketchButton>
            <SketchButton variant="ghost" testId="join-game" onClick={() => setScreen('join')} disabled={!playerName.trim()}>
              join a game
            </SketchButton>
          </div>

          <div className="flex items-center gap-3 my-4">
            <div className="divider-line" />
            <small>or</small>
            <div className="divider-line" />
          </div>

          <div className="flex gap-3.5">
            <SketchButton variant="ghost" testId="play-vs-bots" onClick={() => host(DEFAULT_BOTS)} disabled={!playerName.trim() || busy}>
              play vs bots
            </SketchButton>
            <SketchButton variant="ghost" testId="open-rules" onClick={() => setShowRules(true)}>rules</SketchButton>
          </div>

          <div className="mt-6 flex justify-center">
            <SoundToggle />
          </div>
        </div>
      </div>
    )
  }

  // ── Join code ──
  if (view === 'join' && connection !== 'connected' && connection !== 'connecting') {
    return (
      <div className="page-shell justify-center" data-testid="join-screen">
        <div className="max-w-[400px] w-full text-center">
          <h1 className="text-4xl mb-2">join a game</h1>
          <p className="text-muted mb-6">ask the host for the code</p>
          <SketchInput
            type="text"
            data-testid="join-code-input"
            value={joinCode}
            onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
            onKeyDown={(e) => e.key === 'Enter' && join()}
            placeholder="_ _ _ _"
            maxLength={4}
            className="text-center text-4xl tracking-[0.5em] font-bold mb-4"
          />
          {(localError || error) && <p className="text-[var(--accent)] mb-4" data-testid="lobby-error">{localError ?? error}</p>}
          <div className="flex gap-3.5">
            <SketchButton variant="ghost" testId="join-back" onClick={() => { setScreen('choose'); setLocalError(null) }}>← back</SketchButton>
            <SketchButton variant="primary" testId="join-submit" onClick={join} disabled={busy}>join!</SketchButton>
          </div>
        </div>
      </div>
    )
  }

  // ── Connecting ──
  if (connection === 'connecting' || !state || !config) {
    return (
      <div className="page-shell justify-center" data-testid="connecting-screen">
        <div className="text-center">
          <h2 className="mb-3 sway-mid">connecting…</h2>
          <p className="text-muted mb-6">finding the table</p>
          <SketchButton variant="ghost" testId="connect-cancel" onClick={leave}>cancel</SketchButton>
        </div>
      </div>
    )
  }

  // ── Waiting room ──
  const winLabel = config.winCondition === 'first_to_score'
    ? `first to ${config.targetScore}`
    : `best of ${config.totalRounds} rounds`
  const minPlayers = catalog?.minPlayers ?? 2
  const maxPlayers = catalog?.maxPlayers ?? 5
  const missing = Math.max(0, minPlayers - players.length)

  return (
    <div className="page-shell justify-start pt-12" data-testid="waiting-room" data-host={isHost}>
      {countdown && <Countdown onDone={startGame} />}

      <div className="max-w-[460px] w-full">
        <div className="text-center mb-8">
          <h1 className="sway-slow">{playerName ? `${playerName}'s game` : 'let it ride'}</h1>
          {roomCode && (
            <button
              onClick={async () => {
                if (await copyToClipboard(roomCode)) {
                  setCopied(true)
                  setTimeout(() => setCopied(false), 2000)
                }
              }}
              className="mt-3 bg-transparent border-none cursor-pointer block mx-auto"
            >
              <label>room code: </label>
              <span data-testid="room-code" className="display text-4xl tracking-[0.25em] room-code-border pb-1 select-all">{roomCode}</span>
              <small className="block mt-2">{copied ? 'copied!' : 'share it with your friends'}</small>
            </button>
          )}
        </div>

        {/* Rules of this table */}
        <div className="sketch-box mb-4 rounded p-5 relative">
          <h2 className="mb-4 -rotate-1">~ rules for this game ~</h2>

          <div className="flex items-center justify-between mb-3">
            <p>
              <span className="text-muted">deck: </span>
              <span className="display text-xl" data-testid="table-deck-name">
                {preset?.name ?? 'a deck of your own'}
              </span>
              <small className="ml-1.5">({preset?.cardCount ?? builtCardCount} cards)</small>
            </p>
            <button
              onClick={() => setShowDeckCards(!showDeckCards)}
              data-testid="toggle-deck-cards"
              className="bg-transparent border-none cursor-pointer display text-base text-[var(--accent)]"
            >
              {showDeckCards ? 'hide cards' : 'see cards'}
            </button>
          </div>

          {showDeckCards && (
            <div className="sketch-box-light flex flex-wrap gap-1.5 p-2 mb-4 rounded">
              {(preset?.contents ?? builtContents).map((entry) => (
                <div key={entry.card.id} className="relative">
                  <PlayingCard card={entry.card} size="small" />
                  <span className="absolute -bottom-0.5 -right-0.5 z-10 display text-[10px] text-[var(--card-face)] bg-[var(--ink)] rounded-full px-1 leading-[14px] min-w-[16px] text-center">
                    {entry.count}x
                  </span>
                </div>
              ))}
            </div>
          )}

          <p className="mb-1">
            <span className="text-muted">goal: </span>
            <span className="display text-xl" data-testid="table-goal">{winLabel}</span>
          </p>
          <p className="mb-1">
            <span className="text-muted">turn timer: </span>
            <span className="display text-xl" data-testid="table-timer">{config.turnTimeSeconds}s</span>
          </p>
          <p className="mb-1">
            <span className="text-muted">next round: </span>
            <span className="display text-xl" data-testid="table-autostart">
              {config.autoNextRoundSeconds ? `auto after ${config.autoNextRoundSeconds}s` : 'when the host says'}
            </span>
          </p>
          {config.ruleIds.length > 0 && (
            <p>
              <span className="text-muted">house rules: </span>
              <span className="display text-xl" data-testid="table-house-rules">
                {config.ruleIds.map((id) => findRule(catalog, id)?.name ?? id).join(', ')}
              </span>
            </p>
          )}

          <div className="mt-4 flex gap-3.5">
            <SketchButton variant="ghost" testId="open-settings" onClick={() => setScreen('settings')}>
              {isHost ? 'change settings' : 'see settings'}
            </SketchButton>
            <SketchButton variant="ghost" testId="open-rules" onClick={() => setShowRules(true)}>rules</SketchButton>
          </div>
        </div>

        {/* Players */}
        <div className="sketch-box mb-4 rounded p-5 relative">
          <h2 className="mb-3 -rotate-1">~ players ({players.length}/{maxPlayers}) ~</h2>
          {players.length === 0 ? (
            <p className="text-muted text-lg py-3 italic">waiting for friends…</p>
          ) : (
            players.map((p, i) => (
              <div
                key={p.id}
                data-testid="lobby-player"
                data-player-id={p.id}
                data-player-name={p.name}
                data-bot={p.isBot}
                className="flex items-center gap-2 py-1.5 display text-xl"
              >
                <small>{i + 1}.</small>
                <span className="flex-1">{p.name}</span>
                {p.isBot && <small className="font-normal">bot</small>}
                {p.id === state.hostId && <small className="font-normal">(host)</small>}
                {isHost && p.id !== state.hostId && (
                  <button
                    onClick={() => send({ type: 'KICK', playerId: p.id })}
                    data-testid="kick-player"
                    className="bg-transparent border-none cursor-pointer display text-base text-[var(--accent)] px-1.5 -rotate-1"
                  >
                    kick
                  </button>
                )}
              </div>
            ))
          )}
          {isHost && players.length < maxPlayers && (
            <button
              onClick={() => send({ type: 'ADD_BOT' })}
              data-testid="add-bot"
              className="mt-2 bg-transparent border-none cursor-pointer display text-base text-[var(--accent)] -rotate-1"
            >
              + add a bot
            </button>
          )}
        </div>

        {error && <p className="text-[var(--accent)] text-center mb-3" data-testid="lobby-error">{error}</p>}

        <div className="flex gap-3.5">
          {isHost ? (
            <SketchButton variant="primary" testId="start-game" onClick={() => setCountdown(true)} disabled={missing > 0}>
              {missing > 0 ? `need ${missing} more` : 'let it ride!'}
            </SketchButton>
          ) : (
            <p className="flex-1 flex items-center justify-center text-muted text-lg" data-testid="waiting-for-host">
              waiting for host to start…
            </p>
          )}
          <SketchButton variant="ghost" testId="leave-room" onClick={leave}>leave</SketchButton>
        </div>
      </div>
    </div>
  )
}
