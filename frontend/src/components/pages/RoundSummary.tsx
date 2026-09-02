import { useGameStore } from '../../state/gameStore'
import { send } from '../../net/client'
import { PlayingCard } from '../cards/PlayingCard'
import { Scoreboard } from '../game/Scoreboard'
import { SketchButton } from '../ui/Button'

export function RoundSummary() {
  const state = useGameStore((s) => s.state)
  const isHost = useGameStore((s) => s.isHost)
  const localPlayerId = useGameStore((s) => s.localPlayerId)

  if (!state) return null

  const { players, round, roundDeltas, roundWinnerId, flip7PlayerId } = state
  const winner = players.find((p) => p.id === roundWinnerId) ?? null

  return (
    <div className="page-shell pt-10">
      <div className="content-width">
        <div className="text-center mb-8">
          <small>round {String(round).padStart(2, '0')} complete</small>
          {winner ? (
            <h1 className="text-[42px] animate-[swayMore_3s_ease-in-out_infinite]">
              <span className="text-[var(--accent)]">{winner.name}</span> wins!
            </h1>
          ) : (
            <h1 className="text-[42px] text-[var(--accent)]">everyone busted!</h1>
          )}
          {flip7PlayerId && (
            <p className="text-muted mt-1">
              <span className="display text-xl text-[var(--accent)]">
                {players.find((p) => p.id === flip7PlayerId)?.name}
              </span>{' '}
              hit seven — round called early
            </p>
          )}
        </div>

        <div className="flex flex-col gap-3 mb-6">
          {players.map((player) => {
            const isWinner = winner?.id === player.id
            const busted = player.status === 'bust'
            const points = roundDeltas[player.id] ?? 0

            return (
              <div
                key={player.id}
                className={`rounded p-4 relative ${isWinner ? 'sketch-box-winner' : 'sketch-box'} ${busted ? 'opacity-55' : ''}`}
              >
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2">
                    {isWinner && <span className="text-xl">★</span>}
                    <h3 className={busted ? 'line-through text-[var(--accent)]' : ''}>{player.name}</h3>
                    {player.id === localPlayerId && <small>(you)</small>}
                    {player.id === flip7PlayerId && <small className="text-[var(--accent)]">flip 7!</small>}
                  </div>
                  <span className={`number text-[28px] ${isWinner ? 'text-[var(--accent)]' : 'text-muted'}`}>
                    +{points}
                  </span>
                </div>
                <div className="flex items-center gap-1 flex-wrap">
                  {player.hand.map((card) => (
                    <PlayingCard key={card.id} card={card} size="small" dimmed={busted} />
                  ))}
                  {player.passives.map((card) => (
                    <PlayingCard key={card.id} card={card} size="small" dimmed={busted} />
                  ))}
                  <span className={`number text-[22px] ml-2 ${busted ? 'text-[var(--accent)] line-through' : 'text-muted'}`}>
                    = {busted ? player.handValue : points}
                  </span>
                </div>
                {busted && player.bustReason && (
                  <p className="mt-1.5 display text-sm text-[var(--accent)]">
                    ✗ {player.bustReason === 'duplicate' ? 'duplicate card!' : `${player.bustReason}!`}
                  </p>
                )}
              </div>
            )
          })}
        </div>

        <div className="mb-6">
          <Scoreboard players={players} currentPlayerId="" localPlayerId={localPlayerId} />
        </div>

        {isHost ? (
          <SketchButton variant="primary" onClick={() => send({ type: 'NEXT_ROUND' })}>
            {state.gameWinnerId ? 'see the results →' : 'next round →'}
          </SketchButton>
        ) : (
          <p className="text-center text-muted">waiting for host…</p>
        )}
      </div>
    </div>
  )
}
