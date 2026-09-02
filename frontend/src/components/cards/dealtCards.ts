/**
 * Cards that have already played their entrance from the draw pile. Kept
 * outside React so a re-render never replays an animation the player has seen.
 */
const seen = new Set<string>()

export function markDealt(cardId: string): boolean {
  if (seen.has(cardId)) return false
  seen.add(cardId)
  return true
}

export function resetDealtCards(): void {
  seen.clear()
}
