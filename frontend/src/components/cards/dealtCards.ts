/**
 * Cards that have already played their entrance from the draw pile. Kept
 * outside React so a re-render never replays an animation the player has seen.
 */
const seen = new Set<string>()

/** Whether [cardId] has already played its entrance. */
export function hasDealt(cardId: string): boolean {
  return seen.has(cardId)
}

/**
 * Records that [cardId] is playing its entrance. Called when the animation
 * actually starts rather than when it is set up — an entrance that gets torn
 * down before its first frame has not been seen, and must be allowed to run
 * again.
 */
export function markDealt(cardId: string): void {
  seen.add(cardId)
}

/**
 * Forgets every card that is no longer on the table.
 *
 * A game is played with one deck, dealt once: ids are handed out when the deck
 * is built and the same physical card comes round again and again, back to the
 * discard pile at the end of a round and shuffled in the next time the deck
 * runs dry. Marking a card seen for the whole game meant that from the first
 * reshuffle on — four or five rounds in — nothing dealt in any more, and the
 * only entrances left were the minted passives, whose ids really are new.
 *
 * A card that has left the table has not been seen where it is going next, so
 * it gets to make the trip from the deck again. One that only moves between
 * seats never leaves, and so still arrives by its own animation — a steal
 * flies across the table rather than in from the draw pile.
 */
export function retainDealtCards(cardIds: Iterable<string>): void {
  const onTable = new Set(cardIds)
  for (const id of seen) if (!onTable.has(id)) seen.delete(id)
  // The card is going back to the deck, so the way it came off it last time is
  // not worth remembering — see [tension].
  for (const id of tension.keys()) if (!onTable.has(id)) tension.delete(id)
}

export function resetDealtCards(): void {
  seen.clear()
  tension.clear()
}

/**
 * A stable number for a card id, for anything that has to look the same every
 * time the same card is drawn — a sway phase, a coin flip on which entrance it
 * makes. Rolling those at render time means a re-render changes them, and an
 * animation that is halfway through when that happens jumps.
 */
export function cardHash(id: string): number {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0
  return Math.abs(h)
}

/** How many trips off the deck are made the slow way. */
const TENSION_CHANCE = 0.1

/**
 * Which way each card is coming off the deck on the trip it is making now.
 *
 * The roll belongs to the entrance, not to the card. Deriving it from the id
 * instead is stable in the way that matters — a re-render cannot change it
 * mid-flight — but it is stable in a way that ruins the effect: a deck is built
 * once and the same physical cards come round all game, so the same five or so
 * of the fifty-two would be the dramatic ones every single time they were
 * dealt, and a player learns that fast. Rolling per trip and remembering the
 * answer for as long as the card is on the table gets both: settled while it
 * flies, fresh the next time it is dealt.
 */
const tension = new Map<string, boolean>()

/**
 * Whether [cardId] gets the tension entrance — drawn slowly face down, carried
 * over the hand, then flipped and slammed — rather than the plain deal-in.
 *
 * Asked from the layout effect that starts the entrance, and only for a card
 * that has not dealt yet, so the roll happens once per trip. Asking again
 * before the card lands — the effect can be torn down and set back up — gets
 * the same answer back rather than a second opinion.
 */
export function dealsTense(cardId: string): boolean {
  const decided = tension.get(cardId)
  if (decided !== undefined) return decided
  const tense = Math.random() < TENSION_CHANCE
  tension.set(cardId, tense)
  return tense
}
