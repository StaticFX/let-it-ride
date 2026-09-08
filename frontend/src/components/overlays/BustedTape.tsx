/**
 * You are out, and the rest of the round is something you are watching rather
 * than something you are in.
 *
 * So the picture goes: the whole table drains to black and white and the tape it
 * is playing back on starts to show — scanlines, a tracking band rolling up
 * through it, and the grain of a signal nobody is looking after any more. It is
 * the one state on this felt that is *about* not being able to do anything, and
 * the only thing that says so at the moment is the strike through your name.
 *
 * Two things are deliberate about how little it does. The first is that it is
 * kept minor: everything here is an artefact of the tape and none of it is an
 * artefact of the game, so a seat you were reading before you went out reads
 * exactly the same afterwards, only grey. The second is that the only colour it
 * lets back in is the chroma noise on the lines — a red accent that survived the
 * drain would look like something the table was still telling you.
 *
 * It sits above everything that happens on the felt and below everything you can
 * still use: the pause menu, the disconnect notice and a card opened up to be
 * read are all above z-380 and stay in colour, because those are yours and are
 * not part of the round you are out of.
 *
 * The desaturation is a `backdrop-filter` on a sheet laid over the table rather
 * than a `filter` on the board itself. A filter on an ancestor makes that
 * ancestor the containing block for every `position: fixed` descendant, and
 * nearly everything that flies across this table is one — the busting card, the
 * steals, the response stack — so the colour going would have moved all of them
 * at once.
 */
export function BustedTape() {
  return (
    <div className="busted-tape" data-testid="busted-tape" aria-hidden="true">
      <div className="busted-tape-drain" />
      <div className="busted-tape-lines" />
      <div className="busted-tape-track" />
    </div>
  )
}
