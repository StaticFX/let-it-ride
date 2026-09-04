/**
 * Seeds that reproduce a specific first round.
 *
 * A room's shuffles come from its seed alone, and nothing else about a run
 * varies: the players sit down in the same order, and the local player follows
 * the same policy every time. So a seed that busted once busts always, and a
 * spec can assert on the outcome instead of hoping for it.
 *
 * Found with `scripts/find-seeds.ts`. Each entry records the exact setup it
 * depends on — change the deck, the bot count or the policy and the seed no
 * longer means anything.
 */
export interface Scenario {
  seed: number
  deck: string
  bots: number
  /** What the local player does on their turn. All of these were found with "always hit". */
  policy: 'alwaysHit'
  what: string
}

/**
 * These pin a room's *shuffle*. Prefer `app.hostStacked` for anything that
 * wants particular cards: it names the cards it wants, so it goes on meaning
 * that when the deck's contents change, where a seed quietly stops. What is
 * left here is the two rounds whose *shape* is the point — a bust, a flip —
 * rather than any card in particular.
 */

/** The local player draws a duplicate and the round ends on them. */
export const LOCAL_BUST: Scenario = {
  seed: 100,
  deck: 'pure',
  bots: 3,
  policy: 'alwaysHit',
  what: 'the local player busts in round 1',
}

/** Somebody collects seven different numbers and calls the round early. */
export const FLIP_SEVEN: Scenario = {
  seed: 128,
  deck: 'pure',
  bots: 3,
  policy: 'alwaysHit',
  what: 'a flip 7 ends round 1 and nobody busts',
}
