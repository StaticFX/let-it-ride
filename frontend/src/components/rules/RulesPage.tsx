import { useCallback, useState } from 'react'
import { useCatalog } from '../../state/gameStore'
import type { Catalog, GameConfig } from '../../game/types'
import { PaperSheet } from './PaperSheet'

interface RulesPageProps {
  onClose: () => void
  /** When a table is set up, cards and rules it does not use are greyed out. */
  config?: GameConfig
  /**
   * What this room actually plays to. The catalog's copy is only the ruleless
   * default (7), so a table with "flip 9" on has to say 9 — and does, because
   * the room's own `GameStateView.flip7Target` is passed down here.
   */
  flip7Target?: number
}

const ROTATIONS = [-1.2, 0.8, -0.5, 1.0]

// ─── Pages ───

function Heading({ children }: { children: React.ReactNode }) {
  return <div className="display text-[32px] font-bold mb-5 -rotate-1">{children}</div>
}

function Subheading({ children }: { children: React.ReactNode }) {
  return <div className="display text-[22px] font-bold mb-2.5 -rotate-[0.5deg]">{children}</div>
}

function PageBasics() {
  return (
    <>
      <Heading>how to play</Heading>
      <div className="text-lg leading-[1.8] space-y-3.5">
        <p>everyone gets <b>one card</b> to open the round.</p>
        <p>on your turn you have two choices:</p>
        <p className="display text-[22px] font-bold -rotate-[0.5deg] !mb-1.5">→ <u>let it ride</u> — draw a card</p>
        <p className="display text-[22px] font-bold rotate-[0.3deg]">→ <u>go out</u> — bank what you have</p>
        <p>
          draw a card with a <b>number you already hold</b> and you{' '}
          <span className="display font-bold text-[var(--accent)]">bust</span> — the whole round scores zero.
        </p>
        <p>you cannot go out before you have taken at least one card.</p>
        <p>
          each turn is on a <b>clock</b>. let it run out and you go out automatically; if you were holding an
          action card, a random player gets hit with it.
        </p>
        <p>the starting player <b>rotates</b> every round.</p>
      </div>
    </>
  )
}

function PageScoring({ catalog, flipTarget }: { catalog: Catalog; flipTarget: number }) {
  return (
    <>
      <Heading>scoring</Heading>
      <div className="text-lg leading-[1.8] space-y-3.5">
        <p>at the end of a round each survivor scores:</p>
        <ol className="list-decimal pl-6 space-y-1.5">
          <li>add up your <b>number cards</b></li>
          <li>apply <b>×2</b> if you are holding it — it doubles the numbers only</li>
          <li>add your <b>+2 / +4 / +6 / +8 / +10</b> modifiers</li>
          <li>
            add <b>{catalog.flip7Bonus}</b> if you hit {flipTarget}
          </li>
        </ol>
        <p className="text-muted">
          so 10 + 5 with a ×2 and a +10 is <b>(10+5)×2 + 10 = 40</b>, not 50.
        </p>

        <Subheading>flip {flipTarget}!</Subheading>
        <p>
          collect <b>{flipTarget} different numbers</b> and the round ends immediately for everyone —
          you bank your hand plus a <b>{catalog.flip7Bonus} point</b> bonus.
        </p>

        <Subheading>busting</Subheading>
        <p>a bust scores <b>nothing</b> for the round. modifiers you were holding are wasted.</p>
        <p>the game runs until someone reaches the target score, or until the round limit is up.</p>
      </div>
    </>
  )
}

function CardRow({ sigil, name, description, dimmed }: {
  sigil: string
  name: string
  description: string
  dimmed: boolean
}) {
  return (
    <div className={`flex items-start gap-3.5 mb-4 transition-opacity ${dimmed ? 'opacity-30' : ''}`}>
      <div className="display text-[28px] font-bold w-9 text-center shrink-0">{sigil}</div>
      <div>
        <div className="display text-xl font-bold">{name}</div>
        <div className="text-muted">{description}</div>
      </div>
    </div>
  )
}

function PageCards({ catalog, config }: { catalog: Catalog; config?: GameConfig }) {
  const activeActions = new Set(config?.deck.actionCards ?? [])
  const activePassives = new Set(config?.deck.passiveCards ?? [])
  // A card no deck holds is not listed among the ones a deck holds.
  const dealt = catalog.passives.filter((card) => card.deckable !== false)
  const effects = catalog.passives.filter((card) => card.deckable === false)

  return (
    <>
      <Heading>cards</Heading>

      <Subheading>action cards</Subheading>
      <p className="text-muted mb-3.5">drawn and resolved on the spot — pick who gets hit</p>
      {/* A definition that is not a card — a house rule asking a question —
          belongs on the house rules page, not among the cards. */}
      {catalog.actions.filter((card) => card.deckable !== false).map((card) => (
        <CardRow
          key={card.id}
          sigil={card.sigil}
          name={card.name}
          description={card.selfTarget ? `${card.description} (always on yourself)` : card.description}
          dimmed={!!config && !activeActions.has(card.id)}
        />
      ))}

      <div className="h-px bg-[var(--ink)]/10 my-5" />

      <Subheading>modifiers & protection</Subheading>
      <p className="text-muted mb-3.5">kept in front of you until the round ends</p>
      {dealt.map((card) => (
        <CardRow
          key={card.id}
          sigil={card.sigil}
          name={card.name}
          description={card.description}
          dimmed={!!config && !activePassives.has(card.id)}
        />
      ))}
      <p className="text-muted mt-4">
        you can only ever hold one <b>second life</b> — draw another and it goes to a player without one.
      </p>

      {effects.length > 0 && (
        <>
          <div className="h-px bg-[var(--ink)]/10 my-5" />

          <Subheading>effect cards</Subheading>
          <p className="text-muted mb-3.5">
            no deck holds these — they are handed to you by whatever caused them, and they are gone
            when the round ends. they are cards all the same, so they sit in front of you with
            everything else and can be traded away.
          </p>
          {effects.map((card) => (
            <CardRow key={card.id} sigil={card.sigil} name={card.name} description={card.description} dimmed={false} />
          ))}
        </>
      )}
    </>
  )
}

function PageHouseRules({ catalog, config }: { catalog: Catalog; config?: GameConfig }) {
  const active = new Set(config?.ruleIds ?? [])

  return (
    <>
      <Heading>house rules</Heading>
      <p className="text-muted mb-5">the host turns these on before the game starts</p>
      {catalog.rules.map((rule) => {
        const on = config ? active.has(rule.id) : true
        return (
          <div key={rule.id} className={`mb-5 transition-opacity ${config && !on ? 'opacity-30' : ''}`}>
            <div className="flex items-center gap-2.5 mb-1">
              <div className={`w-2.5 h-2.5 rounded-full ${on ? 'bg-[var(--accent)]' : 'bg-[var(--ink)]/25'}`} />
              <div className="display text-[22px] font-bold">{rule.name}</div>
            </div>
            <div className="text-muted pl-5 text-[17px]">{rule.description}</div>
          </div>
        )
      })}
    </>
  )
}

// ─── Shell ───

export function RulesPage({ onClose, config, flip7Target }: RulesPageProps) {
  const catalog = useCatalog()
  const [page, setPage] = useState(0)
  const [flip, setFlip] = useState<'none' | 'out-left' | 'out-right' | 'in'>('none')

  const isFlipping = flip !== 'none'

  const goToPage = useCallback(
    (target: number) => {
      if (isFlipping) return
      setFlip(target > page ? 'out-left' : 'out-right')
      setTimeout(() => {
        setPage(target)
        setFlip('in')
        setTimeout(() => setFlip('none'), 350)
      }, 250)
    },
    [page, isFlipping],
  )

  if (!catalog) {
    return (
      <div className="page-shell justify-center">
        <p className="text-muted">loading the rules…</p>
      </div>
    )
  }

  // The room's own target when there is a room; the catalog's default when the
  // rules are being read from the title screen with no table set up.
  const flipTarget = flip7Target ?? catalog.flip7Target

  const pages = [
    { label: 'the basics', node: <PageBasics /> },
    { label: 'scoring', node: <PageScoring catalog={catalog} flipTarget={flipTarget} /> },
    { label: 'cards', node: <PageCards catalog={catalog} config={config} /> },
    { label: 'house rules', node: <PageHouseRules catalog={catalog} config={config} /> },
  ]

  const flipClass = {
    'out-left': '-translate-x-16 -rotate-6 scale-95 opacity-0 duration-250',
    'out-right': 'translate-x-16 rotate-6 scale-95 opacity-0 duration-250',
    in: 'translate-x-0 rotate-0 scale-100 opacity-100 duration-350 ease-[cubic-bezier(0.34,1.56,0.64,1)]',
    none: 'translate-x-0 rotate-0 scale-100 opacity-100',
  }[flip]

  return (
    <div
      className="fixed inset-0 z-[400] bg-[var(--felt)] flex flex-col items-center overflow-auto"
      data-testid="rules-page"
      data-page={page + 1}
      data-flip-target={flipTarget}
    >
      <div className="flex items-center justify-between w-full max-w-[640px] px-6 pt-6 z-20">
        <button onClick={onClose} data-testid="rules-back" className="bg-transparent border-none cursor-pointer display text-xl font-bold px-2 py-1">
          ← back
        </button>
        <small>{page + 1} / {pages.length}</small>
      </div>

      <div className="flex-1 flex items-start justify-center w-full px-6 pt-6 pb-32 relative">
        <div className={`w-full flex justify-center transition-all ${flipClass}`}>
          <PaperSheet rotation={ROTATIONS[page % ROTATIONS.length]} zIndex={10}>
            {pages[page].node}
            <div className="mt-8 display text-sm text-[var(--ink-soft)] text-center -rotate-1">
              — {pages[page].label} —
            </div>
          </PaperSheet>
        </div>
      </div>

      <div className="fixed bottom-8 left-1/2 -translate-x-1/2 flex gap-6 items-center z-30">
        <button
          onClick={() => page > 0 && goToPage(page - 1)}
          data-testid="rules-prev"
          disabled={page === 0 || isFlipping}
          className={`bg-transparent border-none display text-[28px] font-bold px-4 py-2 -rotate-2 transition-colors ${
            page === 0 || isFlipping ? 'text-[var(--ink)]/20 cursor-default' : 'cursor-pointer'
          }`}
        >
          ← prev
        </button>
        <div className="display text-lg font-bold text-[var(--ink-soft)]">{page + 1}</div>
        <button
          onClick={() => page < pages.length - 1 && goToPage(page + 1)}
          data-testid="rules-next"
          disabled={page === pages.length - 1 || isFlipping}
          className={`bg-transparent border-none display text-[28px] font-bold px-4 py-2 rotate-1 transition-colors ${
            page === pages.length - 1 || isFlipping ? 'text-[var(--ink)]/20 cursor-default' : 'cursor-pointer'
          }`}
        >
          next →
        </button>
      </div>
    </div>
  )
}
