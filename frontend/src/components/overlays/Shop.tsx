import type { Offer } from '../../game/types'
import { OfferShelf } from './OfferShelf'

/**
 * The deck, laid out with a price on every card.
 *
 * Everything here is already affordable and already in this table's deck — the
 * server worked both out before it asked — so the sheet has no rules of its
 * own to enforce. It shows what is on sale and takes the click.
 *
 * The purse is what the round has left the buyer, not their score: a second
 * purchase has to come out of what the first one left behind.
 */
export function Shop({ offers, purse, chosen, waiting, onPick, x, y, testId = 'shop' }: {
  offers: Offer[]
  purse: number
  /** The id already picked, while the answer is on its way to the server. */
  chosen: string | null
  /** The table is animating, so the pick is held rather than sent. */
  waiting: boolean
  onPick: (offerId: string) => void
  x: number
  y: number
  /**
   * Stable handle for the end-to-end suite. The sheet has an id because it is
   * the only shop today; a second one somewhere else has to be tellable from
   * this one, while the offers inside it keep their own ids either way so
   * "click an offer" stays one thing a spec knows how to do.
   */
  testId?: string
}) {
  return (
    /* Stretched edge to edge and centred inside itself rather than anchored at
       `x` and pulled back over its own middle. An out-of-flow box with only a
       `left` has the rest of the window to shrink-to-fit into, so at the middle
       of a phone the sheet laid itself out in 195px and `max-w-[92vw]` never
       got a chance to apply. `x` is still taken so the caller keeps saying
       where the question is, and on a wide felt the answer is the same. */
    <div
      className="fixed inset-x-0 z-[240] flex justify-center shop-sheet"
      style={{ top: y }}
      data-testid={testId}
      data-offers={offers.length}
      data-chosen={chosen ?? ''}
      data-x={Math.round(x)}
    >
      <div className="sketch-box rounded px-4 py-4 sm:px-5 flex flex-col items-center gap-3 max-w-[96vw]">
        <div className="display text-xl -rotate-1">
          buy a card — <span className="text-[var(--accent)]">{purse}</span> to spend
        </div>

        <OfferShelf
          offers={offers}
          purse={purse}
          chosen={chosen}
          onPick={onPick}
          className="max-w-[560px] max-h-[46dvh] overflow-y-auto overscroll-contain"
        />

        {chosen && <small>{waiting ? 'when the table settles…' : 'bought!'}</small>}
      </div>
    </div>
  )
}
