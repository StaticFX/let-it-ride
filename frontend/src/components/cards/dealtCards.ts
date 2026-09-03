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

export function resetDealtCards(): void {
  seen.clear()
}
