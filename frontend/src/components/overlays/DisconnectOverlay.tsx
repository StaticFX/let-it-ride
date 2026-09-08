import { useGameStore } from '../../state/gameStore'
import { leaveGame, retryConnection } from '../../net/client'
import { SketchButton } from '../ui/Button'

/**
 * Covers the table when the socket drops mid-game. The client retries on its
 * own; this only appears once it has given up or the host removed the player.
 */
export function DisconnectOverlay() {
  const connection = useGameStore((s) => s.connection)
  const error = useGameStore((s) => s.error)
  const kicked = useGameStore((s) => s.kicked)
  const phase = useGameStore((s) => s.state?.phase)

  const reconnecting = connection === 'connecting' && !!phase && phase !== 'LOBBY'
  const lost = connection === 'disconnected' && (kicked || (!!error && !!phase && phase !== 'LOBBY'))

  if (reconnecting) {
    return (
      <div
        className="fixed bottom-[calc(1.5rem+var(--safe-bottom))] left-1/2 -translate-x-1/2 z-[600] sketch-box rounded px-4 py-2"
        data-testid="reconnecting"
      >
        <p className="text-muted sway-mid">reconnecting…</p>
      </div>
    )
  }

  if (!lost) return null

  return (
    <div className="fixed inset-0 z-[600] bg-black/30 flex items-center justify-center" data-testid="disconnected" data-kicked={kicked}>
      <div className="sketch-box rounded p-6 sm:p-8 text-center max-w-[min(320px,92vw)]">
        <h2 className="mb-2 -rotate-1">{kicked ? 'removed' : 'disconnected'}</h2>
        <p className="text-muted mb-6">{error}</p>
        <div className="flex flex-col items-center gap-2.5">
          {/* Somebody who was dropped still has a seat at that table — see
              [retryConnection]. Going back to the menu gives it up, and until
              now it was the only thing offered. */}
          {!kicked && (
            <SketchButton variant="primary" testId="try-again" onClick={retryConnection}>
              try again
            </SketchButton>
          )}
          <SketchButton
            variant={kicked ? 'primary' : 'ghost'}
            testId="back-to-menu"
            onClick={leaveGame}
          >
            back to menu
          </SketchButton>
        </div>
      </div>
    </div>
  )
}
