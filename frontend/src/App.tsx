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
  const [clock, setClock] = useState(() => Date.now())
  useEffect(() => {
    if (!outroUntil) return
    const interval = window.setInterval(() => setClock(Date.now()), 80)
    return () => window.clearInterval(interval)
  }, [outroUntil])

  const closing = phase === 'ROUND_END' || phase === 'GAME_END'
  const holdingTable = closing && !!outroUntil && clock < outroUntil
  const displayPhase = holdingTable ? 'PLAYING' : phase

  // Cards animate in from the deck once each; a new round deals a fresh set.
  useEffect(() => {
    if (phase === 'LOBBY') resetDealtCards()
  }, [phase])

  if (catalogError) {
    return (
      <div className="page-shell justify-center">
        <div className="text-center">
          <h2 className="mb-2 -rotate-1 text-[var(--accent)]">no connection</h2>
          <p className="text-muted">{catalogError}</p>
        </div>
      </div>
    )
  }

  if (!catalog) {
    return (
      <div className="page-shell justify-center">
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
