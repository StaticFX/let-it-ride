import { useCallback, useEffect, useState } from 'react'
import { useGameStore } from '../../state/gameStore'
import { useViewport } from '../../hooks/useViewport'
import { leaveGame } from '../../net/client'
import { SketchButton } from '../ui/Button'
import { VolumeControl } from '../ui/VolumeControl'
import { RulesPage } from '../rules/RulesPage'

export function EscapeMenu() {
  const { touch } = useViewport()
  const [open, setOpen] = useState(false)
  const [showRules, setShowRules] = useState(false)
  const [visible, setVisible] = useState(false)
  const phase = useGameStore((s) => s.state?.phase)
  const config = useGameStore((s) => s.state?.config)
  // The rules are being read at a live table, so they answer for this room's
  // rules rather than the catalog's defaults.
  const flip7Target = useGameStore((s) => s.state?.flip7Target)

  const canOpen = phase === 'PLAYING' || phase === 'ROUND_END'

  const openMenu = useCallback(() => {
    setOpen(true)
    setTimeout(() => setVisible(true), 20)
  }, [])

  const closeMenu = useCallback(() => {
    setVisible(false)
    setTimeout(() => setOpen(false), 300)
  }, [])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape') return
      if (showRules) {
        setShowRules(false)
        return
      }
      if (!canOpen) return
      if (open) closeMenu()
      else openMenu()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, canOpen, showRules, openMenu, closeMenu])

  function resume() {
    closeMenu()
  }

  function leave() {
    setVisible(false)
    setOpen(false)
    leaveGame()
  }

  if (showRules) {
    return <RulesPage onClose={() => setShowRules(false)} config={config} flip7Target={flip7Target} />
  }

  /**
   * The way in, for a table with no keyboard on it.
   *
   * This menu is the only place in a live game where the volume, the rules and
   * — the one that matters — *leaving cleanly* live, and until now the only way
   * to reach it was an Escape key. On a phone that made all three unreachable
   * for the whole life of a table, and the only way out was closing the tab,
   * which drops the seat mid-round and, if that tab happened to own the
   * animation gate, hands the table to the eight-second ceiling.
   *
   * Drawn from this component rather than from the board, so the button and the
   * sheet it opens cannot get out of step with each other, and so it is there
   * on the between-rounds screens too — which is where somebody actually stops
   * to change the volume.
   */
  if (!open) {
    // Drawn only where there is no key to press. A machine with a cursor has
    // one and has had one all along, and putting a button in the corner of a
    // wide felt would be furniture nobody there needs. The felt makes room for
    // this on exactly the same condition — see `viewport.touch` in GameBoard.
    if (!canOpen || !touch) return null
    return (
      <button
        onClick={openMenu}
        data-testid="open-pause"
        aria-label="pause"
        className="sketch-box tap-target fixed z-[95] rounded-[2px] display text-[18px] rotate-2"
        style={{
          top: 'calc(0.75rem + var(--safe-top))',
          right: 'calc(0.75rem + var(--safe-right))',
        }}
      >
        ⏸
      </button>
    )
  }

  return (
    <div
      className={`fixed inset-0 z-[500] bg-black/25 transition-opacity duration-200 ${visible ? 'opacity-100' : 'opacity-0'}`}
      onClick={resume}
      data-testid="escape-menu"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className={`
          sketch-box absolute top-[calc(2.5rem+var(--safe-top))] left-1/2 rounded p-6 max-w-[92vw] min-w-[280px] text-center
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
