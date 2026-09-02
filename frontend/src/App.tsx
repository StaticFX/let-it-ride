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
import { BUST_REVEAL_MS, BUST_SCATTER_MS } from './hooks/useGame'
import { unlockAudio } from './audio/sfx'

/**
 * Give the bust and flip-7 animations time to land before the summary takes
 * over. A bust plays in two beats — call out the pair, then scatter the hand —
 * so it needs the whole of both.
 */
const ROUND_END_DELAY_MS = {
  flip7: 3000,
  bust: BUST_REVEAL_MS + BUST_SCATTER_MS + 300,
  none: 0,
}

function App() {
  const phase = useGameStore((s) => s.state?.phase) ?? 'LOBBY'
  const events = useGameStore((s) => s.events)
  const catalog = useGameStore((s) => s.catalog)
  const [catalogError, setCatalogError] = useState<string | null>(null)

  useEffect(() => {
    fetchCatalog().catch(() => setCatalogError('could not reach the table — is the server up?'))
  }, [])

  // Browsers will not start an AudioContext until the page has been touched,
  // so the samples are decoded on whatever the first interaction happens to be.
  useEffect(() => {
    const unlock = () => unlockAudio()
    window.addEventListener('pointerdown', unlock, { once: true })
    window.addEventListener('keydown', unlock, { once: true })
    return () => {
      window.removeEventListener('pointerdown', unlock)
      window.removeEventListener('keydown', unlock)
    }
  }, [])

  // Hold on the board for a beat so the last animation of the round plays out.
  const [displayPhase, setDisplayPhase] = useState(phase)
  useEffect(() => {
    if (displayPhase === phase) return
    const closingRound = phase === 'ROUND_END' || phase === 'GAME_END'
    const delay = !closingRound
      ? ROUND_END_DELAY_MS.none
      : events.some((e) => e.type === 'flip7')
        ? ROUND_END_DELAY_MS.flip7
        : events.some((e) => e.type === 'bust')
          ? ROUND_END_DELAY_MS.bust
          : ROUND_END_DELAY_MS.none
    const timer = setTimeout(() => setDisplayPhase(phase), delay)
    return () => clearTimeout(timer)
  }, [phase, displayPhase, events])

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
