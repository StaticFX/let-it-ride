import { useEffect, useState } from 'react'
import { useGameStore } from './state/gameStore'
import { fetchCatalog } from './net/client'
import { Lobby } from './components/pages/Lobby'
import { GameBoard } from './components/game/GameBoard'
import { RoundSummary } from './components/pages/RoundSummary'
import { GameOver } from './components/pages/GameOver'
import { EscapeMenu } from './components/overlays/EscapeMenu'
import { DisconnectOverlay } from './components/overlays/DisconnectOverlay'
import { resetDealtCards } from './components/cards/dealtCards'
import { prefetchAudio, unlockAudio } from './audio/sfx'

function App() {
  const phase = useGameStore((s) => s.state?.phase) ?? 'LOBBY'
  const outroUntil = useGameStore((s) => s.state?.roundOutroUntil)
  const catalog = useGameStore((s) => s.catalog)
  const [catalogError, setCatalogError] = useState<string | null>(null)

  useEffect(() => {
    fetchCatalog().catch(() => setCatalogError('could not reach the table — is the server up?'))
  }, [])

  // Downloading does not need a gesture — only starting the AudioContext does —
  // so the samples are fetched up front and merely decoded on first touch.
  // Waiting for the gesture to start the download made the very click that
  // unlocked audio the one click that never made a sound.
  useEffect(() => {
    prefetchAudio()
    const unlock = () => unlockAudio()
    window.addEventListener('pointerdown', unlock, { once: true })
    window.addEventListener('keydown', unlock, { once: true })
    return () => {
      window.removeEventListener('pointerdown', unlock)
      window.removeEventListener('keydown', unlock)
    }
  }, [])

  // The round's closing beats — the last animation, then the outro card — run
  // on the table, so the scoreboard waits for the window the server set.
  //
  // One timer at the deadline rather than a poll. The swap happens exactly
  // once, so polling for it re-rendered the whole app a dozen times a second to
  // learn nothing — and worse, it crossed `roundOutroUntil` on a tick of its
  // own, never the same tick as the table's own clock, so for whatever gap fell
  // between the two the loser was left on screen alone. That is how the table
  // came back for a moment between the outro card and the scoreboard.
  //
  // The clock is therefore read once at mount and then only ever pushed to the
  // deadline itself, which is the only reading this screen has a use for.
  // Seeding it from the clock rather than from zero is what keeps somebody
  // reconnecting into a finished round from seeing the table for a frame first.
  const [clock, setClock] = useState(() => Date.now())
  useEffect(() => {
    if (!outroUntil) return
    const timer = window.setTimeout(
      () => setClock(Math.max(Date.now(), outroUntil)),
      Math.max(0, outroUntil - Date.now()),
    )
    return () => window.clearTimeout(timer)
  }, [outroUntil])

  const closing = phase === 'ROUND_END' || phase === 'GAME_END'
  const holdingTable = closing && !!outroUntil && clock < outroUntil
  const displayPhase = holdingTable ? 'PLAYING' : phase

  // A card animates in from the deck once per trip. The table prunes as it
  // goes, so this is only for the stretches where there is no table to do it —
  // back in the lobby, with a fresh deck about to be built.
  useEffect(() => {
    if (phase === 'LOBBY') resetDealtCards()
  }, [phase])

  if (catalogError) {
    return (
      <div className="page-shell justify-center" data-testid="catalog-error">
        <div className="text-center">
          <h2 className="mb-2 -rotate-1 text-[var(--accent)]">no connection</h2>
          <p className="text-muted">{catalogError}</p>
        </div>
      </div>
    )
  }

  if (!catalog) {
    return (
      <div className="page-shell justify-center" data-testid="catalog-loading">
        <p className="text-muted sway-mid">shuffling…</p>
      </div>
    )
  }

  let screen: React.ReactNode
  switch (displayPhase) {
    case 'PLAYING':
      screen = <GameBoard />
      break
    case 'ROUND_END':
      screen = <RoundSummary />
      break
    case 'GAME_END':
      screen = <GameOver />
      break
    default:
      screen = <Lobby />
  }

  return (
    <>
      {screen}
      <EscapeMenu />
      <DisconnectOverlay />
    </>
  )
}

export default App
