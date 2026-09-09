import type { Player } from '../../game/types'
import { theme } from '../../theme'
import { useElementSize } from '../../hooks/useElementSize'
import { RoughBox, RoughSquiggle } from '../ui/RoughShapes'
import { Pencil } from './Pencil'

interface ScoreboardProps {
  players: Player[]
  currentPlayerId: string
  localPlayerId: string | null
  /**
   * What a player's cards are worth to them, which is not always what they add
   * up to — see the "antimatter" card. Handed in rather than read off the
   * player, because the answer is the server's.
   *
   * Null when this seat's hand is face down to you — see the "redacted" card.
   * Told apart from nought, which is a seat with nothing.
   */
  worth: (player: Player) => number | null
  /**
   * Whether a seat's round is over *and* worth nothing, which is what the strike
   * through a number means here. Not the same question as "did they bust" — an
   * antimatter holder busts and pays for it anyway, and a struck-through minus
   * eighteen would be the felt telling them they got away with it. Handed in
   * because the answer is the server's; without one, every bust is written off
   * the way it always was.
   */
  writtenOff?: (player: Player) => boolean
  /**
   * What their total reads. Handed in for the same reason: while the table is
   * being paid it is not yet what the server has already banked — see the
   * payout in `useGame`.
   */
  total?: (player: Player) => number
  /**
   * The score this game is a race to, when it is one. A game played to a number
   * of rounds has no line to run at, so it gets no bar.
   */
  target?: number
  /** Handed each row as it mounts, so the payout knows where to fly to. */
  rowRef?: (playerId: string, element: HTMLDivElement | null) => void
  onReset?: () => void
}

/** How much of the way to the target is worth making something of. */
const CLOSE = 0.7
const BRINK = 0.9

export function Scoreboard({
  players, currentPlayerId, localPlayerId, worth, writtenOff, total, target, rowRef, onReset,
}: ScoreboardProps) {
  const ink = theme.ink
  const sw = theme.strokeWidth
  const { ref: wrapRef, size } = useElementSize<HTMLDivElement>()
  const lineColor = `${ink}10`

  return (
    <div style={{ position: 'relative' }}>
    <div ref={wrapRef} style={{
      width: 300,
      fontFamily: theme.fontBody, color: ink,
      padding: '16px 18px 12px 30px',
      borderRadius: 2,
      position: 'relative',
      transform: 'rotate(-1.2deg)',
      // Ruled lines — same pattern as PaperSheet in RulesPage
      background: `
        repeating-linear-gradient(
          transparent,
          transparent 27px,
          ${lineColor} 27px,
          ${lineColor} 28px
        ),
        linear-gradient(to bottom, ${theme.cardFace}, ${theme.cardFace})
      `,
      backgroundAttachment: 'local',
      boxShadow: `
        inset 0 0 40px rgba(0,0,0,0.04),
        inset 10px 0 20px -10px rgba(0,0,0,0.04),
        inset -10px 0 20px -10px rgba(0,0,0,0.04),
        inset 0 10px 15px -10px rgba(0,0,0,0.03),
        inset 0 -10px 15px -10px rgba(0,0,0,0.03),
        4px 5px 0 0 ${ink}
      `,
    }}>
      {/* Red margin line */}
      <div style={{
        position: 'absolute', top: 0, bottom: 0, left: 22,
        width: 1.5, background: 'rgba(192, 57, 43, 0.18)',
        zIndex: 0, pointerEvents: 'none',
      }} />

      {/* Wrinkle texture — diagonal creases + worn edges */}
      <svg style={{
        position: 'absolute', inset: 0, width: '100%', height: '100%',
        pointerEvents: 'none', zIndex: 0, borderRadius: 2,
      }}>
        <line x1="0" y1="28%" x2="100%" y2="31%" stroke={ink} strokeWidth="0.5" opacity="0.03" />
        <line x1="0" y1="64%" x2="100%" y2="61%" stroke={ink} strokeWidth="0.4" opacity="0.025" />
        <line x1="30%" y1="0" x2="32%" y2="100%" stroke={ink} strokeWidth="0.3" opacity="0.02" />
        <rect x="0" y="0" width="100%" height="3" fill={ink} opacity="0.02" />
        <rect x="0" y="0" width="3" height="100%" fill={ink} opacity="0.015" />
      </svg>

      {/* Corner fold — bottom-right */}
      <div style={{
        position: 'absolute', bottom: 0, right: 0,
        width: 20, height: 20,
        overflow: 'hidden', borderRadius: '0 0 2px 0',
        zIndex: 1, pointerEvents: 'none',
      }}>
        <div style={{
          position: 'absolute', bottom: 0, right: 0,
          width: 28, height: 28,
          background: `linear-gradient(135deg, transparent 48%, rgba(0,0,0,0.05) 49%, #f0ebd8 52%, #ece6d0 100%)`,
          boxShadow: '-1px -1px 3px rgba(0,0,0,0.06)',
        }} />
      </div>

      {/* -- Coffee stain -- top right -- */}
      <svg width="80" height="85" viewBox="0 0 80 85" style={{
        position: 'absolute', top: -8, right: -6,
        zIndex: 3, pointerEvents: 'none', overflow: 'visible',
      }}>
        <ellipse cx="40" cy="38" rx="30" ry="28"
          fill="none" stroke="rgba(120, 72, 28, 0.1)" strokeWidth="7" />
        <ellipse cx="41" cy="37" rx="27" ry="25.5"
          fill="none" stroke="rgba(120, 72, 28, 0.07)" strokeWidth="4" />
        <ellipse cx="39" cy="39" rx="23" ry="22"
          fill="none" stroke="rgba(120, 72, 28, 0.035)" strokeWidth="2.5" />
        <ellipse cx="40" cy="38" rx="16" ry="14"
          fill="rgba(120, 72, 28, 0.02)" stroke="none" />
        <path d="M 58 20 Q 68 32 65 48 Q 62 38 55 28 Z"
          fill="rgba(120, 72, 28, 0.04)" />
        <ellipse cx="54" cy="64" rx="7" ry="5"
          fill="rgba(120, 72, 28, 0.04)" stroke="none"
          transform="rotate(-10 54 64)" />
        <ellipse cx="50" cy="74" rx="4" ry="3"
          fill="rgba(120, 72, 28, 0.025)" stroke="none"
          transform="rotate(-15 50 74)" />
      </svg>

      {/* Rough border */}
      {size.h > 0 && (
        <RoughBox width={size.w} height={size.h}
          stroke={ink} strokeWidth={sw} roughness={2.2} boil={false} />
      )}

      <div style={{ position: 'relative', zIndex: 2 }}>
        {/* Title */}
        <div style={{
          display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
          marginBottom: 6,
        }}>
          <div style={{
            fontFamily: theme.fontDisplay, fontSize: 21,
            color: ink, fontWeight: 700, lineHeight: 1,
            transform: 'rotate(-0.5deg)',
          }}>Scoreboard</div>
          {onReset && (
            <button onClick={onReset} style={{
              background: 'transparent', border: 'none',
              color: theme.inkSoft, fontFamily: theme.fontDisplay,
              fontSize: 14, fontWeight: 700, cursor: 'pointer', padding: '2px 4px',
            }}>↻ reset</button>
          )}
        </div>

        {/* Separator */}
        <div style={{ position: 'relative', height: 8, marginBottom: 2 }}>
          <RoughSquiggle width={255} height={8}
            stroke={ink} strokeWidth={sw * 0.5}
            amplitude={1.2} segments={9} roughness={1.5} boil={false} />
        </div>

        {/* Column headers */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: '1fr 52px 48px',
          gap: 4, padding: '2px 2px 0',
        }}>
          <div style={{
            fontFamily: theme.fontDisplay, fontSize: 11,
            color: theme.inkSoft, fontWeight: 700,
            letterSpacing: '0.03em',
          }}>Player</div>
          <div style={{
            fontFamily: theme.fontDisplay, fontSize: 11,
            color: theme.inkSoft, fontWeight: 700,
            textAlign: 'right', letterSpacing: '0.03em',
          }}>Pts</div>
          <div style={{
            fontFamily: theme.fontDisplay, fontSize: 11,
            color: theme.inkSoft, fontWeight: 700,
            textAlign: 'right', letterSpacing: '0.03em',
          }}>Total</div>
        </div>

        {/* Thin line under headers */}
        <div style={{
          height: 1, margin: '3px 2px 2px',
          background: `linear-gradient(90deg, ${ink} 0%, ${ink} 70%, transparent 100%)`,
          opacity: 0.15,
        }} />

        {/* Player rows */}
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {players.map((p) => {
            const isCurrent = p.id === currentPlayerId
            const isBusted = p.status === 'bust'
            // Out is out — the name is struck either way. What the strike
            // through the *number* says is "and it came to nothing", which is
            // not true of every bust.
            const isDead = isBusted && (writtenOff?.(p) ?? true)
            const isStayed = p.status === 'stayed'
            const isMe = p.id === localPlayerId
            const isOut = isBusted || isStayed

            const score = total ? total(p) : p.score
            // How far along the run at the target this player is. No target, no
            // run: a game played to a number of rounds is not a race to a line.
            const run = target && target > 0 ? Math.max(0, Math.min(1, score / target)) : null
            const close = run !== null && run >= CLOSE
            const brink = run !== null && run >= BRINK

            return (
              <div
                key={p.id}
                ref={(element) => rowRef?.(p.id, element)}
                data-testid="score-row"
                data-player-id={p.id}
                data-close={close}
                data-brink={brink}
                style={{
                  display: 'grid',
                  gridTemplateColumns: '1fr 52px 48px',
                  alignItems: 'baseline',
                  gap: 4,
                  padding: '3px 2px',
                  opacity: isBusted ? 0.4 : isStayed ? 0.55 : 1,
                }}
              >
                {/* Name */}
                <div style={{ position: 'relative' }}>
                  <div style={{
                    fontFamily: theme.fontDisplay, fontSize: 17,
                    fontWeight: 700,
                    color: isBusted ? theme.actionAccent : ink,
                    lineHeight: 1.1,
                    textDecoration: isBusted ? 'line-through' : 'none',
                  }}>
                    {p.name}
                    {isMe && (
                      <span style={{
                        color: theme.inkSoft, fontWeight: 400,
                        marginLeft: 3, fontSize: 11,
                      }}>you</span>
                    )}
                    {/* Said out loud, because a bar that is nearly full is
                        something you want to be told rather than measure. */}
                    {brink && (
                      <span className="score-brink-tag" data-testid="match-point">match point</span>
                    )}
                  </div>
                  {/* Hand-drawn underline for current turn */}
                  {isCurrent && !isOut && (
                    <div style={{ position: 'relative', height: 5, width: '85%', marginTop: -1 }}>
                      <RoughSquiggle
                        width={Math.max(36, p.name.length * 9 + (isMe ? 20 : 0))}
                        height={5}
                        stroke={theme.actionAccent}
                        strokeWidth={sw * 0.65}
                        amplitude={1}
                        segments={4}
                        roughness={1.6}
                        boil={false}
                      />
                    </div>
                  )}
                </div>
                {/* Current points (hand value this round) */}
                <div style={{
                  fontFamily: theme.fontNumber, fontSize: 21, fontWeight: 700,
                  color: isBusted ? theme.actionAccent : theme.inkSoft,
                  textAlign: 'right', lineHeight: 1,
                  textDecoration: isDead ? 'line-through' : 'none',
                  // A dash is a seat with nothing in front of it; a question
                  // mark is a seat you are not allowed to look at.
                }}>{worth(p) === null ? '?' : worth(p) || '–'}</div>
                {/* Total score */}
                {/* Keyed on the number, so a total that changes is remounted
                    and lands rather than simply reading differently — which is
                    the whole of what a payout looks like from this end. */}
                <div
                  key={score}
                  className="score-total"
                  data-testid="score-total"
                  data-total={score}
                  style={{
                    fontFamily: theme.fontNumber, fontSize: 21, fontWeight: 700,
                    color: ink, textAlign: 'right', lineHeight: 1,
                  }}
                >{score}</div>

                {/* The run at the target, drawn under the whole row. */}
                {run !== null && (
                  <div
                    className="score-bar"
                    data-close={close}
                    data-brink={brink}
                    style={{ ['--run' as string]: `${Math.round(run * 100)}%`, gridColumn: '1 / -1' }}
                  >
                    <div className="score-bar-fill" />
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>

    {/* Pencil on the desk */}
    <Pencil style={{
      position: 'absolute',
      bottom: -14, right: -30,
      transform: 'rotate(25deg)',
      zIndex: 5,
    }} />
    </div>
  )
}
