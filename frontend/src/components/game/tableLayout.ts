import type { Viewport } from '../../hooks/useViewport'

/**
 * Where the felt puts things, given the window it has been dealt.
 *
 * This exists because the table has two shapes now and only ever wants one
 * answer. On anything wide enough the seats sit on an arc, the way they always
 * have; on a phone held upright they cannot — the arc is a shape for a room
 * wider than it is tall, and a portrait screen is the opposite of that. Rather
 * than teach every animation which of the two it is looking at, both shapes are
 * worked out here and everything downstream asks the same question it always
 * asked: *where does this seat sit*. [GameBoard.seatOfId] is still the one
 * mapping, and it now reads its answer from here.
 *
 * Everything is in pixels of the shell, which is the whole viewport, so a
 * `fixed` overlay and an `absolute` seat can be given the same number.
 */

// ─── The wide table ───

/**
 * The arc the other seats sit on, as fractions of the window: an ellipse
 * centred on the felt, with you at the bottom of it.
 *
 * An arc rather than the four hand-placed seats it replaced, because the table
 * takes ten now and ten places that also had to look right at three was never
 * going to hold. Its ends are where the left and right seats always were, and
 * its top is where the top ones were, so a table of five draws itself almost
 * exactly where it used to.
 */
const SEAT_ARC = { cx: 0.5, cy: 0.46, rx: 0.41, ry: 0.335 }

/**
 * How far down the arc the end seats sit, in degrees either side of the top.
 *
 * A full quarter-turn each way at the sizes that fit in one, which is exactly
 * where the two hand-placed end seats used to be. A crowded table pulls its
 * ends up instead: the scoreboard grows a row at a time out of the bottom-left
 * corner, and past six other players it reaches the seat that would sit there.
 */
function arcSpan(others: number): number {
  return others > 5 ? 82 : 90
}

/**
 * How big a seat is drawn when there are [count] of them on the arc.
 *
 * The arc is the same length however many people are on it, so past about six
 * the seats have to give something up or they print over each other. Only the
 * seats shrink: the deck, the card being played and your own hand are all the
 * same size at a table of ten as at a table of three.
 */
function arcScale(count: number): number {
  if (count <= 4) return 1
  if (count <= 6) return 0.84
  return 0.7
}

// ─── The narrow table ───

/**
 * What a seat takes up when nobody is hovering it, unscaled: the avatar and the
 * name beside it, with a hand of small cards under them.
 *
 * A nominal size and deliberately not a measured one. A hand grows as the round
 * goes on, and a felt that re-laid itself out every time somebody drew a card
 * would move the seat you were about to point at out from under your thumb.
 */
const SEAT_W = 152
const SEAT_H = 148

/** Air between one row of seats and the next. */
const ROW_GAP = 14

/**
 * The smallest a seat is allowed to get before the felt stops trying to fit
 * them all in and simply lets them touch.
 *
 * Ten players in a portrait phone is the case this exists for. Past this the
 * name under an avatar stops being a name, and two seats overlapping slightly
 * is easier to read than nine seats nobody can identify.
 */
const MIN_SEAT_SCALE = 0.5

/** The most rows the felt will break the other seats into. */
const MAX_ROWS = 4

/**
 * How far the middle of a row is lifted above its ends, in pixels.
 *
 * Purely so a row still reads as the far side of a table rather than as a list
 * of players. Small enough that nothing has to be laid out around it.
 */
const ROW_BOW = 10

/** How the seats are broken up when they will not fit on an arc. */
interface Packing {
  /** How many seats are in each row, nearest row last. */
  rows: number[]
  scale: number
}

/**
 * Fits [count] seats into a band [width] by [height], in as few rows as the
 * space allows.
 *
 * Every row count is tried and the roomiest wins, rather than stopping at the
 * first that fits: more rows is not always better, because a row of nine broken
 * into four rows is still a row of three at the front and has simply spent the
 * height as well. The scale that comes back is the smaller of what the width
 * allows and what the height does, so a seat is never wider than its share of
 * the row nor taller than its share of the band.
 */
function packSeats(count: number, width: number, height: number): Packing {
  let best: Packing | null = null

  for (let rows = 1; rows <= Math.min(MAX_ROWS, count); rows++) {
    const perRow = Math.ceil(count / rows)
    const byWidth = width / (perRow * SEAT_W)
    const byHeight = height / (rows * (SEAT_H + ROW_GAP))
    const scale = Math.min(1, byWidth, byHeight)
    if (!best || scale > best.scale) best = { rows: shareOut(count, rows), scale }
  }

  const packing = best ?? { rows: [count], scale: 1 }
  return { ...packing, scale: Math.max(MIN_SEAT_SCALE, packing.scale) }
}

/**
 * Splits [count] seats over [rows] rows, filling the far rows first.
 *
 * Fuller at the back and thinner at the front, which is the way a crowd stands
 * — and it keeps the row nearest you, the one you are reading most often, the
 * one with the most room in it.
 */
function shareOut(count: number, rows: number): number[] {
  const out: number[] = []
  let left = count
  for (let row = 0; row < rows; row++) {
    const take = Math.ceil(left / (rows - row))
    out.push(take)
    left -= take
  }
  return out
}

// ─── What the felt hands back ───

export interface Point {
  x: number
  y: number
}

export interface TableLayout {
  /** The window this was worked out for. */
  w: number
  h: number
  /** Whether the narrow table is being drawn — see [useViewport]. */
  compact: boolean
  /** Where the [index]th of [count] other players sits. */
  seat: (index: number, count: number) => Point
  /** ...and how big that seat is drawn. */
  seatScale: number
  /** Where your own seat sits, which is the one place a card can fly to that is not on the arc. */
  mine: Point
  /** The middle of the draw pile — where a dealt card comes from. */
  deck: Point
  /**
   * ...and the middle of the two piles taken together, which is where they are
   * actually drawn. The deck is half a row to the left of it.
   */
  piles: Point
  /** The middle of the table, which every bearing is measured from. */
  centre: Point
  /** Where a card being played is held up before it is sent at a seat. */
  cardStage: Point
  /** ...and how big it is held up, which is as big as there is room for. */
  cardStageScale: number
  /** ...and where a pile of cards answering each other sits, which is higher. */
  responseStage: Point
  /** The top edge of the sheet a card puts a question on. */
  sheetTop: number
  /** How big the scoreboard is drawn, when it is drawn open at all. */
  scoreboardScale: number
  /**
   * ...and how big the draw and discard piles are.
   *
   * One on anything with room for them. The piles are the last thing a table
   * should give up — the draw pile is the biggest tap target on the felt and
   * every turn goes through it — so this is only ever below one on a window too
   * short to seat anybody otherwise.
   */
  pilesScale: number
  /**
   * Whether your own name, hand and hidden cards are stacked up the screen
   * rather than laid out in a line. Only ever true on a narrow *upright*
   * window — see the note on `sideways`.
   */
  stacked: boolean
  /** How far in from the edge of the window the corner furniture sits. */
  gutter: number
  /**
   * How much room under your own hand is kept for the two buttons.
   *
   * Reserved whether or not they are showing. They come and go with whose turn
   * it is, and a hand that slid down the screen every time somebody else's turn
   * started would be a hand you had to find again each time it was yours.
   */
  buttonsHeight: number
  /** How much room your own hand has to lay itself out in. */
  handWidth: number
  /**
   * ...and how much another seat's has, in that seat's own coordinates — the
   * seat is drawn scaled, and its hand is laid out inside it before the scale
   * is applied.
   */
  seatHandWidth: number
  /** How tall your own hand's row is, spread and unspread. */
  handHeight: { spread: number; folded: number }
  /** The size cards are drawn at in your own hand once it is spread open. */
  myCardSize: 'small' | 'normal'
  /** The size your hidden hand's cards are drawn at. */
  gamblerCardSize: 'small' | 'normal'
}

/**
 * The whole felt's geometry, from the size of the window and the size of the
 * table sitting at it.
 *
 * [rolling] is asked for because a rolling rules table carries a second hand in
 * front of every seat, and the room that costs has to come out of the felt
 * before anything is placed rather than out of whatever is underneath it.
 */
export function tableLayout(
  viewport: Viewport,
  others: number,
  players: number,
  rolling: boolean,
): TableLayout {
  const { w, h, compact } = viewport

  if (!compact) {
    // The wide table, unchanged. Everything below this line is the narrow one,
    // and nothing in it is reachable from a window that has room for the arc.
    const scale = arcScale(others)
    return {
      w, h, compact: false,
      seat: (index, count) => {
        const span = arcSpan(count)
        const degrees = count > 1 ? -span + (index * 2 * span) / (count - 1) : 0
        const t = (degrees * Math.PI) / 180
        return {
          x: (SEAT_ARC.cx + SEAT_ARC.rx * Math.sin(t)) * w,
          y: (SEAT_ARC.cy - SEAT_ARC.ry * Math.cos(t)) * h,
        }
      },
      seatScale: scale,
      mine: { x: w / 2, y: h - 120 },
      deck: { x: w / 2 - 20, y: h * 0.42 },
      piles: { x: w / 2, y: h * 0.42 },
      centre: { x: w / 2, y: h * 0.46 },
      cardStage: { x: w / 2, y: h * 0.62 },
      cardStageScale: 1.8,
      responseStage: { x: w / 2, y: h * 0.55 },
      sheetTop: Math.min(h * 0.62 - 40, h - 320),
      scoreboardScale: players <= 6 ? 1 : players <= 8 ? 0.88 : 0.78,
      pilesScale: 1,
      stacked: false,
      gutter: 38,
      buttonsHeight: 90,
      // Wide enough that no hand a wide table can actually hold is tightened by
      // it: this is a backstop against a hand of twenty, not a layout rule.
      handWidth: Math.max(420, Math.min(w - 360, 900)),
      seatHandWidth: 320,
      handHeight: { spread: 180, folded: 110 },
      myCardSize: 'normal',
      gamblerCardSize: 'normal',
    }
  }

  /**
   * The narrow table reads top to bottom: the other seats, the piles, your own
   * hand. It is laid out from the bottom up, because the bottom is the part
   * that cannot give anything away — your hand is the thing you are actually
   * playing, and the buttons under it are the thing you are actually pressing.
   *
   * Every height here is the *worst* case rather than the current one. The
   * piles sit where they sit whether or not your hand happens to be spread open
   * at the moment; a deck that slid up the screen when somebody's turn ended
   * would be a deck you could not learn the position of.
   */
  // Just inside the drawn frame, which sits 14px in, and clear of whatever the
  // phone's own hardware is covering when it is turned on its side.
  const gutter = 20
  const inset = viewport.insets
  const innerWidth = w - gutter * 2 - inset.left - inset.right

  /**
   * How short the window is, which is what the whole vertical budget below has
   * to give way to.
   *
   * A phone held upright in Safari is not 844px tall — the toolbars are up for
   * the whole session, because nothing in a table scrolls to retract them, so
   * it is nearer 664. Turned on its side it is 390 with a hand and two buttons
   * still to fit in. Every reservation below is therefore a share of what there
   * actually is rather than a number that was true on one device.
   */
  const short = h < 700

  /**
   * ...and whether it is short because the phone is on its side, which is a
   * different problem and gets a different answer.
   *
   * Upright there is height to spend and no width; sideways it is the exact
   * reverse — 844 by 390, with a hand, two buttons and a row of seats all
   * wanting a share of those 390. Stacking three things down a screen that
   * short does not work at any set of numbers: the piles alone are a third of
   * it. So sideways two things step out of the column — the piles go down into
   * the bottom right corner, which nothing else wants, and what is in front of
   * your own seat goes back to sitting in a line the way it does on a wide
   * felt. Both are spending width, which is the one thing a phone on its side
   * has plenty of.
   */
  const sideways = w > h && h < 560

  // Your own name and score above your hand, the hand itself spread open, your
  // hidden hand under the name when there is one, and the buttons below all of
  // it. The buttons' own room is reserved even while they are hidden — a felt
  // that shuffled up and down as turns changed hands would be a felt nobody
  // could learn the shape of.
  const nameRow = short ? 34 : 44
  const gamblerRow = rolling ? (short ? 62 : 84) : 0
  /**
   * Whether what is in front of your own seat is stacked or laid out in a line.
   *
   * Stacked upright, because at 390px across the name, a hand of seven and a
   * rack of hidden cards are half as wide again as the screen. Sideways there
   * are 844px to put them in and only 390 to stack them down, so they go back
   * to a line — which is also what makes rolling rules fit at all on a phone
   * turned over: stacked, the three rows and the buttons come to three quarters
   * of the height before the seats have been given anything.
   */
  const stacked = !sideways
  // Never under the height of the cards in it. `minHeight` is a floor and not a
  // ceiling, so a reservation smaller than a `normal` card simply gets
  // overrun — which is what put the local player's name across the draw pile's
  // own label the first time this was tried at 132px a card.
  const handSpread = short ? 140 : 150
  const buttons = sideways ? 56 : short ? 68 : 88
  // ...and however much of the window that adds up to, it may not take more
  // than its share of it. Past that there is no table left to play it on.
  const mineHeight = Math.min(
    (stacked
      ? nameRow + gamblerRow + handSpread
      : Math.max(nameRow, gamblerRow, handSpread)) +
      buttons +
      inset.bottom,
    h * (sideways ? 0.62 : 0.42),
  )

  /**
   * How big the piles are drawn.
   *
   * Full size wherever there is room: the draw pile is the biggest tap target
   * on the table and the one thing every single turn goes through. A short
   * window buys the seats their band back out of it rather than out of the
   * hand, because a deck at four fifths is still a deck and a hand at four
   * fifths is a hand you cannot read.
   */
  const pilesScale = sideways ? 0.7 : short ? 0.78 : 1
  const pilesHeight = 142 * pilesScale
  const pilesLabel = 30 * pilesScale
  // The piles row is the deck, a gap and the discard box side by side.
  const pilesWidth = (100 + 20 + 108) * pilesScale

  // The band the other seats sit in: below the chrome along the top, and above
  // whatever the bottom of the felt has been reserved for. That chrome is the
  // round, whose turn it is, what they are being asked for and a clock — about
  // a hundred pixels of it stacked in the right hand corner, and rather more of
  // the top of the screen than a notch takes.
  const bandTop = sideways
    ? Math.max(40 + inset.top, h * 0.10)
    : Math.max(96 + inset.top, h * 0.13)
  const bandBottom = h - mineHeight - (sideways ? 6 : 10)

  const pilesCentre: Point = sideways
    // Down in the bottom right corner, which sideways is the one part of the
    // felt nothing else wants: the seats are along the top, your own hand is
    // centred at the bottom and is only as wide as it is, and the chrome is in
    // the two top corners. It is also where a thumb already is, which suits the
    // one thing on the table that is tapped every single turn.
    ? {
        x: w - inset.right - gutter - pilesWidth / 2,
        y: h - inset.bottom - 16 - pilesLabel - pilesHeight / 2,
      }
    // ...or in the column, between the seats and your hand.
    : {
        x: (inset.left + w - inset.right) / 2,
        y: bandBottom - pilesLabel - pilesHeight / 2,
      }

  // What is left for the seats once the piles have taken their place. Sideways
  // that is the whole band, because the piles went to a corner of their own.
  const seatBand = {
    left: inset.left + gutter,
    right: w - inset.right - gutter,
    top: bandTop,
    bottom: sideways ? bandBottom : pilesCentre.y - pilesHeight / 2 - 10,
  }

  const bandWidth = Math.max(SEAT_W * MIN_SEAT_SCALE, seatBand.right - seatBand.left)
  const bandHeight = Math.max(SEAT_H * MIN_SEAT_SCALE, seatBand.bottom - seatBand.top)
  const packing = packSeats(Math.max(others, 1), bandWidth, bandHeight)

  const cellWidth = Math.min(SEAT_W * packing.scale + 6, bandWidth / packing.rows[0])
  const rowHeight = SEAT_H * packing.scale + ROW_GAP
  const blockHeight = packing.rows.length * rowHeight
  // Centred in the band rather than pinned to the top of it, so a table of two
  // does not leave all its air in one place.
  const blockTop = seatBand.top + Math.max(0, (bandHeight - blockHeight) / 2)
  const bandMiddle = (seatBand.left + seatBand.right) / 2

  /**
   * Which row a seat is in and where along it, reading across and then down.
   *
   * `others` arrives in play order starting from whoever acts after me, and
   * reading order is the plainest way to keep that legible once the ring is
   * gone: the next player to act is top left, and the turn works its way down
   * to the seat nearest yours.
   */
  function place(index: number): Point {
    let row = 0
    let offset = index
    while (row < packing.rows.length - 1 && offset >= packing.rows[row]) {
      offset -= packing.rows[row]
      row += 1
    }
    const inRow = packing.rows[row]
    const cell = Math.min(SEAT_W * packing.scale + 6, bandWidth / inRow)
    const across = inRow > 1 ? offset / (inRow - 1) - 0.5 : 0

    // A shallow bow, so a row still reads as the far side of a table.
    const bow = inRow > 1 ? ROW_BOW * (1 - (2 * across) ** 2) : 0
    return {
      // Centred on the band rather than on the window: what is drawable is not
      // the whole width once a notch has taken a bite out of one side of it,
      // and sideways the piles have taken the other end of it as well.
      x: bandMiddle + across * cell * (inRow - 1),
      y: blockTop + row * rowHeight + (SEAT_H * packing.scale) / 2 - bow,
    }
  }

  const middle = (inset.left + w - inset.right) / 2
  return {
    w, h, compact: true,
    // The packing was worked out for the seats this table actually has, so the
    // count it is handed back with is only ever the same one.
    seat: (index) => place(index),
    seatScale: packing.scale,
    mine: { x: middle, y: h - inset.bottom - buttons - handSpread / 2 },
    stacked,
    // The piles row is centred as a whole — so the deck's own middle, which is
    // what a dealt card flies from, is half a row to the left of it and moves
    // with the row's scale.
    deck: { x: pilesCentre.x - 64 * pilesScale, y: pilesCentre.y },
    piles: pilesCentre,
    pilesScale,
    centre: { x: bandMiddle, y: (seatBand.top + bandBottom) / 2 },
    // A card being played is held over the piles rather than under them. There
    // is no felt to spare between the seats and your hand on a narrow table,
    // and of the three things that could be covered while a card waits for an
    // answer the draw pile is the only one nobody is reading: the seats are
    // what the question is *about*, and your hand is what it is being asked of.
    cardStage: { x: middle, y: sideways ? bandBottom - 30 : pilesCentre.y },
    cardStageScale: sideways ? 1 : 1.15,
    responseStage: { x: middle, y: sideways ? bandBottom - 30 : pilesCentre.y },
    // The sheet gets the felt from just under the chrome down, and scrolls
    // inside itself for whatever is left — see the `max-h` on the shelf.
    sheetTop: Math.round(sideways ? h * 0.06 : h * 0.16),
    scoreboardScale: 1,
    gutter,
    buttonsHeight: buttons,
    handWidth: innerWidth + gutter,
    // The seat is drawn scaled, so its hand is given its share of the row back
    // in the seat's own units — otherwise a hand of eight in a table of ten
    // would be laid out to a width that had already been shrunk under it.
    seatHandWidth: cellWidth / packing.scale,
    handHeight: { spread: handSpread, folded: short ? 78 : 96 },
    myCardSize: 'normal',
    // Small, and tapped to be read rather than read where it lies — see the
    // inspect sheet, which is where a hidden card is played from on a phone.
    gamblerCardSize: 'small',
  }
}

/**
 * How far each card in a fan of [count] has to be pulled back over the one
 * before it for the whole fan to fit in [width].
 *
 * Never *less* overlap than the fan was drawn with — a hand of two is not
 * spread across the whole screen just because there is room — and never so much
 * that a card disappears entirely behind its neighbour: past [minShowing] the
 * fan stops tightening and is allowed to run over, because a hand you cannot
 * count is worse than a hand that reaches the edge of the felt.
 */
export function fanOverlap(
  count: number,
  cardWidth: number,
  width: number,
  preferred: number,
  minShowing = 16,
): number {
  if (count <= 1) return preferred
  // What the last card's left edge may be, shared out between the gaps.
  const step = (width - cardWidth) / (count - 1)
  return Math.max(minShowing - cardWidth, Math.min(preferred, step - cardWidth))
}
