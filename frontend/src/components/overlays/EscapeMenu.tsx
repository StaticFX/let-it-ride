import { useEffect, useState } from 'react'
import { useGameStore } from '../../state/gameStore'
import { leaveGame } from '../../net/client'
import { SketchButton } from '../ui/Button'
import { VolumeControl } from '../ui/VolumeControl'
import { RulesPage } from '../rules/RulesPage'

export function EscapeMenu() {
  const [open, setOpen] = useState(false)
  const [showRules, setShowRules] = useState(false)
  const [visible, setVisible] = useState(false)
  const phase = useGameStore((s) => s.state?.phase)
  const config = useGameStore((s) => s.state?.config)

  const canOpen = phase === 'PLAYING' || phase === 'ROUND_END'

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape') return
      if (showRules) {
        setShowRules(false)
        return
      }
      if (!canOpen) return
      if (open) {
        setVisible(false)
        setTimeout(() => setOpen(false), 300)
      } else {
        setOpen(true)
        setTimeout(() => setVisible(true), 20)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, canOpen, showRules])

  function resume() {
    setVisible(false)
    setTimeout(() => setOpen(false), 300)
  }

  function leave() {
    setVisible(false)
    setOpen(false)
    leaveGame()
  }

  if (showRules) return <RulesPage onClose={() => setShowRules(false)} config={config} />
  if (!open) return null

  return (
    <div
      className={`fixed inset-0 z-[500] bg-black/25 transition-opacity duration-200 ${visible ? 'opacity-100' : 'opacity-0'}`}
      onClick={resume}
      data-testid="escape-menu"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className={`
          sketch-box absolute top-10 left-1/2 rounded p-6 min-w-[280px] text-center
          transition-transform
          ${visible
            ? 'translate-x-[-50%] translate-y-0 duration-500 ease-[cubic-bezier(0.34,1.56,0.64,1)]'
            : 'translate-x-[-50%] -translate-y-[120%] duration-300 ease-in'}
        `}
      >
        <h2 className="mb-1 -rotate-1">paused</h2>
        <p className="text-muted mb-5">what do you want to do?</p>

        <div className="mb-5">
          <VolumeControl />
        </div>

        <div className="flex flex-col gap-2.5">
          <SketchButton variant="primary" testId="pause-resume" onClick={resume}>resume</SketchButton>
          <SketchButton variant="ghost" testId="pause-rules" onClick={() => setShowRules(true)}>rules</SketchButton>
          <SketchButton variant="ghost" testId="pause-leave" onClick={leave}>leave game</SketchButton>
        </div>
      </div>
    </div>
  )
}
