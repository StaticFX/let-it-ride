import { useGameStore } from '../../state/gameStore'
import { leaveGame } from '../../net/client'
import { PlayingCard } from '../cards/PlayingCard'
import { SketchButton } from '../ui/Button'

export function GameOver() {
  const state = useGameStore((s) => s.state)
  const localPlayerId = useGameStore((s) => s.localPlayerId)

  if (!state) return null

  const sorted = [...state.players].sort((a, b) => b.score - a.score)
  const winner = state.players.find((p) => p.id === state.gameWinnerId) ?? sorted[0]
  const isLocalWinner = winner?.id === localPlayerId

  return (
    <div className="page-shell justify-center">
      <div className="max-w-[460px] w-full text-center">
        <small>game over</small>
        <h1 className="text-5xl mb-1 animate-[swayMore_3s_ease-in-out_infinite]">
          {isLocalWinner ? 'you win!' : <><span className="text-[var(--accent)]">{winner?.name}</span> wins!</>}
        </h1>
        {winner && (
          <p className="text-lg text-muted mb-8">
            final score: <span className="number text-3xl">{winner.score}</span>
          </p>
        )}

        <div className="sketch-box text-left rounded p-4 mb-6">
          <h3 className="mb-2 -rotate-1">~ final standings ~</h3>
          {sorted.map((p, i) => (
            <div key={p.id} className={`flex items-center justify-between py-1.5 ${i > 0 ? 'opacity-70' : ''}`}>
              <div className="flex items-center gap-2">
                <small className="w-5">{i + 1}.</small>
                <span className="display text-xl">{p.name}</span>
                {p.id === localPlayerId && <small>(you)</small>}
                {p.isBot && <small>bot</small>}
              </div>
              <span className={`number text-2xl ${i === 0 ? 'text-[var(--accent)]' : 'text-muted'}`}>{p.score}</span>
            </div>
          ))}
        </div>

        {winner && winner.hand.length > 0 && (
          <div className="mb-6">
            <small>winning hand</small>
            <div className="flex justify-center gap-1 mt-2">
              {winner.hand.map((card) => (
                <PlayingCard key={card.id} card={card} size="small" />
              ))}
            </div>
          </div>
        )}

        <SketchButton variant="primary" onClick={leaveGame}>play again!</SketchButton>
      </div>
    </div>
  )
}
