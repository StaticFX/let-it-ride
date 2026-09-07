import type { Card, Offer } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'

/**
 * Cards laid out with a price on each.
 *
 * Shared by the two shops in the game, which want the same grid and nothing else
 * the same: the "mutate" card's is a sheet held over the table for one purchase,
 * and the one between rounds is a screen you spend two minutes in buying as many
 * as you can pay for. The testids live here so "click an offer" stays one thing
 * a spec knows how to do either side of that.
 *
 * It shows what you cannot afford rather than hiding it. The in-round shop is
 * pre-filtered by the server and every card in it is already affordable; the
 * one between rounds is not, because a shelf is yours for two minutes and
 * deciding what to buy *first* is the whole game of it.
 *
 * Two ways of picking, because the two shops are asking different things. The
 * sheet asks "which one" and the card *is* the button. The shelf asks "do you
 * want this, at this price, instead of the other three" — and there the card has
 * to stay something you can pick up and read without that counting as an answer,
 * so reading and buying become two separate controls. Handing the whole decision
 * to one click made the only way to look closely at a card be to buy it.
 */
export function OfferShelf({
  offers, purse, slotsFree, bought, chosen, onPick, onInspect, size = 'small', className = '',
}: {
  offers: Offer[]
  purse: number
  /** How much room is left in the tray, so a full one can say why it is greyed. */
  slotsFree?: number
  /** What has already been taken off this shelf. */
  bought?: string[]
  /** The one picked, while the answer is on its way. */
  chosen?: string | null
  onPick: (offerId: string) => void
  /**
   * Given, the card becomes a reading control and a separate button does the
   * buying. Left out, the card is the button — which is what the mid-round
   * sheet wants.
   */
  onInspect?: (card: Card) => void
  /**
   * How big to draw them. `small` is a sheet held over a table mid-round, where
   * space is short and the buyer is already under a clock. A screen of its own
   * uses `large`, which is the size a card's description is meant to be read at
   * rather than merely rendered at — and a shop you cannot read the cards in is
   * a shop you are guessing in.
   */
  size?: 'small' | 'normal' | 'large'
  className?: string
}) {
  const noRoom = slotsFree !== undefined && slotsFree <= 0
  const big = size !== 'small'
  return (
    <div className={`flex flex-wrap justify-center ${big ? 'gap-5' : 'gap-2.5'} py-1 ${className}`}>
      {offers.map((offer) => {
        const sold = bought?.includes(offer.id) ?? false
        const picked = chosen === offer.id
        const affordable = offer.price <= purse
        const takeable = !sold && affordable && !noRoom && !chosen
        // Why it cannot be had, in the order that actually stops you. A shelf
        // that only ever said "no" would leave you working out which of three
        // reasons it was.
        const refusal = sold ? 'yours' : !affordable ? 'too dear' : noRoom ? 'no room' : null

        const face = <PlayingCard card={offer.card} size={size} glowing={picked} dimmed={sold} />
        const price = (
          <span
            className={`absolute -bottom-1 -right-1 z-10 display text-[var(--card-face)] bg-[var(--ink)] rounded-full ${
              big ? 'text-[15px] px-2.5 leading-[23px]' : 'text-[11px] px-1.5 leading-[15px]'
            }`}
          >
            {sold ? '✓' : offer.price}
          </span>
        )

        // The sheet: one control, and the card is it.
        if (!onInspect) {
          return (
            <button
              key={offer.id}
              onClick={() => takeable && onPick(offer.id)}
              disabled={!takeable}
              data-testid="shop-offer"
              data-offer-id={offer.id}
              data-price={offer.price}
              data-picked={picked}
              data-sold={sold}
              data-affordable={affordable && !noRoom}
              className={`relative bg-transparent border-none p-0 transition-transform duration-150 ${
                takeable ? 'cursor-pointer hover:scale-110 hover:-rotate-2' : 'cursor-default'
              } ${picked ? 'scale-110 -rotate-2' : ''} ${sold || !affordable || noRoom ? 'opacity-30' : ''}`}
            >
              {face}
              {price}
            </button>
          )
        }

        // The shelf: read it, then decide. The wrapper keeps every attribute the
        // suite reads off an offer, so both shapes look the same from outside.
        return (
          <div
            key={offer.id}
            data-testid="shop-offer"
            data-offer-id={offer.id}
            data-price={offer.price}
            data-picked={picked}
            data-sold={sold}
            data-affordable={affordable && !noRoom}
            className="flex flex-col items-center gap-2"
          >
            <button
              onClick={() => onInspect(offer.card)}
              data-testid="shop-inspect"
              title="have a proper look"
              className={`relative bg-transparent border-none p-0 cursor-pointer transition-transform duration-150 hover:scale-105 hover:-rotate-1 ${
                sold ? 'opacity-40' : ''
              } ${picked ? 'scale-105 -rotate-1' : ''}`}
            >
              {face}
              {price}
            </button>
            <button
              onClick={() => takeable && onPick(offer.id)}
              disabled={!takeable}
              data-testid="shop-buy"
              className={`display rounded border-2 px-3 py-1 text-base leading-none transition-colors ${
                takeable
                  ? 'cursor-pointer border-[var(--ink)] bg-[var(--ink)] text-[var(--card-face)] hover:bg-[var(--accent)] hover:border-[var(--accent)]'
                  : 'cursor-default border-dashed border-[var(--ink-soft)] bg-transparent text-[var(--ink-soft)]'
              }`}
            >
              {refusal ?? `buy · ${offer.price}`}
            </button>
          </div>
        )
      })}
    </div>
  )
}
