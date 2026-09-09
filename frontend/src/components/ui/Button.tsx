import { useState } from 'react'
import { theme } from '../../theme'
import { useElementSize } from '../../hooks/useElementSize'
import { play } from '../../audio/sfx'
import { RoughBox } from './RoughShapes'

/**
 * What a button is for, said in colour.
 *
 * Three fills and a way out, which is as many as a menu can hold before the
 * colours stop meaning anything: `primary` is the thing you came to this screen
 * to do, `secondary` is the other way in, `tertiary` is the sideshow, and
 * `ghost` is how you get back out of wherever you are. A screen with two
 * primaries on it has not decided what it is for.
 *
 * The colours themselves are the ones already on the table — the action red,
 * the passive green and the frost blue every card is drawn in — so a menu is
 * printed in the game's own ink rather than in a palette of its own.
 */
export type SketchButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'ghost'

const FILL: Record<SketchButtonVariant, string> = {
  primary: theme.actionAccent,
  secondary: theme.frost,
  tertiary: theme.passiveAccent,
  ghost: 'none',
}

interface SketchButtonProps {
  children: React.ReactNode
  onClick?: () => void
  disabled?: boolean
  variant?: SketchButtonVariant
  /** Fills the row it is in and sits up a size — what a menu is stacked out of. */
  block?: boolean
  className?: string
  /** Stable handle for the end-to-end suite; the label is decoration. */
  testId?: string
}

export function SketchButton({
  children, onClick, disabled, variant = 'primary',
  block = false, className = '', testId,
}: SketchButtonProps) {
  const [hovered, setHovered] = useState(false)
  const [pressed, setPressed] = useState(false)
  const { ref, size } = useElementSize<HTMLButtonElement>()

  const colour = FILL[variant]
  const filled = colour !== 'none'

  /**
   * Hovering a filled button turns it inside out — the ink and the paper swap
   * places and the label comes up in the colour the button was.
   *
   * A lift and a shadow say *something is under the cursor*; they do not say
   * *this one*. Inverting does, from across the room, and it costs a repaint of
   * one box. It is also the one state that cannot be mistaken for disabled,
   * which a button that merely dims towards on hover can.
   */
  const invert = filled && hovered && !disabled

  /**
   * A button that cannot be pressed is drawn in pencil, not in faded ink.
   *
   * Fading a filled one to four tenths leaves a pale pink or a pale blue —
   * still a colour, still saying "this is the red one", only quieter, which
   * reads as a rendering problem rather than as a state. Three of the four
   * buttons on the front door are disabled until a name is typed, so this is
   * the very first thing anybody sees the game do; it has to look deliberate.
   * Taking the colour out entirely and leaving the shape says *not yet* in a
   * way no amount of transparency does.
   */
  const fg = disabled ? theme.inkSoft : invert ? colour : filled ? theme.cardFace : theme.ink
  const stroke = disabled ? theme.inkSoft : invert ? colour : theme.ink
  const fill = disabled
    ? (filled ? 'rgba(31, 28, 20, 0.06)' : 'none')
    : invert
      ? theme.cardFace
      : filled
        ? colour
        : (hovered ? 'rgba(31, 28, 20, 0.06)' : 'none')

  // Filled buttons carry a heavier frame than ghosts do, for the same reason a
  // printed sticker has a heavier rule than a line of type: it has to hold a
  // block of colour in rather than just enclose some words.
  const sw = filled ? theme.strokeWidth * 1.2 : theme.strokeWidth
  const lift = block ? 6 : 5
  const shadow = filled ? lift : lift - 1

  return (
    <button
      ref={ref}
      data-testid={testId}
      disabled={disabled}
      // The brackets and the inversion are hung on this rather than on `:hover`,
      // and that is the whole of what keeps them off a touch screen: the state
      // below is only ever set for a mouse, so there is no `:hover` for iOS to
      // latch after a tap and nothing to wrap in a `(hover: hover)` query.
      data-hovered={hovered && !disabled ? 'true' : undefined}
      // Pointer events rather than mouse ones, so the press reads on a screen
      // being tapped as well as one being clicked — a synthesised mouse event
      // arrives after the finger has already come off, which is a button that
      // never looks pressed at all. `pointercancel` matters here: a tap that
      // turns into a scroll never gets its `up`.
      onPointerEnter={(e) => { if (e.pointerType === 'mouse') setHovered(true) }}
      onPointerLeave={() => { setHovered(false); setPressed(false) }}
      onPointerDown={() => setPressed(true)}
      onPointerUp={() => setPressed(false)}
      onPointerCancel={() => { setPressed(false); setHovered(false) }}
      onClick={() => {
        // Every button in the game routes through here, so the click is wired
        // once rather than at each call site.
        play('click')
        onClick?.()
      }}
      className={`sketch-btn ${block ? 'sketch-btn-block' : ''} ${className}`}
      style={{
        background: 'transparent',
        color: fg,
        border: 'none',
        // Read from the sheet so a narrow window can bring every button in the
        // game down a size at once — see the compact block in index.css.
        padding: block
          ? 'var(--button-block-pad, 13px 26px)'
          : 'var(--button-pad, 10px 26px)',
        minWidth: block ? 0 : 'var(--button-min-width, 110px)',
        width: block ? '100%' : undefined,
        fontFamily: theme.fontDisplay,
        fontSize: block
          ? 'var(--button-block-font-size, 27px)'
          : 'var(--button-font-size, 24px)',
        letterSpacing: '0.01em',
        fontWeight: 700,
        cursor: disabled ? 'not-allowed' : 'pointer',
        position: 'relative',
        // The tilt is a variable rather than a number so a stack of these can be
        // dealt out at four different angles without any of them knowing they
        // are in a stack — see `.menu-stack` in index.css. A column of buttons
        // all leaning one degree the same way is not hand-set, it is a tower.
        transform: pressed
          ? 'translate(2px, 2px) rotate(var(--btn-tilt, -1deg))'
          : hovered
            ? 'translate(-1px, -3px) rotate(var(--btn-tilt, -1deg))'
            : 'rotate(var(--btn-tilt, -1deg))',
        transition: 'transform 160ms cubic-bezier(.2,.9,.3,1.3), color 160ms, box-shadow 160ms',
        // Held up from the four tenths this used to be. The colour is already
        // gone by here — see [fg] — so the transparency no longer has to carry
        // "you cannot press this" on its own, and a label nobody can read is a
        // button nobody knows they are waiting on.
        opacity: disabled ? 0.65 : 1,
        boxShadow: disabled || pressed
          ? 'none'
          : `${hovered ? shadow + 2 : shadow}px ${hovered ? shadow + 2 : shadow}px 0 0 ${theme.ink}`,
        borderRadius: 4,
      }}
    >
      {size.w > 0 && (
        <RoughBox
          width={size.w} height={size.h}
          stroke={stroke} strokeWidth={sw} roughness={1.7}
          fill={fill === 'none' ? 'none' : fill}
          fillStyle="solid"
        />
      )}
      {/* The cursor's own marks, in a different material to everything else on
          the page: four crisp corners against a table drawn entirely in wobbly
          ink. That contrast is the point — the brackets read as something laid
          *over* the paper rather than as another thing printed on it, which is
          what a selection is. Kept for `:focus-visible` too, so arriving by
          keyboard is not a worse-lit version of arriving by mouse. */}
      <span className="btn-brackets" aria-hidden="true">
        <i /><i /><i /><i />
      </span>
      <span style={{ position: 'relative', zIndex: 2 }}>{children}</span>
    </button>
  )
}
