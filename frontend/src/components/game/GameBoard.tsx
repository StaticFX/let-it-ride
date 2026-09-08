import { useCallback, useEffect, useRef, useState } from 'react'
import type { Card as CardType, Player } from '../../game/types'
import { PAYOUT_FLIGHT_MS, useGame } from '../../hooks/useGame'
import { useViewport } from '../../hooks/useViewport'
import { fanOverlap, tableLayout } from './tableLayout'
import { findAction, findGambler, useCatalog } from '../../state/gameStore'
import { RoughBox } from '../ui/RoughShapes'
import { PlayingCard } from '../cards/PlayingCard'
import { CardBack } from '../cards/CardBack'
import { DealtCard } from '../cards/DealtCard'
import { retainDealtCards } from '../cards/dealtCards'
import { PlayerAvatar } from './PlayerAvatar'
import { Scoreboard } from './Scoreboard'
import { SpunHand } from './SpunHand'
import { TableNote } from './TableNote'
import { TableShader } from './TableShader'
import { TurnClock } from './TurnClock'
import { SketchButton } from '../ui/Button'
import { SoundToggle } from '../ui/SoundToggle'
import { RoundIntro } from '../overlays/RoundIntro'
import { RoundOutro } from '../overlays/RoundOutro'
import { BustedTape } from '../overlays/BustedTape'
import { ImpactParticles } from '../overlays/ImpactParticles'
import { Lucky7Overlay } from '../overlays/Lucky7Overlay'
import { SlotMachine } from '../overlays/SlotMachine'
import { FreezeBurst } from '../overlays/FreezeBurst'
import { DrawThreeStamp } from '../overlays/DrawThreeStamp'
import { StolenCard } from '../overlays/StolenCard'
import { ChoicePicker } from '../overlays/ChoicePicker'
import { CoinToss } from '../overlays/CoinToss'
import { SpinningBottle } from '../overlays/SpinningBottle'
import { TableSwirl } from '../overlays/TableSwirl'
import { SpinPreview, SPIN_TABLE_DEF_ID } from '../overlays/SpinPreview'
import { Showdown } from '../overlays/Showdown'
import { FizzleNote } from '../overlays/FizzleNote'
import { GamblerReveal } from '../overlays/GamblerReveal'
import { ResponseStack } from '../overlays/ResponseStack'
import { CounteredCard } from '../overlays/CounteredCard'
import { SecondLife } from '../overlays/SecondLife'
import { PointsFlight } from '../overlays/PointsFlight'
import { PointsAward } from '../overlays/PointsAward'
import { Shop } from '../overlays/Shop'

/*
 * Where every seat, pile and stage on the felt sits lives in [tableLayout]. The
 * table has two shapes — an arc on anything wide enough, and rows of seats on a
 * phone held upright — and working both out in one place is what keeps
 * [seatOfId] below the single mapping everything that flies between seats
 * measures from.
 *
 * What is left up here is the two animations that have to be told how much room
 * they have, because only the board knows where the seat they are aimed at ended
 * up.
 */

/**
 * How high above [anchorY] the card that busted somebody is held before it comes
 * down — `--bust-lift`, which `bustCard` measures its whole climb off.
 *
 * It cannot be a constant. The card is held at about 1.6 times deck size, which
 * is 227px tall, and a seat at the top of the arc sits barely a hundred pixels
 * from the top of the window: held the same distance above that seat as above
 * your own hand, the card that just ended somebody's round would hang mostly off
 * the screen, which is the one thing this animation exists not to do. So the
 * *place* it hangs is clamped rather than the distance, and the distance is
 * whatever is left over — at the top of the arc that is small or negative, and
 * the card hangs over the seat's hand instead of over its head.
 */
function bustLift(anchorY: number): number {
  const HANG_SCALE = 1.6
  const half = (142 * HANG_SCALE) / 2
  return anchorY - Math.max(anchorY - 138, half + 16)
}

/**
 * ...and the same question for the card being brought down on somebody, which
 * `targetSmash` winds up 156px above the seat before it falls.
 *
 * Same reasoning and a different number: the smash peaks at 1.52 times a deck
 * card, so 108px of it is above its own anchor before the lift is counted at
 * all. On a wide felt every seat is far enough down the screen for the full
 * wind-up to fit and nothing changes; on a narrow one the seats along the top
 * are a hundred pixels from the edge, and the beat this animation exists for —
 * the card hanging over somebody — was happening off the top of the screen.
 */
function smashLift(anchorY: number): number {
  return Math.max(40, Math.min(156, anchorY - 116))
}

/**
 * What the table says when it stops for a question nothing was drawn for. A
 * card explains itself by arriving; these have to say what they are.
 */
const DEFERRED_PROMPTS: Record<string, string> = {
  bust: 'who comes with you?',
  flipChoice: 'the bonus is yours — or theirs',
  flipTarget: 'take it off who?',
  throw: 'throw!',
  bet: 'bet a card, face down',
  buy: 'buy something',
  gambler: 'your call!',
  redirect: 'keep it, or hand it on?',
  secondOpinion: 'keep it, or take another?',
}

/**
 * Why a card did nothing, for the ones where the reason is worth saying. A card
 * with nobody to hit is the ordinary case and speaks for itself; these are the
 * ones a player would otherwise be left guessing about.
 */
const FIZZLE_REASONS: Record<string, string> = {
  comeback: 'only last place may throw',
  allIn: 'not enough hands to bet',
  mutate: 'nothing you can afford',
  suicideBomber: 'you are already armed',
  justOneMore: 'you already have one',
  unluckySeven: 'everybody has one already',
}

export function GameBoard() {
  const game = useGame()
  const catalog = useCatalog()
  const viewport = useViewport()
  const { w, h } = viewport
  /**
   * The seat the player is looking at.
   *
   * A hover on a machine with a cursor and a tap on one without — see
   * [focusSeat]. It is the same fact either way, which is why it is one piece
   * of state and not two: the seat you are paying attention to spreads its hand
   * out and steps forward, and everything else steps back.
   */
  const [hoveredPlayerId, setHoveredPlayerId] = useState<string | null>(null)
  const [inspectedCard, setInspectedCard] = useState<CardType | null>(null)
  /**
   * The option under the cursor while a card is asking a question, tagged with
   * the card that asked it.
   *
   * The tag is the whole reason this is not a plain string: the next spin off
   * the deck opens the same picker, and a hover left over from the last one
   * would draw an arrow over a question nobody has looked at yet.
   */
  const [hoveredOption, setHoveredOption] = useState<{ cardKey: string; option: string } | null>(null)
  // Where each player's line on the scoreboard is, so the round's points have
  // somewhere to land. Measured when a payout starts rather than kept in state:
  // the scoreboard does not move, and a rect in state is a rect that goes stale.
  const scoreRows = useRef(new Map<string, HTMLDivElement>())
  /**
   * ...and where the pad itself is, on a felt too narrow to leave it open.
   *
   * A round's points have to land somewhere they can be believed. With the
   * scoreboard folded up there is no line to land on, so they land on the thing
   * you would pick up to read it — which is the same answer, only shorter.
   */
  const scorePad = useRef<HTMLButtonElement>(null)
  const [scoresOpen, setScoresOpen] = useState(false)
  const measureScoreRow = useCallback(
    (playerId: string) =>
      scoreRows.current.get(playerId)?.getBoundingClientRect()
        ?? scorePad.current?.getBoundingClientRect()
        ?? null,
    [],
  )
  // Whether the felt is being drawn by the shader. If it is not — no WebGL2 —
  // the CSS vignette below goes back to carrying the turn on its own.
  const [feltLive, setFeltLive] = useState(false)

  const {
    players, me, others, currentPlayer, turnIndex, round, roundStartPlayer,
    deckCount, discardCount, localPlayerId, isMyTurn, isEliminated, mustDraw, cannotStay,
    isDealing, dealingPlayerId, pendingDef, isPickingTarget, pendingIsLocal, validTargets,
    targetChosen, pickTarget, hit, stay, animating,
    pendingOptions, needsChoice, seatIsImplied, optionChosen, pickOption,
    picksCards, picksNeeded, cardsChosen, canPickCard, canUnpickCard, pickCard,
    pendingIsDeferred, pendingPhase, validCards,
    picksFromCatalog, offers, purse, pickOffer,
    pendingAwaitingOthers, responders, answeredBy,
    worthOf, writtenOff, totalOf, award, animations, bust, flights, slots, dismissSlots,
    showRoundIntro, showRoundOutro, introUntil, outroUntil, timer,
    clockIsClose, clockUrgency,
  } = game

  // What is face-up on the table right now. Everything else is forgotten, so a
  // card that goes back to the discard pile deals in again the next time the
  // deck picks it up — see [retainDealtCards].
  const cardsOnTable = players.flatMap((p) => [...p.hand, ...p.passives]).map((c) => c.id).join(' ')
  useEffect(() => {
    retainDealtCards(cardsOnTable.split(' ').filter(Boolean))
  }, [cardsOnTable])

  /**
   * The whole felt's geometry, worked out from the window and the size of the
   * table sitting at it — see [tableLayout], which is where the arc and the
   * narrow table's rows both live.
   */
  const layout = tableLayout(viewport, others.length, players.length, game.isRollingRules)
  const deckCenter = layout.deck
  /** Where a card being played is held up before it is sent at a seat. */
  const cardStage = layout.cardStage
  /**
   * ...and where a pile of cards answering each other sits, which is higher.
   *
   * A stack is taller than one card and it comes with a button underneath it,
   * and at the card stage the two of them were printed straight across the
   * local player's hand.
   */
  const responseStage = layout.responseStage

  /**
   * Where a seat sits on screen. Anything that flies between seats measures
   * from here, so this is the only place the mapping may live.
   */
  function seatOfId(playerId: string) {
    if (playerId === me?.id) return layout.mine
    const seat = others.findIndex((p) => p.id === playerId)
    return layout.seat(Math.max(seat, 0), others.length)
  }

  /**
   * A thing of this size, centred as near [at] as it can be without hanging off
   * the felt.
   *
   * For what is thrown *at* a seat rather than for the seat itself — the frost
   * bloom is 190px across, the draw-three stamp 120, a stolen card 124 — and
   * deliberately not folded into [seatOfId], which has to keep answering where
   * the seat actually *is*. A card that flew to a clamped point would land
   * beside the hand it was taken from. On a wide felt no seat is near enough to
   * an edge for any of this to bite; on a narrow one the end of a row is twenty
   * pixels from it, and half of what happens to that player happened off the
   * screen.
   */
  function clampToFelt(at: { x: number; y: number }, width: number, height: number) {
    const halfW = Math.min(width, w) / 2
    const halfH = Math.min(height, h) / 2
    return {
      x: Math.min(Math.max(at.x, halfW), w - halfW),
      y: Math.min(Math.max(at.y, halfH), h - halfH),
    }
  }

  /** Where a bottle lands, and what every bearing is measured from. */
  const tableCenter = layout.centre

  /**
   * Degrees clockwise from north to [playerId]'s seat, as seen from the middle
   * of the table. Every client works this out from its own seat layout, which
   * is why the bottle's landing angle cannot be a constant.
   */
  function bearingTo(playerId: string) {
    const seat = seatOfId(playerId)
    return (Math.atan2(seat.y - tableCenter.y, seat.x - tableCenter.x) * 180) / Math.PI + 90
  }

  // ─── Animation lookups ───
  // A bust outranks a slam when both land in the same beat: the round ending
  // under somebody is the bigger of the two things that just happened.
  const shakes = animations.filter((a) => a.type === 'screenShake')
  const shakeClass = shakes.length === 0
    ? ''
    : shakes.some((s) => s.strength === 'bust') ? 'shake-bust' : 'shake'
  const impact = animations.find((a) => a.type === 'impact')
  const flip7 = animations.find((a) => a.type === 'flip7')
  const smashes = animations.filter((a) => a.type === 'smash')
  const freezes = animations.filter((a) => a.type === 'freeze')
  const drawThrees = animations.filter((a) => a.type === 'drawThree')
  const fizzles = animations.filter((a) => a.type === 'fizzled')
  const secondLives = animations.filter((a) => a.type === 'secondLife')
  const timedOutIds = animations.filter((a) => a.type === 'timeout').map((a) => a.playerId)
  const coinTosses = animations.filter((a) => a.type === 'coinFlip')
  const bottleSpins = animations.filter((a) => a.type === 'bottleSpin')
  const tableSpin = animations.find((a) => a.type === 'tableSpun')
  const tolls = animations.filter((a) => a.type === 'pointsTransferred')
  const showdown = animations.find((a) => a.type === 'showdown')
  const reveals = animations.filter((a) => a.type === 'gamblerPlayed')
  const counters = animations.filter((a) => a.type === 'countered')

  // Which copy of which card is asking. `cardId` is unique per physical card;
  // an older server sends only the def id, which is enough to tell one card's
  // question from the next one's.
  const pendingCardKey = game.pendingAction?.cardId ?? game.pendingAction?.cardDefId ?? ''
  /**
   * The direction the spin would go if the drawer committed to what they are
   * pointing at — the hover, or the call they have already made.
   *
   * Only ever a picture. Which directions exist and what either of them does
   * are the server's, and arrive in the card's own options.
   */
  const previewSpin =
    isPickingTarget && pendingIsLocal && game.pendingAction?.cardDefId === SPIN_TABLE_DEF_ID
      ? (hoveredOption?.cardKey === pendingCardKey ? hoveredOption.option : null) ?? optionChosen
      : null

  // Which of the local player's gambler cards the server says are live right
  // now. The client only ever reads this list — whether a window is open is a
  // rule, and rules do not live here.
  const playableGamblerIds = game.gamblerHand
    .filter((card) => game.canPlayGambler(card.id))
    .map((card) => card.id)
  const hasPlayable = playableGamblerIds.length > 0

  /**
   * How far [playerId]'s hand has to be thrown back for the spin, or null when
   * this seat is not part of it. The event lists the seats in table order, and
   * a hand that went "right" came from the id before it in that list.
   */
  function handSlide(playerId: string) {
    if (!tableSpin) return null
    const ids = tableSpin.playerIds
    const index = ids.indexOf(playerId)
    if (index < 0 || ids.length < 2) return null
    const fromId = tableSpin.direction === 'left'
      ? ids[(index + 1) % ids.length]
      : ids[(index - 1 + ids.length) % ids.length]
    const from = seatOfId(fromId)
    const own = seatOfId(playerId)
    return { dx: from.x - own.x, dy: from.y - own.y }
  }

  /**
   * Paying attention to a seat, on a screen with no cursor to do it with.
   *
   * The felt has one hover and it means one thing: *this is the seat I am
   * looking at*. It fans the hand out, steps the seat forward and steps the
   * rest of the table back, and on a machine with a mouse it costs nothing to
   * ask for — you get it by looking. A touch screen has no such thing, so the
   * tap that would otherwise do nothing is spent on it instead: tap a seat to
   * look at it, tap it again to look away, tap the felt to look away from all
   * of them.
   *
   * Only where the tap is not already spoken for. A seat that can be pointed at
   * takes the tap as the answer to the question on the table, which is the
   * whole reason it is lit — a card asking for a target is not the moment to
   * make somebody tap twice.
   */
  const focusSeat = (playerId: string) =>
    setHoveredPlayerId((id) => (id === playerId ? null : playerId))

  /**
   * ...and spending a card on something, which is the other half.
   *
   * A cursor arrives before a click does, so on a machine with one the table
   * has already said what you are about to hit by the time you hit it. A finger
   * arrives *as* the click, and the cards that ask a question are the ones you
   * cannot take back — a mis-tap on a seat is a strike on the wrong player and
   * a mis-tap on a card is the wrong card gone. So on a touch screen the first
   * tap arms and the second commits, and what is armed is marked exactly the
   * way a hover marks it. On a mouse nothing is ever armed and the first click
   * still commits, which is what it has always done.
   *
   * One piece of state for seats and cards together, because only one question
   * is ever on the table and it can only be pointed at one thing at a time.
   */
  const [armed, setArmed] = useState<{ cardKey: string; id: string } | null>(null)
  // Tagged with the card that asked, and read back through that tag rather than
  // cleared when the question goes — the same trick [hoveredOption] uses just
  // above, and for the same reason: the next card off the deck asks the next
  // question, and a seat still armed from the last one is armed for a card
  // nobody has looked at yet.
  const armedId = armed?.cardKey === pendingCardKey ? armed.id : null
  const setArmedId = (id: string | null) =>
    setArmed(id === null ? null : { cardKey: pendingCardKey, id })
  const armedFor = (id: string) => (viewport.touch && armedId === id ? true : undefined)

  /** Whether a tap on [id] should commit, or only arm. */
  function commitsOnTap(id: string): boolean {
    return !viewport.touch || armedId === id
  }

  function seatPointerProps(playerId: string, targetable: boolean, onPick: () => void) {
    if (viewport.touch) {
      return {
        onClick: targetable
          ? () => (commitsOnTap(playerId) ? onPick() : setArmedId(playerId))
          : () => focusSeat(playerId),
      }
    }
    return {
      onClick: targetable ? onPick : undefined,
      onMouseEnter: () => setHoveredPlayerId(playerId),
      onMouseLeave: () => setHoveredPlayerId((id) => (id === playerId ? null : id)),
    }
  }

  const showButtons = isMyTurn && !isEliminated && !isPickingTarget
  /**
   * Whether the rest of this round is something you are only watching — see
   * [BustedTape], which is the whole of what it looks like.
   *
   * Your own bust animation is allowed to finish first. The state says `bust`
   * from the moment the event arrives, which is while the card that did it is
   * still being carried up over the hand, and draining the colour there would
   * answer the question the animation is in the middle of asking. So it waits
   * for [bust] to clear, which is the beat after the hand has scattered — the
   * colour goes as the last card leaves. Somebody *else* busting never holds it
   * up, and a tab that reconnects into a round it is already out of gets it
   * straight away, there being nothing left to watch.
   *
   * Going out by choice is not this. Staying is a decision that paid, and a
   * seat that took it is not out of the game so much as done with the round.
   */
  const bustedOut = me?.status === 'bust' && bust?.playerId !== me.id
  const canInspect = !isPickingTarget
  // Somebody is on the clock, so every other seat can step back — a lit seat
  // only reads as lit if the ones around it are not.
  const someoneOnClock = !isDealing && !isPickingTarget && currentPlayer?.status === 'active'
  const myHovered = hoveredPlayerId === me?.id
  // On a screen with no cursor your own hand is open by default. Folded away it
  // is drawn at 0.75 of `small`, which is a 39x57 card behind a 22px strip of
  // its neighbour — small enough to read at a glance and far too small to aim
  // a finger at, and there is no hover to open it with.
  const mySpread = isMyTurn || myHovered || viewport.touch
  // How hard the felt should be breathing. Only your own clock counts — the
  // table stays still while somebody else is thinking. Whether the clock is
  // close at all belongs to [useGame], which is also what sounds the warning.
  const feltUrgency = isMyTurn ? clockUrgency : 0


  /**
   * How a card in `playerId`'s hand should be treated by the bust animation.
   *
   * Live through the hover as well as the reveal, which is the point of having a
   * hover at all: while the card that did it is being held up over the seat, the
   * card it is about to land on is already lit and the rest of the hand has
   * stepped back. What is coming is legible before it arrives.
   *
   * Nothing is dimmed once the hand is in the air — `bustFlyUp` drives opacity
   * itself and would override the dim anyway, so a dimmed card would brighten at
   * the exact moment it left, which reads as two different cards.
   */
  function bustRole(playerId: string, cardId: string): 'none' | 'match' | 'other' {
    if (bust?.playerId !== playerId) return 'none'
    if (!bust.cardId && !bust.matchedId) return 'none'
    if (cardId === bust.cardId || cardId === bust.matchedId) return 'match'
    return bust.phase === 'scatter' ? 'none' : 'other'
  }

  /**
   * Whether this card is the one currently being carried over the hand, and so
   * is not in the hand yet as far as the table is concerned. The fan still
   * reserves its place — it is the layout that holds still while the card comes
   * down into it, not the card that shoulders its way in afterwards.
   */
  const inFlight = (cardId: string) => bust?.phase === 'hover' && bust.cardId === cardId

  const scattering = (playerId: string) => bust?.playerId === playerId && bust.phase === 'scatter'
  const isFrozen = (playerId: string) => freezes.some((f) => f.playerId === playerId)

  function statusBadge(player: Player) {
    if (player.status === 'bust') return <span className="status-badge text-[var(--accent)] border border-[var(--accent)]">bust!</span>
    if (player.status === 'stayed') return <span className="status-badge border border-[var(--ink)]">out</span>
    if (!player.connected) return <span className="status-badge border border-[var(--ink-soft)] text-[var(--ink-soft)]">away</span>
    return null
  }

  /**
   * How a card behaves while a card-picking prompt is on the table: the ones it
   * may be pointed at light up and take the click, the ones it may not step
   * back, and inspecting is out of the way until the pick is made.
   *
   * Null when nothing is being picked, which is the ordinary case — the card
   * then behaves exactly as it always has.
   */
  /**
   * Whether this hand is currently being picked from, and so has to be laid out
   * flat instead of fanned. In a fan every card but the last is half covered by
   * the next one along, which is fine to read and hopeless to aim at — and a
   * prompt that asks for a card has to make every card its own target.
   */
  function isLaidOut(player: Player) {
    return picksCards && [...player.hand, ...player.passives].some((c) => validCards.includes(c.id))
  }

  // Note that a hand being picked from is already laid *flat* rather than
  // fanned, which is what makes a card in one aimable at all: in a fan every
  // card but the last is a 22px strip with the wrong card immediately beside
  // it, so a miss is not a no-op — it picks the neighbour.

  function pickState(card: CardType) {
    if (!picksCards) return null
    const picked = cardsChosen.includes(card.id)
    const canPick = canPickCard(card.id)
    // A card already picked stays clickable while the answer is unfinished —
    // clicking it is how you take it back.
    const canUnpick = canUnpickCard(card.id)
    return {
      picked,
      canPick,
      canUnpick,
      armed: armedFor(card.id),
      className: picked
        ? `card-picked ${canUnpick ? 'card-takeable' : ''}`
        : canPick ? 'card-pickable' : 'card-unpickable',
      onClick: canPick || canUnpick
        ? (e: React.MouseEvent) => {
            e.stopPropagation()
            // Taking a card back out of an answer is already reversible, so it
            // goes in on the first tap. Putting one in is not — see [armedId].
            if (picked || commitsOnTap(card.id)) pickCard(card.id)
            else setArmedId(card.id)
          }
        : undefined,
    }
  }

  /**
   * How far each card in a row is pulled back over the one before it.
   *
   * The fan has always had a fixed overlap per size, which is right until the
   * row is wider than the felt it is lying on: a hand of eight normal cards is
   * 452px across at the overlap this game was written with, and a phone held
   * upright is 390. So the overlap the hand *wants* is the starting point and
   * the width it has to fit in is the limit — see [fanOverlap], which tightens
   * one against the other and stops once a card would be all but hidden.
   *
   * The passives are counted in it because they are on the same line: the row
   * in front of a seat is the hand and the modifier row and a gap between them,
   * and fitting the hand alone would push the ×2 off the edge instead.
   */
  function fanMargin(player: Player, size: 'small' | 'normal', spread: boolean, width: number) {
    const preferred = size === 'normal' ? (spread ? -32 : -38) : spread ? -18 : -30
    const cardWidth = size === 'normal' ? 92 : 52
    const count = player.hand.length + player.passives.length
    return fanOverlap(count, cardWidth, width, preferred)
  }

  function handCard(
    player: Player, card: CardType, idx: number,
    size: 'small' | 'normal', spread: boolean, margin: number,
  ) {
    const role = bustRole(player.id, card.id)
    const pick = pickState(card)
    const laidOut = isLaidOut(player)
    const scatter = scattering(player.id)
    const fanAngle = laidOut ? 0 : (idx - (player.hand.length - 1) / 2) * (spread ? (size === 'normal' ? 5 : 6) : 4)

    return (
      <div
        key={card.id}
        onClick={pick
          ? pick.onClick
          : canInspect ? (e) => { e.stopPropagation(); setInspectedCard(card) } : undefined}
        data-testid="hand-card"
        data-card-id={card.id}
        data-card-kind={card.kind}
        data-card-label={card.label}
        data-pickable={pick?.canPick}
        data-picked={pick?.picked}
        data-unpickable={pick?.canUnpick}
        data-armed={pick?.armed}
        className={`origin-bottom cursor-pointer ${pick?.className ?? ''}`}
        style={{
          marginLeft: idx === 0 ? 0 : laidOut ? 3 : margin,
          transform: `rotate(${fanAngle}deg) translateY(${Math.abs(fanAngle) * 0.7}px)`,
          transition: 'margin-left 280ms ease, transform 280ms cubic-bezier(.2,.9,.3,1.3)',
        }}
      >
        <div
          className={role === 'match' ? 'relative bust-match' : role === 'other' ? 'bust-dim' : undefined}
          style={scatter
            ? {
                '--bust-spin': `${(idx % 2 === 0 ? -1 : 1) * (15 + idx * 5)}deg`,
                // The stagger stops at the fifth card so the last one still
                // clears the screen inside BUST_SCATTER_MS. Left uncapped, a
                // big hand's back cards had their flight cut off and snapped
                // back onto the table at full opacity.
                animation: `bustFlyUp 780ms ${Math.min(idx, 4) * 52}ms cubic-bezier(.2,0,.6,1) forwards`,
              } as React.CSSProperties
            // Not `display: none` and not unmounted: the fan has to keep the gap
            // the card is coming down into, and `DealtCard` has to be left alone
            // to settle it there while nobody is looking.
            : inFlight(card.id) ? { visibility: 'hidden' } : undefined}
        >
          <DealtCard card={card} from={deckCenter}>
            <PlayingCard card={card} size={size} />
          </DealtCard>
          {/* Name the clash on the newer of the two cards only — and only when
              there was one. A hand that went over the threshold has a card that
              did it and nothing it collided with, and the tag was naming the
              card against itself. */}
          {role === 'match' && card.id === bust?.cardId && !!bust?.matchedId && (
            <div className="bust-match-tag">same {card.label}!</div>
          )}
        </div>
      </div>
    )
  }

  return (
    <div
      className={`game-shell ${shakeClass}`}
      // Looking away. A tap on the felt itself — never on anything drawn on it,
      // which is what the target check is for — puts down whatever was armed
      // and stops looking at whatever seat was being read.
      onClick={(e) => {
        if (e.target !== e.currentTarget) return
        setArmedId(null)
        setHoveredPlayerId(null)
      }}
      data-testid="game-board"
      data-round={round}
      data-my-turn={isMyTurn}
      data-dealing={isDealing}
      data-picking-target={isPickingTarget}
      data-my-status={me?.status ?? 'none'}
      data-mode={game.isRollingRules ? 'rollingRules' : 'classic'}
      // Which of the two tables is being drawn, and whether it is being pointed
      // at rather than hovered — so a spec can assert the branch it means to be
      // testing actually engaged, instead of inferring it from a viewport size
      // and being wrong the day the threshold moves.
      data-compact={layout.compact}
      data-touch={viewport.touch}
      data-responding={game.canPass}
      data-purse={game.purse}
      data-minted-gamblers={game.state?.mintedGamblers ?? 0}
    >
      {showRoundIntro && introUntil && (
        <RoundIntro
          round={round}
          startingPlayerName={players[roundStartPlayer]?.name ?? '...'}
          untilMs={introUntil}
        />
      )}

      {showRoundOutro && outroUntil && (
        <RoundOutro
          round={round}
          winner={players.find((p) => p.id === game.state?.roundWinnerId) ?? null}
          points={game.state?.roundDeltas[game.state?.roundWinnerId ?? ''] ?? 0}
          flip7={!!game.state?.flip7PlayerId}
          flipTarget={game.state?.flip7Target ?? catalog?.flip7Target ?? 7}
          untilMs={outroUntil}
        />
      )}

      {/* The table itself. Bottom of the stack and pointer-transparent, so the
          shader can do what it likes without ever touching the UI above it.
          A bust shakes the whole shell rather than rippling the felt; the
          shader's ripple channel is there for whatever wants it next. */}
      <TableShader myTurn={isMyTurn} urgency={feltUrgency} onReady={setFeltLive} />

      {/* It is your move — say so with the whole screen, not just the corner.
          The shader draws this when it is up; this is the fallback. */}
      {!feltLive && (
        <div
          className={`turn-vignette ${isMyTurn ? 'is-active' : ''} ${clockIsClose ? 'is-urgent' : ''}`}
          data-testid="turn-vignette"
          data-active={isMyTurn}
        >
          <div className="turn-vignette-glow" />
        </div>
      )}

      {/* Outer frame. The second, fainter box is the felt's own margin note and
          the first thing a narrow window gives up — two nested frames spend
          22px either side of a screen that only has 390 of them. */}
      <div className="absolute inset-0 pointer-events-none z-[1]">
        <div className="absolute inset-3.5">
          <RoughBox width={w - 28} height={h - 28} stroke="var(--ink)" strokeWidth={2} roughness={2.0} boil={false} />
        </div>
        {!layout.compact && (
          <div className="absolute inset-[22px] opacity-55">
            <RoughBox width={w - 44} height={h - 44} stroke="var(--ink)" strokeWidth={1} roughness={2.4} boil={false} />
          </div>
        )}
      </div>

      {/* Top-left */}
      <div
        className="absolute z-[55] flex items-center gap-3"
        style={{ top: `calc(${layout.compact ? 20 : 28}px + var(--safe-top))`, left: `calc(${layout.gutter}px + var(--safe-left))` }}
      >
        <h1 className={`-rotate-[1.5deg] sway-slow ${layout.compact ? 'text-[24px]' : 'text-[38px]'}`}>let it ride</h1>
        <SoundToggle className={layout.compact ? '' : 'mt-1.5'} />
      </div>

      {/* Top-right */}
      <div
        className="absolute z-[55] text-right flex flex-col items-end gap-1 max-w-[52%]"
        style={{
          top: `calc(${layout.compact ? 18 : 28}px + var(--safe-top))`,
          // Room for the pause button, which is only drawn where there is no
          // Escape key to press — see [EscapeMenu], which decides on the same
          // fact so the two cannot get out of step.
          right: `calc(${layout.gutter + (viewport.touch ? 50 : 0)}px + var(--safe-right))`,
        }}
      >
        <small className="rotate-1 block" data-testid="round-label">round {String(round).padStart(2, '0')}</small>
        <div
          className={`display leading-none flex items-center gap-2.5 justify-end rotate-1 sway-mid ${
            layout.compact ? 'text-[20px]' : 'text-[30px]'
          }`}
          data-testid="turn-name"
        >
          <span className="text-[var(--accent)]">→</span>
          {/* A name long enough to run off a phone is cut rather than allowed
              to push the whole block over the felt's own border. */}
          <span className="truncate">
            {isPickingTarget
              ? responders.length > 1
                ? 'everyone'
                : players.find((p) => p.id === game.pendingAction?.playerId)?.name ?? '...'
              : isDealing ? 'dealing…' : currentPlayer?.name || '...'}
          </span>
        </div>
        <small className="rotate-1 block" data-testid="turn-prompt">
          {isPickingTarget
            ? pendingIsLocal
              ? pendingIsDeferred
                ? DEFERRED_PROMPTS[pendingPhase] ?? 'your call!'
                : picksCards
                  ? `pick ${picksNeeded - cardsChosen.length} more card${picksNeeded - cardsChosen.length === 1 ? '' : 's'}!`
                  : needsChoice ? 'your call!' : 'pick a target!'
              // Answered, and the rest of the table has not. Nobody is told
              // what anybody said until every one of them is in.
              : pendingAwaitingOthers
                ? `waiting on ${responders.length - answeredBy.length} more…`
                : picksCards ? 'is picking cards…' : needsChoice ? 'is choosing…' : 'is picking a target…'
            : isDealing ? '' : isMyTurn ? 'your move!' : 'to act…'}
        </small>
        {timer && !clockIsClose && <TurnClock timer={timer} />}
      </div>

      {/* The last ten seconds, up where the deck is */}
      {timer && clockIsClose && (
        <div
          className="absolute left-1/2 z-[60] flex -translate-x-1/2 -translate-y-full flex-col items-center gap-1 pointer-events-none"
          // Above the deck, but never off the top of the felt: on a phone held
          // sideways the piles stand at the top of the band and there is no
          // ninety-six pixels above them to hang a clock in.
          style={{ top: Math.max(deckCenter.y - 96, 108) }}
          data-testid="deck-clock"
        >
          <TurnClock timer={timer} size="lg" />
          <small className={`display whitespace-nowrap ${timer.remainingMs <= 5000 ? 'text-[var(--accent)]' : ''}`}>
            {isMyTurn ? 'your move — quick!' : `${currentPlayer?.name ?? 'someone'} is running out`}
          </small>
        </div>
      )}

      {/* Center piles. Placed off the same number [DealtCard] flies a card from,
          so the card that leaves the deck leaves the deck. */}
      <div
        className={`absolute flex items-center z-[4] ${layout.compact ? 'gap-5' : 'gap-[38px]'}`}
        style={{
          left: layout.piles.x,
          top: layout.piles.y,
          // One transform rather than the two utilities this used to carry.
          // The order matters: scaled about its own middle first, then pulled
          // back over that middle by half its *unscaled* box — which lands the
          // row's centre exactly on the point above, whatever the scale, and
          // keeps the deck's own middle half a scaled row to the left of it.
          // That is the number [tableLayout] hands out as `deck`, and it is
          // where every dealt card is flown from.
          transform: `translate(-50%, -50%) scale(${layout.pilesScale})`,
        }}
      >
        <div
          className={`relative ${isMyTurn ? 'cursor-pointer' : 'cursor-default'}`}
          onClick={isMyTurn ? hit : undefined}
          data-testid="draw-pile"
          data-count={deckCount}
        >
          {[0, 1, 2, 3].map((i) => (
            <div
              key={i}
              style={{
                position: i === 3 ? 'relative' : 'absolute',
                top: i === 3 ? 0 : -i * 2,
                left: i === 3 ? 0 : i * 2,
                transform: i === 3 ? 'rotate(0deg)' : `rotate(${(i * 2 - 3) * 1.2}deg)`,
              }}
            >
              <CardBack size="deck" />
            </div>
          ))}
          <div className="absolute -bottom-7 left-1/2 -translate-x-1/2 -rotate-1 display text-base whitespace-nowrap">
            {String(deckCount).padStart(2, '0')} · draw
          </div>
        </div>
        <div className="relative w-[108px] h-[152px] flex items-center justify-center" data-testid="discard-pile" data-count={discardCount}>
          <RoughBox width={108} height={152} stroke="var(--ink)" strokeWidth={1.8} roughness={2.0} dashed boil={false} />
          <div className="display text-[17px] text-[var(--ink-soft)] text-center leading-tight -rotate-3">
            discard<br />{discardCount}
          </div>
        </div>
      </div>

      {/* Other players */}
      {others.map((p, i) => {
        const seatPos = layout.seat(i, others.length)
        const crowded = layout.seatScale
        const pIndex = players.findIndex((pl) => pl.id === p.id)
        const isActive = !isDealing && !isPickingTarget && pIndex === turnIndex && p.status === 'active'
        const isBeingDealt = dealingPlayerId === p.id
        const dimmed = (p.status === 'stayed' || p.status === 'bust') && !isBeingDealt
        const backgrounded = someoneOnClock && !isActive && p.status === 'active'
        // A card that asks a question resolves on its own drawer, so there is
        // nothing to point at and the seats stay out of it — see [seatIsImplied].
        const targetable = pendingIsLocal && !seatIsImplied && !targetChosen && validTargets.includes(p.id)
        const targetHovered = targetable && (hoveredPlayerId === p.id || armedId === p.id)
        const spread = hoveredPlayerId === p.id
        const slide = handSlide(p.id)
        const seatFan = fanMargin(p, 'small', spread, layout.seatHandWidth)

        return (
          <div
            key={p.id}
            {...seatPointerProps(p.id, targetable, () => pickTarget(p.id))}
            data-testid="seat"
            data-player-id={p.id}
            data-player-name={p.name}
            data-status={p.status}
            data-hand-value={worthOf(p)}
            data-hand-size={p.hand.length}
            data-passive-count={p.passives.length}
            data-gambler-count={game.gamblerCounts?.[p.id] ?? 0}
            data-gambler-slots={game.state?.gamblerLimits?.[p.id] ?? 0}
            data-targetable={targetable}
            data-armed={armedFor(p.id)}
            data-active={isActive}
            data-bot={p.isBot}
            style={{
              position: 'absolute',
              left: seatPos.x,
              top: seatPos.y,
              // The crowd's scale multiplies the seat's own rather than
              // replacing it: a seat being dealt to at a table of ten still
              // steps forward, it simply steps forward from smaller.
              transform: `translate(-50%, -50%) scale(${
                crowded * (isBeingDealt ? 1.12 : targetHovered ? 1.15 : isActive ? 1.09 : 1)
              })`,
              // Published so the badges inside can divide it back out — a seat
              // shrinking is the point, "bust!" at six pixels is not.
              '--seat-scale': crowded,
              // A targetable seat has to sit above the local player's bar
              // (z-8), or on a short window the bar swallows the click.
              zIndex: targetable ? 30 : isActive || isBeingDealt ? 10 : 3,
              // A seat you can point at is lit even when its round is over.
              // Cards that take something reach a seat that is already out —
              // its hand is still points and its modifier row is still cards —
              // so dimming it for being out would say the opposite of what the
              // server just offered.
              opacity: targetable ? 1 : dimmed ? 0.45 : isPickingTarget ? 0.3 : backgrounded ? 0.7 : 1,
              transition: 'opacity 280ms, transform 350ms cubic-bezier(.2,.9,.3,1.3)',
              cursor: targetable ? 'crosshair' : isPickingTarget ? 'not-allowed' : 'default',
            } as React.CSSProperties}
          >
            <div
              className={`flex flex-col items-center gap-2 ${isFrozen(p.id) ? 'frozen-seat' : ''}`}
              style={{ animation: impact?.targetId === p.id ? 'impactShake 500ms ease-out' : 'none' }}
            >
              <div className={`hover-glow ${targetHovered ? 'opacity-100' : 'opacity-0'}`} />
              {isActive && <div className="turn-halo" />}
              <div className="flex items-center gap-2.5 relative z-[1]">
                <PlayerAvatar
                  initial={p.name.charAt(0)}
                  active={isActive || isBeingDealt || targetHovered}
                  onTurn={isActive}
                  id={i + 1}
                />
                <div className="text-left">
                  <div className="display text-[22px] leading-none relative">
                    {p.name}
                    {p.isBot && <small className="ml-1 font-normal">bot</small>}
                    {bust?.playerId === p.id && <div className="bust-strike" />}
                  </div>
                  <div className="flex items-center gap-2 mt-0.5">
                    <span className={`number text-[22px] leading-none ${p.status === 'bust' ? 'text-[var(--accent)]' : ''}`}>
                      {worthOf(p)}
                    </span>
                    {statusBadge(p)}
                    {timedOutIds.includes(p.id) && <span className="status-badge border border-[var(--ink-soft)]">timed out</span>}
                    {/* Deliberately not gated on the seat still being in the
                        round: a seat that is out can be a legal target now, so
                        one that is not has a reason worth saying out loud. */}
                    {isPickingTarget && pendingIsLocal && !seatIsImplied && !targetable && (
                      <span className="status-badge border border-[var(--ink-soft)] text-[var(--ink-soft)]">
                        {p.hand.length === 0 ? 'no cards' : 'no target'}
                      </span>
                    )}
                  </div>
                </div>
              </div>

              <SpunHand spinId={tableSpin?.id ?? null} dx={slide?.dx ?? 0} dy={slide?.dy ?? 0}>
                <div
                  className="flex min-h-[86px] px-1.5 py-0.5 cursor-pointer origin-bottom transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)]"
                  style={{ transform: spread ? 'scale(1.15)' : 'scale(1)' }}
                >
                  {p.hand.map((card, idx) => handCard(p, card, idx, 'small', spread, seatFan))}
                  {p.passives.length > 0 && (
                    <>
                      <div className="w-2.5 shrink-0" />
                      {p.passives.map((card, idx) => {
                        const pick = pickState(card)
                        return (
                        <div
                          key={card.id}
                          onClick={pick
                            ? pick.onClick
                            : canInspect ? (e) => { e.stopPropagation(); setInspectedCard(card) } : undefined}
                          data-testid="passive-card"
                          data-card-id={card.id}
                          data-card-def-id={card.defId}
                          data-pickable={pick?.canPick}
                          data-picked={pick?.picked}
                          data-unpickable={pick?.canUnpick}
                          data-armed={pick?.armed}
                          className={`card-fan-transition opacity-85 cursor-pointer ${pick?.className ?? ''}`}
                          style={{
                            marginLeft: idx === 0 ? 0 : isLaidOut(p) ? 3 : seatFan,
                            transform: isLaidOut(p)
                              ? 'none'
                              : `rotate(${(idx - (p.passives.length - 1) / 2) * 3}deg)`,
                          }}
                        >
                          <DealtCard card={card} from={deckCenter}>
                            <PlayingCard card={card} size="small" dimmed={dimmed} />
                          </DealtCard>
                        </div>
                        )
                      })}
                    </>
                  )}
                </div>
              </SpunHand>

              {/* What they are carrying, and only how much of it.
                  Backs rather than nothing at all: the cap is public, so a seat
                  that is full has to look full, and a rack that fills up over
                  an evening is most of what makes the mode readable from across
                  the table. Never wrapped in [DealtCard] — these were not dealt
                  from the pile in front of you. */}
              {game.isRollingRules && (game.gamblerCounts?.[p.id] ?? 0) > 0 && (
                <div
                  className="flex items-center justify-center relative mt-1"
                  data-testid="gambler-backs"
                  data-count={game.gamblerCounts?.[p.id] ?? 0}
                >
                  {Array.from({ length: game.gamblerCounts?.[p.id] ?? 0 }).map((_, idx) => (
                    <div
                      key={idx}
                      className="relative"
                      style={{
                        marginLeft: idx === 0 ? 0 : -34,
                        // Big enough to count from across the table, which is
                        // the whole job of a rack you cannot read.
                        transform: `scale(0.78) rotate(${(idx - 2) * 3}deg)`,
                        transformOrigin: 'center',
                      }}
                    >
                      <CardBack size="small" variant="gambler" />
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )
      })}

      {/* My hand */}
      {me && (() => {
        const targetable = pendingIsLocal && !seatIsImplied && !targetChosen && validTargets.includes(me.id)
        const targetHovered = targetable && (hoveredPlayerId === me.id || armedId === me.id)
        const dimmed = !isMyTurn && !isEliminated && !isDealing
        const slide = handSlide(me.id)
        // Your own seat gets the same halo and ring as everyone else's. The
        // vignette already says the move is yours; this says which of the seats
        // on screen that means, in the shape you have been reading all round.
        const myTurnNow = isMyTurn && !isEliminated
        // The hand is laid out at the size it is *about* to be drawn at, so a
        // fan that fits while it is folded away does not burst its banks the
        // moment your turn comes round and it opens.
        const myFan = fanMargin(me, mySpread ? layout.myCardSize : 'small', mySpread, layout.handWidth)

        return (
          <div
            // Transparent to the pointer across its whole width — only what is
            // actually drawn in it takes a tap. It is a full-width bar sitting
            // over the bottom of the felt, and on a phone held sideways the
            // draw pile is down in that same corner: without this the bar would
            // quietly swallow every tap meant for the deck.
            className="absolute bottom-0 left-0 right-0 flex flex-col items-center z-[8] pointer-events-none"
            style={{
              // The buttons' room is reserved on a narrow table whether or not
              // they are showing: everything above the hand is placed off the
              // bottom of the screen, and a felt that shuffled itself up and
              // down as turns changed hands would be a felt nobody could learn.
              paddingBottom: layout.compact
                ? `calc(${layout.buttonsHeight}px + var(--safe-bottom))`
                : showButtons ? 90 : 14,
              transition: 'padding-bottom 520ms cubic-bezier(.2,.9,.25,1.25)',
            }}
          >
            <div
              className="pointer-events-auto"
              style={{ animation: impact?.targetId === me.id ? 'impactShake 500ms ease-out' : 'none' }}
            >
              <div
                {...seatPointerProps(me.id, targetable, () => pickTarget(me.id))}
                data-testid="seat"
                data-self="true"
                data-player-id={me.id}
                data-player-name={me.name}
                data-status={me.status}
                data-hand-value={worthOf(me)}
                data-hand-size={me.hand.length}
                data-passive-count={me.passives.length}
                data-gambler-count={game.gamblerCounts?.[me.id] ?? 0}
                data-gambler-slots={game.gamblerSlots}
                data-targetable={targetable}
                data-armed={armedFor(me.id)}
                // On a narrow table the three things in front of you stack
                // instead of sitting side by side: at 390px the name, a hand of
                // seven and a rack of hidden cards are half as wide again as
                // the screen, and the hand is the one that has to stay big.
                className={`relative transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)] ${
                  layout.stacked
                    ? 'flex flex-col items-center gap-1 px-2 py-1'
                    : 'flex items-end gap-[18px] px-4 py-2'
                } ${isFrozen(me.id) ? 'frozen-seat' : ''}`}
                style={{
                  cursor: targetable ? 'crosshair' : 'default',
                  transform: targetHovered ? 'scale(1.05)' : 'scale(1)',
                }}
              >
                <div className={`hover-glow-self ${targetHovered ? 'opacity-100' : 'opacity-0'}`} />
                <div
                  className={`relative flex gap-1.5 ${
                    layout.stacked
                      ? 'order-1 flex-row items-center'
                      : `flex-col items-end ${layout.compact ? '' : 'min-w-[120px]'}`
                  }`}
                >
                  {myTurnNow && <div className="turn-halo" />}
                  <div
                    className="flex items-center gap-2.5 relative z-[1] transition-transform duration-300 ease-[cubic-bezier(.2,.9,.3,1.3)]"
                    style={{ transform: `scale(${dealingPlayerId === me.id ? 1.1 : myTurnNow ? 1.06 : 1})` }}
                  >
                    <PlayerAvatar
                      initial={me.name.charAt(0)}
                      active={myTurnNow || dealingPlayerId === me.id}
                      onTurn={myTurnNow}
                      id={0}
                    />
                    <div className={layout.compact ? 'flex items-center gap-2' : undefined}>
                      <div className={`display leading-none relative ${layout.compact ? 'text-[20px]' : 'text-[26px]'}`}>
                        {me.name}
                        {bust?.playerId === me.id && <div className="bust-strike" />}
                      </div>
                      <div className={`flex items-center gap-1.5 ${layout.compact ? '' : 'mt-0.5'}`}>
                        <span
                          className={`number leading-none ${layout.compact ? 'text-[22px]' : 'text-[28px]'} ${
                            me.status === 'bust' ? 'text-[var(--accent)]' : ''
                          }`}
                        >
                          {worthOf(me)}
                        </span>
                        {statusBadge(me)}
                      </div>
                    </div>
                  </div>
                </div>

                <SpunHand
                  spinId={tableSpin?.id ?? null}
                  dx={slide?.dx ?? 0}
                  dy={slide?.dy ?? 0}
                  className={layout.stacked ? 'order-3' : undefined}
                >
                  <div
                    className="flex items-end px-3 origin-bottom"
                    style={{
                      minHeight: mySpread ? layout.handHeight.spread : layout.handHeight.folded,
                      transform: mySpread ? 'scale(1)' : dealingPlayerId === me.id ? 'scale(0.9)' : 'scale(0.75)',
                      opacity: bust?.playerId === me.id ? 1 : isEliminated ? 0.4 : dimmed && !myHovered ? 0.55 : 1,
                      transition:
                        'transform 420ms cubic-bezier(.2,.9,.3,1.3), opacity 320ms ease, min-height 420ms cubic-bezier(.2,.9,.3,1.3)',
                      filter: dimmed && !isEliminated && !myHovered ? 'grayscale(0.6)' : 'none',
                    }}
                  >
                    {me.hand.map((card, idx) =>
                      handCard(me, card, idx, mySpread ? layout.myCardSize : 'small', mySpread, myFan))}
                    {me.passives.length > 0 && (
                      <>
                        <div className="shrink-0" style={{ width: mySpread ? 18 : 12 }} />
                        {me.passives.map((card, idx) => {
                          const pick = pickState(card)
                          return (
                          <div
                            key={card.id}
                            onClick={pick
                              ? pick.onClick
                              : canInspect ? () => setInspectedCard(card) : undefined}
                            data-testid="passive-card"
                            data-card-id={card.id}
                            data-card-def-id={card.defId}
                            data-pickable={pick?.canPick}
                            data-picked={pick?.picked}
                            data-unpickable={pick?.canUnpick}
                            data-armed={pick?.armed}
                            className={`card-fan-transition-slow opacity-85 cursor-pointer ${pick?.className ?? ''}`}
                            style={{
                              marginLeft: idx === 0 ? 0 : isLaidOut(me) ? 3 : myFan,
                              transform: isLaidOut(me)
                                ? 'none'
                                : `rotate(${(idx - (me.passives.length - 1) / 2) * 3}deg)`,
                            }}
                          >
                            <DealtCard card={card} from={deckCenter}>
                              <PlayingCard card={card} size="small" />
                            </DealtCard>
                          </div>
                          )
                        })}
                      </>
                    )}
                  </div>
                </SpunHand>

                {/* The hidden hand.
                    A sibling of [SpunHand] rather than something inside it, and
                    that placement is load-bearing: the spin slides a whole group
                    across the table to another chair, and a hand nobody can see
                    does not change seats when the hands do. It also sits outside
                    the dimming a finished seat gets, because a card played when
                    you are out is one of the things this hand is *for*. */}
                {game.isRollingRules && (
                  <div
                    className={`flex items-end pb-1 relative ${layout.stacked ? 'order-2' : ''}`}
                    style={{ zIndex: hasPlayable ? 35 : 9 }}
                    data-testid="gambler-hand"
                    data-count={game.gamblerHand.length}
                    data-slots={game.gamblerSlots}
                    data-playable={playableGamblerIds.join(' ')}
                  >
                    {game.gamblerHand.map((card, idx) => {
                      const playable = game.canPlayGambler(card.id)
                      const offered = game.offeredGambler === card.id
                      const info = findGambler(catalog, card.defId)
                      return (
                        <div
                          key={card.id}
                          data-testid="gambler-card"
                          data-card-id={card.id}
                          data-card-def-id={card.defId}
                          data-gambler="true"
                          data-rarity={info?.rarity ?? ''}
                          data-window={info?.window ?? ''}
                          data-playable={playable}
                          data-offered={offered}
                          // On a narrow table this rack is drawn small, and a
                          // card that plays itself the instant a thumb brushes
                          // it would be the one irreversible tap on the felt.
                          // So a tap opens it up to be read instead, and it is
                          // played from there — see the inspection sheet, which
                          // is the only place a phone can show a face this hand
                          // is meant to be read at.
                          onClick={
                            playable && !layout.compact
                              ? () => game.playGambler(card.id)
                              : () => setInspectedCard(card)
                          }
                          className={`card-fan-transition cursor-pointer ${
                            offered ? 'card-picked' : playable ? 'card-pickable' : ''
                          }`}
                          style={{
                            marginLeft: idx === 0
                              ? 0
                              : layout.compact ? (hasPlayable ? -18 : -30) : hasPlayable ? -34 : -58,
                            transform: `rotate(${(idx - (game.gamblerHand.length - 1) / 2) * 3}deg)`,
                            // A card you cannot play is still a card you can
                            // read — held back a little rather than hidden.
                            opacity: playable || hasPlayable === false ? 1 : 0.55,
                          }}
                        >
                          {/* Not `small` on a wide table. This is the one hand
                              nobody can help you read, every face in it is one
                              you have met once or twice, and at 52px it was the
                              smallest thing on screen — you could see that you
                              were holding something purple and no more. A phone
                              has no room for it either way, which is why a tap
                              blows it up rather than playing it. */}
                          <PlayingCard card={card} size={layout.gamblerCardSize} glowing={offered} />
                        </div>
                      )
                    })}
                    {/* The empty slots, because the cap is public and you have
                        to be able to see you have room. */}
                    {Array.from({ length: Math.max(0, game.gamblerSlots - game.gamblerHand.length) }).map(
                      (_, idx) => (
                        <div
                          key={`slot-${idx}`}
                          data-testid="gambler-slot"
                          className="relative"
                          style={{
                            width: layout.compact ? 20 : 30,
                            height: layout.compact ? 76 : 132,
                            marginLeft: idx === 0 && game.gamblerHand.length === 0 ? 0 : 3,
                          }}
                        >
                          <RoughBox
                            width={layout.compact ? 20 : 30}
                            height={layout.compact ? 76 : 132}
                            stroke="var(--ink)"
                            strokeWidth={1}
                            roughness={2.2}
                            dashed
                            boil={false}
                            style={{ opacity: 0.2 }}
                          />
                        </div>
                      ),
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>
        )
      })()}

      {/* Scoreboard.
          Pinned to the corner of a wide felt, where it has always been. On a
          narrow one it is folded up into the pad it is drawn on and picked up
          when somebody wants it: the sheet is 300px across and a phone is 390,
          so left open it is the felt, and what a round is actually played on is
          the hand in front of you and the number over every seat. */}
      {layout.compact ? (
        <>
          <button
            ref={scorePad}
            data-testid="scores-toggle"
            data-open={scoresOpen}
            onClick={() => setScoresOpen((open) => !open)}
            className="sketch-box absolute z-[95] rounded-[2px] px-3 py-1.5 display text-[16px] -rotate-2"
            style={{
              left: `calc(${layout.gutter}px + var(--safe-left))`,
              bottom: `calc(22px + var(--safe-bottom))`,
            }}
          >
            {scoresOpen ? 'put it down' : 'scores'}
          </button>

          {scoresOpen && (
            <div
              className="fixed inset-0 z-[300] flex flex-col items-center justify-center gap-5 overflow-y-auto p-4"
              style={{ background: 'color-mix(in srgb, var(--felt) 82%, transparent)', backdropFilter: 'blur(10px)' }}
              onClick={() => setScoresOpen(false)}
              data-testid="scores-sheet"
            >
              <Scoreboard
                players={players}
                currentPlayerId={currentPlayer?.id || ''}
                localPlayerId={localPlayerId}
                worth={worthOf}
                writtenOff={writtenOff}
                total={totalOf}
                target={game.state?.config.winCondition === 'first_to_score'
                  ? game.state.config.targetScore
                  : undefined}
                rowRef={(playerId, element) => {
                  if (element) scoreRows.current.set(playerId, element)
                  else scoreRows.current.delete(playerId)
                }}
              />
              {/* The felt has no corner to leave this in on a phone, and it is
                  the same question — what are we playing? — as the one that
                  brought the pad out. */}
              {game.state && <TableNote config={game.state.config} />}
              <small className="text-[var(--ink-soft)]">tap anywhere to put it down</small>
            </div>
          )}
        </>
      ) : (
        <div
          className="absolute left-[38px] bottom-9 z-[90] origin-bottom-left"
          style={{
            transform: `scale(${layout.scoreboardScale})`,
            '--board-scale': layout.scoreboardScale,
          } as React.CSSProperties}
        >
          <Scoreboard
            players={players}
            currentPlayerId={currentPlayer?.id || ''}
            localPlayerId={localPlayerId}
            worth={worthOf}
            writtenOff={writtenOff}
            total={totalOf}
            target={game.state?.config.winCondition === 'first_to_score'
              ? game.state.config.targetScore
              : undefined}
            rowRef={(playerId, element) => {
              if (element) scoreRows.current.set(playerId, element)
              else scoreRows.current.delete(playerId)
            }}
          />
        </div>
      )}

      {/* The round's points, going home one seat at a time. */}
      {award && (() => {
        const seat = seatOfId(award.playerId)
        // Lifted clear of the hand it was made with, rather than written over it.
        return (
          <PointsAward
            key={award.id}
            points={award.points}
            playerId={award.playerId}
            from={{ x: seat.x, y: seat.y - 44 }}
            measure={measureScoreRow}
            ms={PAYOUT_FLIGHT_MS}
          />
        )
      })()}

      {/* What is being played, opposite the running score. On a narrow felt it
          travels with the scoreboard into the pad, above. */}
      {game.state && !layout.compact && (
        <div className="absolute right-[38px] bottom-9 z-[55]">
          <TableNote config={game.state.config} />
        </div>
      )}

      {/* Cards answering cards. The pile is where the pending card would be,
          because it is the same question in a different shape: something is
          being held over the table and somebody has to say what happens to it. */}
      {game.responseStack.length > 0 && (
        <ResponseStack frames={game.responseStack} x={cardStage.x} y={responseStage.y} />
      )}

      {/* ...and the way to say no.
          Every other prompt in this game is answered by picking something, so
          passing needed a control of its own. The other half of the answer is
          your own tray, which is already lit and takes a click — so the button
          says what it is for and the sub-line points at the alternative. */}
      {game.canPass && (
        <div
          className="fixed z-[230] -translate-x-1/2 flex flex-col items-center gap-1.5"
          style={{ left: cardStage.x, top: responseStage.y + 130 }}
        >
          <SketchButton
            variant="ghost"
            testId="pass"
            onClick={game.pass}
            disabled={game.passedThis}
          >
            {game.passedThis ? 'letting it stand…' : 'let it stand'}
          </SketchButton>
          <small className="text-[var(--ink-soft)]">
            {playableGamblerIds.length > 0 ? 'or play something' : 'nothing you can play'}
          </small>
        </div>
      )}

      {/* Everybody else is watching the same question. */}
      {game.responseStack.length > 0 && !game.canPass && game.responseWaiting > 0 && (
        <div
          className="fixed z-[200] -translate-x-1/2 pick-target-label"
          style={{ left: cardStage.x, top: responseStage.y + 130 }}
        >
          waiting on {game.responseWaiting}…
        </div>
      )}

      {/* The card waiting for a target. Once a seat is picked it only winds up —
          the trip across the table is the smash below, which the server starts
          and which every client sees, not just the one that did the picking. */}
      {isPickingTarget && pendingDef && (() => {
        // A pick is committed when the answer is complete and on its way — not
        // when the first click of it lands. A card that wants two cards is only
        // half answered after one, and the picker has to stay live.
        const committed = picksCards ? cardsChosen.length >= picksNeeded : !!targetChosen

        return (
          <div
            className="fixed z-[200] pointer-events-none flex flex-col items-center gap-2.5"
            data-testid="pending-action"
            data-card-def-id={pendingDef.id}
            data-card-id={game.pendingAction?.cardId ?? ''}
            data-mine={pendingIsLocal}
            data-chosen={committed ? targetChosen ?? '' : ''}
            data-picked-cards={cardsChosen.join(' ')}
            data-needs-choice={needsChoice}
            data-option={optionChosen ?? ''}
            style={{
              left: cardStage.x,
              top: cardStage.y,
              transform: `translate(-50%, -50%) rotate(${committed ? -7 : 0}deg) scale(${
                layout.cardStageScale + (committed ? 0.2 : 0)
              })`,
              transition: 'transform 260ms cubic-bezier(.3,.8,.4,1.3)',
            }}
          >
            <div style={{ animation: committed ? 'none' : 'swayMore 1.8s ease-in-out infinite' }}>
              <PlayingCard
                card={{ id: 'pending', kind: 'action', label: pendingDef.name, value: 0, defId: pendingDef.id }}
                size="deck"
              />
            </div>
            {pendingIsLocal && !seatIsImplied && !targetChosen && (
              <div className="pick-target-label">pick a target!</div>
            )}
            {/* Everyone else is watching the same card wait on one player, so
                the table says which of the two things it is waiting for. */}
            {!pendingIsLocal && (
              <div className="pick-target-label">
                {players.find((p) => p.id === game.pendingAction?.playerId)?.name ?? 'someone'}
                {needsChoice ? ' is choosing…' : ' is picking…'}
              </div>
            )}
          </div>
        )
      })()}

      {/* …and the question it asks, when it asks one.
          Not gated on the animation the way the hit/stay buttons are: the card
          and the animation of it being drawn arrive together, so a picker that
          hid itself while the table was animating would be invisible for
          exactly the beat the player is being asked to answer in. The answer is
          held back rather than the question — see the send in useGame. */}
      {isPickingTarget && pendingIsLocal && picksFromCatalog && (
        <Shop
          offers={offers}
          purse={purse}
          chosen={cardsChosen[0] ?? null}
          waiting={animating}
          onPick={pickOffer}
          x={cardStage.x}
          y={layout.sheetTop}
        />
      )}

      {/* Which way the table would turn, drawn round the deck for as long as
          the cursor is on a direction — and left up once one is called, so the
          answer does not blink out between the click and the spin. */}
      {previewSpin && <SpinPreview direction={previewSpin} x={deckCenter.x} y={deckCenter.y} />}

      {isPickingTarget && pendingIsLocal && needsChoice && (
        <ChoicePicker
          cardDefId={game.pendingAction?.cardDefId ?? ''}
          options={pendingOptions}
          chosen={optionChosen}
          waiting={animating}
          onPick={pickOption}
          onHover={(option) => setHoveredOption(option ? { cardKey: pendingCardKey, option } : null)}
          x={cardStage.x}
          y={Math.min(cardStage.y + 142, h - 168)}
        />
      )}

      {/* …and the card coming down on whoever it was pointed at */}
      {smashes.map((s) => {
        const def = findAction(catalog, s.cardDefId)
        if (!def) return null
        const seat = seatOfId(s.targetId)

        return (
          <div
            key={s.id}
            className="fixed z-[210] pointer-events-none smash-card"
            data-testid="smash-card"
            data-card-def-id={s.cardDefId}
            data-target-id={s.targetId}
            style={{
              left: seat.x,
              top: seat.y - 40,
              '--smash-lift': `${smashLift(seat.y - 40)}px`,
              '--smash-dx': `${cardStage.x - seat.x}px`,
              '--smash-dy': `${cardStage.y - (seat.y - 40)}px`,
            } as React.CSSProperties}
          >
            <PlayingCard
              card={{ id: `smash-${s.id}`, kind: 'action', label: def.name, value: 0, defId: def.id }}
              size="deck"
            />
          </div>
        )
      })}

      {/* The card that busted somebody, carried up over their hand and then
          brought down on it. Anchored on the seat with the draw pile expressed
          as a delta, exactly as the smash above is — the card is coming from the
          deck rather than from the middle of the table, and that is the only
          difference between the two.

          Dropped at the scatter, because the flight no longer fades out at the
          end of itself: it holds its last frame, and the state that renders it
          outlives that frame by the whole reveal beat. Left up it would be a
          second copy of a card that is already lying in the fan underneath,
          resting on the seat while the hand flies off around it. Going when the
          hand goes is the one moment its leaving costs nothing to look at. */}
      {bust?.card && bust.phase !== 'scatter' && (() => {
        const seat = seatOfId(bust.playerId)
        const anchorY = seat.y - 40
        return (
          <div
            className="fixed z-[212] pointer-events-none bust-card"
            data-testid="bust-card"
            data-player-id={bust.playerId}
            data-card-id={bust.card.id}
            style={{
              left: seat.x,
              top: anchorY,
              '--bust-dur': `${bust.ms}ms`,
              '--bust-lift': `${bustLift(anchorY)}px`,
              '--bust-dx': `${deckCenter.x - seat.x}px`,
              '--bust-dy': `${deckCenter.y - anchorY}px`,
            } as React.CSSProperties}
          >
            <PlayingCard card={bust.card} size="deck" style={{ animation: 'none' }} />
          </div>
        )
      })()}

      {/* Per-card animations */}
      {impact && (() => {
        const pos = clampToFelt(seatOfId(impact.targetId), 160, 160)
        return <ImpactParticles x={pos.x} y={pos.y - 60} />
      })()}

      {/* Sized to what each of these actually draws, so the bloom over the end
          seat of a narrow table's row is a bloom rather than a half of one. */}
      {freezes.map((f) => {
        const pos = clampToFelt(seatOfId(f.playerId), 190, 210)
        return <FreezeBurst key={f.id} x={pos.x} y={pos.y} />
      })}

      {drawThrees.map((d) => {
        const pos = clampToFelt(seatOfId(d.playerId), 140, 140)
        return <DrawThreeStamp key={d.id} x={pos.x} y={pos.y - 20} />
      })}

      {showdown && (
        <Showdown title={showdown.title} sides={showdown.sides} footnote={showdown.footnote} />
      )}

      {flights.map((flight) => (
        <StolenCard
          key={flight.id}
          card={flight.card}
          from={seatOfId(flight.fromPlayerId)}
          to={seatOfId(flight.toPlayerId)}
        />
      ))}

      {secondLives.map((s) => (
        <SecondLife
          key={s.id}
          card={s.card}
          matched={s.matched}
          saver={s.saver}
          from={seatOfId(s.playerId)}
          center={{ x: w / 2, y: h / 2 }}
          ms={s.ms}
        />
      ))}

      {fizzles.map((f) => (
        <FizzleNote
          key={f.id}
          name={findAction(catalog, f.cardDefId)?.name ?? 'that card'}
          cardDefId={f.cardDefId}
          reason={FIZZLE_REASONS[f.cardDefId] ?? 'nothing it could do'}
          x={w / 2}
          y={h * 0.46}
          ms={f.ms}
        />
      ))}

      {counters.map((c) => (
        <CounteredCard
          key={c.id}
          card={c.card}
          name={players.find((p) => p.id === c.playerId)?.name ?? 'somebody'}
          returned={c.returned}
          x={w / 2}
          y={h * 0.44}
          ms={c.ms}
        />
      ))}

      {reveals.map((r) => (
        <GamblerReveal
          key={r.id}
          card={r.card}
          name={players.find((p) => p.id === r.playerId)?.name ?? 'somebody'}
          ms={r.ms}
          firstSeen={r.firstSeen}
        />
      ))}

      {coinTosses.map((c) => (
        <CoinToss key={c.id} call={c.call} result={c.result} x={tableCenter.x} y={tableCenter.y} ms={c.ms} />
      ))}

      {bottleSpins.map((b) => (
        <SpinningBottle
          key={b.id}
          bearing={bearingTo(b.victimId)}
          victimName={players.find((p) => p.id === b.victimId)?.name ?? 'someone'}
          x={tableCenter.x}
          y={tableCenter.y}
          ms={b.ms}
        />
      ))}

      {tolls.map((t) => (
        <PointsFlight
          key={t.id}
          points={t.points}
          from={seatOfId(t.fromPlayerId)}
          to={seatOfId(t.toPlayerId)}
          ms={t.ms}
        />
      ))}

      {tableSpin && <TableSwirl direction={tableSpin.direction} x={tableCenter.x} y={tableCenter.y} />}

      {slots && <SlotMachine card={slots.card} onDone={dismissSlots} />}

      {flip7 && (() => {
        const player = players.find((p) => p.id === flip7.playerId)
        if (!player) return null
        return <Lucky7Overlay cards={player.hand} startPos={seatOfId(flip7.playerId)} />
      })()}

      {/* Out. Last thing over the felt and above everything on it, so what is
          still going on out there is watched in black and white — and below the
          pause menu and a card opened up to be read, which are still yours. */}
      {bustedOut && <BustedTape />}

      {/* Action buttons */}
      <div className={`action-buttons ${showButtons ? 'visible' : 'hidden'}`} data-testid="action-buttons" data-visible={showButtons}>
        <SketchButton variant="primary" testId="hit" onClick={hit}>let it ride!</SketchButton>
        {/* The id stays put when the wording changes: a card that will not let
            you stop should say so on the button you are reaching for. */}
        <SketchButton variant="ghost" testId="stay" onClick={stay} disabled={mustDraw}>
          {cannotStay ? 'no way out' : 'go out'}
        </SketchButton>
      </div>

      {/* Inspection.
          On a narrow table this is also where a card out of your own hidden
          hand is played from — see the rack above. A card small enough to fit
          on a phone is a card you cannot read, so the tap that would have
          played it opens it up instead, and the decision is taken at a size the
          description actually renders at. */}
      {inspectedCard && (() => {
        const playable = inspectedCard.kind === 'gambler'
          && layout.compact
          && game.canPlayGambler(inspectedCard.id)

        return (
          <div onClick={() => setInspectedCard(null)} className="card-inspect-overlay" data-testid="card-inspect">
            <div className="card-inspect-pop">
              <PlayingCard card={inspectedCard} size="deck" />
            </div>
            {playable && (
              <div
                className="absolute bottom-0 left-0 right-0 flex justify-center"
                style={{ paddingBottom: `calc(36px + var(--safe-bottom))` }}
                onClick={(e) => e.stopPropagation()}
              >
                <SketchButton
                  variant="primary"
                  testId="play-gambler"
                  onClick={() => {
                    game.playGambler(inspectedCard.id)
                    setInspectedCard(null)
                  }}
                >
                  play it
                </SketchButton>
              </div>
            )}
          </div>
        )
      })()}
    </div>
  )
}
