import { useState } from 'react'
import { isMuted, setMuted } from '../../audio/sfx'

export function SoundToggle({ className = '' }: { className?: string }) {
  const [muted, setLocalMuted] = useState(isMuted)

  function toggle() {
    const next = !muted
    setMuted(next)
    setLocalMuted(next)
  }

  return (
    <button
      onClick={toggle}
      title={muted ? 'sound off' : 'sound on'}
      aria-label={muted ? 'turn sound on' : 'turn sound off'}
      className={`bg-transparent border-none cursor-pointer display text-xl leading-none p-1 transition-opacity ${
        muted ? 'opacity-40' : 'opacity-90'
      } ${className}`}
    >
      {muted ? '🔇' : '🔊'}
    </button>
  )
}
