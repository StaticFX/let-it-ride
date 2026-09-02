import { useGameStore } from '../../state/gameStore'
import { leaveGame } from '../../net/client'
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
      <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[600] sketch-box rounded px-4 py-2">
        <p className="text-muted sway-mid">reconnecting…</p>
      </div>
    )
  }

  if (!lost) return null

  return (
    <div className="fixed inset-0 z-[600] bg-black/30 flex items-center justify-center">
      <div className="sketch-box rounded p-8 text-center max-w-[320px]">
        <h2 className="mb-2 -rotate-1">{kicked ? 'removed' : 'disconnected'}</h2>
        <p className="text-muted mb-6">{error}</p>
        <SketchButton variant="primary" onClick={leaveGame}>back to menu</SketchButton>
      </div>
    </div>
  )
}
