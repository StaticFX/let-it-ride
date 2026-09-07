import { signedPoints } from '../../game/types'
import { useGameStore } from '../../state/gameStore'
import { send } from '../../net/client'
import { PlayingCard } from '../cards/PlayingCard'
import { Scoreboard } from '../game/Scoreboard'
import { SketchButton } from '../ui/Button'
import { useCountdown } from '../../hooks/useCountdown'

/**
 * How a bust reads on the scoreboard. The server sends the reason as a bare
 * word — anything not named here is shown as it arrived, so a card added on the
 * backend still says something sensible before it is given a line of its own.
 */
const BUST_REASONS: Record<string, string> = {
  duplicate: 'duplicate card!',
  threshold: 'went over the top!',
  'coin flip': 'called the coin wrong!',
  assassination: 'the bottle picked them!',
  ratio: "don't care + ratio!",
  'taken down': 'taken down by the bomber!',
}

export function RoundSummary() {
  const state = useGameStore((s) => s.state)
  const isHost = useGameStore((s) => s.isHost)
  const localPlayerId = useGameStore((s) => s.localPlayerId)
  const countdown = useCountdown(state?.nextRoundAt)

  if (!state) return null

  const { players, round, roundDeltas, roundWinnerId, flip7PlayerId } = state
  const adjustments = state.roundAdjustments ?? {}
  // Whose bust came to nothing, which is every bust but the one the antimatter
  // makes: its holder is out and pays for the round anyway. The server names
  // them; an older one does not, and every bust reads as written off as before.
  const stillCounts = state.bustStillCountsIds ?? []
  // This room's target, not the catalog's default — "flip 9" moves it.
  const flipTarget = state.flip7Target
  const winner = players.find((p) => p.id === roundWinnerId) ?? null

  return (
    <div
      className="page-shell pt-10"
      data-testid="round-summary"
      data-round={round}
      data-winner-id={roundWinnerId ?? ''}
      data-flip7-id={flip7PlayerId ?? ''}
    >
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
              hit {flipTarget} — round called early
            </p>
          )}
        </div>

        <div className="flex flex-col gap-3 mb-6">
          {players.map((player) => {
            const isWinner = winner?.id === player.id
            const busted = player.status === 'bust'
            const writtenOff = busted && !stillCounts.includes(player.id)
            const points = roundDeltas[player.id] ?? 0
            // Points that came off for a reason other than the hand. Without
            // this a player docked fifteen just sees a zero and no reason.
            const adjustment = adjustments[player.id] ?? 0

            return (
              <div
                key={player.id}
                data-testid="summary-row"
                data-player-id={player.id}
                data-player-name={player.name}
                data-points={points}
                data-busted={busted}
                className={`rounded p-4 relative ${isWinner ? 'sketch-box-winner' : 'sketch-box'} ${busted ? 'opacity-55' : ''}`}
              >
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-2">
                    {isWinner && <span className="text-xl">★</span>}
                    <h3 className={busted ? 'line-through text-[var(--accent)]' : ''}>{player.name}</h3>
                    {player.id === localPlayerId && <small>(you)</small>}
                    {player.id === flip7PlayerId && (
                      <small className="text-[var(--accent)]">flip {flipTarget}!</small>
                    )}
                  </div>
                  <span className={`number text-[28px] ${isWinner ? 'text-[var(--accent)]' : 'text-muted'}`}>
                    {signedPoints(points)}
                  </span>
                </div>
                <div className="flex items-center gap-1 flex-wrap">
                  {player.hand.map((card) => (
                    <PlayingCard key={card.id} card={card} size="small" dimmed={busted} />
                  ))}
                  {player.passives.map((card) => (
                    <PlayingCard key={card.id} card={card} size="small" dimmed={busted} />
                  ))}
                  {/* A bust normally shows what the hand added up to, struck
                      through: this is what it would have been worth. A bust
                      that still counts has no "would have been" — the number
                      beside the cards is the number they are taking. */}
                  <span className={`number text-[22px] ml-2 ${busted ? 'text-[var(--accent)]' : 'text-muted'} ${writtenOff ? 'line-through' : ''}`}>
                    = {writtenOff ? player.handValue : points}
                  </span>
                </div>
                {!writtenOff && adjustment !== 0 && (
                  <p
                    className="mt-1.5 display text-sm text-[var(--accent)]"
                    data-testid="summary-adjustment"
                    data-adjustment={adjustment}
                  >
                    {adjustment < 0 ? `− ${-adjustment} taken off the round` : `+ ${adjustment} on top`}
                  </p>
                )}
                {busted && player.bustReason && (
                  <p className="mt-1.5 display text-sm text-[var(--accent)]" data-bust-reason={player.bustReason}>
                    ✗ {BUST_REASONS[player.bustReason] ?? `${player.bustReason}!`}
                  </p>
                )}
              </div>
            )
          })}
        </div>

        <div className="mb-6">
          <Scoreboard
            players={players}
            currentPlayerId=""
            localPlayerId={localPlayerId}
            worth={(player) => state.handWorth?.[player.id] ?? player.handValue}
            target={state.config.winCondition === 'first_to_score' ? state.config.targetScore : undefined}
          />
        </div>

        {isHost ? (
          <SketchButton variant="primary" testId="next-round" onClick={() => send({ type: 'NEXT_ROUND' })}>
            {state.gameWinnerId
              ? 'see the results →'
              : countdown === null ? 'next round →' : `next round → (${countdown})`}
          </SketchButton>
        ) : (
          <p className="text-center text-muted" data-testid="waiting-for-host">
            {countdown === null ? 'waiting for host…' : `next round in ${countdown}…`}
          </p>
        )}
      </div>
    </div>
  )
}
