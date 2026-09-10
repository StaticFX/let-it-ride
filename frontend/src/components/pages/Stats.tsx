import { useEffect, useState } from 'react'
import { useAuthStore } from '../../state/authStore'
import { fetchLeaderboard, fetchMyStats, fetchPlayerStats, loginHref, logout } from '../../net/auth'
import type { Account, Card, CardTally, LeaderboardRow, PlayerStats } from '../../game/types'
import { PlayingCard } from '../cards/PlayingCard'
import { SketchButton } from '../ui/Button'
import { TitleMark } from '../ui/TitleMark'
import { PosterBurst } from '../ui/PosterBurst'

/**
 * What somebody has done, and what everybody has done.
 *
 * A screen you arrive at rather than play on, so it is printed like the rest of
 * them — burst, wordmark, one colour in the buttons — and not like the felt.
 *
 * Everything on it is the server's own arithmetic. There is no rate computed
 * here, no place worked out, no ranking decided; the client is handed numbers
 * and draws them, which is the same rule the table plays by and matters more
 * here than it looks. A win rate the client divided would be a second
 * definition of what a game is, sitting next to the one in the database, and
 * the two would drift the first time an abandoned table was counted differently
 * at each end.
 */

/**
 * A tally, drawn as the card it is about.
 *
 * The server files a number card under what is printed on it and everything
 * else under its definition — so a face is one lookup away in either case, and
 * this builds the shape `PlayingCard` already knows how to draw rather than
 * inventing a second way to show a card.
 */
function cardFor(tally: CardTally): Card {
  if (tally.kind === 'number') {
    return {
      id: `tally-${tally.kind}-${tally.card}`,
      kind: 'number',
      label: tally.card,
      value: Number.parseInt(tally.card, 10) || 0,
    }
  }
  return {
    id: `tally-${tally.kind}-${tally.card}`,
    kind: tally.kind,
    label: tally.card,
    value: 0,
    defId: tally.card,
  }
}

function CardRow({ title, blank, tallies }: { title: string; blank: string; tallies: CardTally[] }) {
  return (
    <div className="sketch-box rounded p-4 text-left">
      <h3 className="mb-3 -rotate-1">~ {title} ~</h3>
      {tallies.length === 0 ? (
        <p className="text-muted">{blank}</p>
      ) : (
        <div className="flex flex-wrap gap-3">
          {tallies.map((tally) => (
            <div key={`${tally.kind}-${tally.card}`} className="text-center" data-testid="card-tally" data-card={tally.card}>
              <PlayingCard card={cardFor(tally)} size="small" />
              <span className="number text-lg mt-1 block">×{tally.count}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** One number with a word under it. The whole vocabulary of the top half. */
function Figure({ value, label, accent = false, testId }: { value: string; label: string; accent?: boolean; testId?: string }) {
  return (
    <div className="text-center" data-testid={testId}>
      <span className={`number block text-3xl ${accent ? 'text-[var(--accent)]' : ''}`}>{value}</span>
      <small className="text-muted">{label}</small>
    </div>
  )
}

const percent = (fraction: number) => `${Math.round(fraction * 100)}%`
const rounded = (value: number) => (Number.isFinite(value) ? Math.round(value).toString() : '0')

export function Stats({ onClose }: { onClose: () => void }) {
  const enabled = useAuthStore((s) => s.enabled)
  const provider = useAuthStore((s) => s.provider)
  const account = useAuthStore((s) => s.account)

  /**
   * Both answers, or neither.
   *
   * One state rather than three, and it is set exactly once per load — in the
   * promise's callback, never in the body of the effect. That is what the lint
   * is about, and the shape it pushes you into is the better one anyway:
   * "loading" is not a flag anybody has to remember to lower, it is simply not
   * having an answer yet.
   *
   * Note that not having played is not an error. The server says 404 for an
   * account with no games, which is the right answer to a *request* and the
   * wrong thing to put on a screen, so it lands here as a null `mine` and the
   * page says the encouraging thing instead.
   */
  const [result, setResult] = useState<{
    mine: PlayerStats | null
    board: LeaderboardRow[]
    error: string | null
  } | null>(null)

  /**
   * Whose record the top half is showing. Null is your own.
   *
   * The board already names everybody on it, so reading somebody else's is not
   * a disclosure — and it is most of why a board is worth having: a column of
   * win rates says who is winning, and one tap says what they keep busting to.
   */
  const [viewing, setViewing] = useState<Account | null>(null)

  useEffect(() => {
    let live = true
    let failed = false
    Promise.all([
      viewing
        ? fetchPlayerStats(viewing.id).catch(() => null)
        : account
          ? fetchMyStats().catch(() => null)
          : Promise.resolve(null),
      fetchLeaderboard().catch(() => {
        failed = true
        return [] as LeaderboardRow[]
      }),
    ]).then(([mine, board]) => {
      if (live) setResult({ mine, board, error: failed ? 'could not reach the table' : null })
    })
    return () => {
      live = false
    }
  }, [account, viewing])

  const loading = result === null
  const mine = result?.mine ?? null
  const board = result?.board ?? []
  const error = result?.error ?? null
  /** Whose numbers are on screen — the signed-in account, or whoever was tapped. */
  const subject = viewing ?? account
  // "cards you draw" / "cards devin draws". A card row's heading is the one
  // place on this page that has to know whose record it is looking at.
  const whose = viewing ? viewing.name : 'you'
  const verb = viewing ? 's' : ''

  return (
    <div className="page-shell justify-start" data-testid="stats-screen">
      <PosterBurst />
      <div className="poster-content content-width">
        <div className="mb-2 flex justify-center">
          <TitleMark small="the" big="record" scale={0.5} />
        </div>

        {!enabled && (
          <p className="text-muted text-center mb-6" data-testid="stats-disabled">
            this table keeps no records — everybody here is a guest
          </p>
        )}

        {enabled && !account && (
          <div className="text-center mb-8">
            <p className="text-muted mb-4">sign in to keep a record of your games</p>
            <a href={loginHref()} data-testid="stats-sign-in">
              <SketchButton variant="primary">sign in with {provider}</SketchButton>
            </a>
          </div>
        )}

        {loading && <p className="text-muted text-center sway-mid mb-6">counting…</p>}
        {error && <p className="text-[var(--accent)] text-center mb-6">{error}</p>}

        {/* ── One person's numbers: yours, or whoever was tapped on the board ── */}
        {subject && !loading && (
          <div className="mb-8" data-testid="my-stats" data-games={mine?.games ?? 0} data-account-id={subject.id}>
            {viewing ? (
              <p className="text-center text-muted mb-4">
                <span className="display text-xl text-[var(--ink)]">{viewing.name}</span>'s record{' '}
                <button
                  type="button"
                  className="underline"
                  data-testid="back-to-mine"
                  onClick={() => setViewing(null)}
                >
                  {account ? '— back to yours' : '— close'}
                </button>
              </p>
            ) : (
              <p className="text-center text-muted mb-4">
                signed in as <span className="display text-xl text-[var(--ink)]">{subject.name}</span>
              </p>
            )}

            {!mine || mine.games === 0 ? (
              <p className="text-muted text-center mb-6" data-testid="no-games-yet">
                {viewing ? 'no finished games yet' : 'nothing here yet — play a game and it will be'}
              </p>
            ) : (
              <>
                <div className="sketch-box rounded p-4 mb-4 grid grid-cols-2 gap-y-4 sm:grid-cols-4">
                  <Figure value={percent(mine.winRate)} label="win rate" accent testId="stat-win-rate" />
                  <Figure value={`${mine.wins}/${mine.games}`} label="games won" testId="stat-games" />
                  <Figure value={rounded(mine.averageScore)} label="average score" testId="stat-average" />
                  <Figure value={String(mine.bestScore)} label="best game" testId="stat-best" />
                </div>

                <div className="sketch-box sketch-box-light rounded p-4 mb-4 grid grid-cols-2 gap-y-4 sm:grid-cols-4">
                  <Figure value={String(mine.rounds)} label="rounds played" />
                  <Figure value={percent(mine.bustRate)} label="busted" />
                  <Figure value={String(mine.flip7s)} label="flips" />
                  <Figure value={String(mine.cardsDrawn)} label="cards drawn" />
                </div>

                {/* Bots are broken out rather than hidden: they are games you
                    played and they are not games anybody is ranked on, and the
                    leaderboard below will otherwise look like it has lost some
                    of yours. */}
                {!viewing && mine.rankedGames < mine.games && (
                  <p className="text-muted text-center mb-4">
                    <span className="number">{mine.games - mine.rankedGames}</span>{' '}
                    of those were against bots, and are not on the board below
                  </p>
                )}

                <div className="grid gap-4 sm:grid-cols-2">
                  <CardRow title={`cards ${whose} draw${verb}`} blank="no cards yet" tallies={mine.mostDrawn} />
                  <CardRow title={`cards ${whose} bust${verb} to`} blank="never busted — yet" tallies={mine.mostBustedTo} />
                </div>
                {mine.mostPlayed.length > 0 && (
                  <div className="mt-4">
                    <CardRow title={`cards ${whose} play${verb}`} blank="" tallies={mine.mostPlayed} />
                  </div>
                )}
              </>
            )}
          </div>
        )}

        {/* ── Everybody ── */}
        {enabled && !loading && (
          <div className="sketch-box rounded p-4 text-left mb-6" data-testid="leaderboard">
            <h3 className="mb-1 -rotate-1">~ the board ~</h3>
            <small className="text-muted block mb-3">games with other people in them</small>
            {board.length === 0 ? (
              <p className="text-muted" data-testid="leaderboard-empty">nobody has finished a game together yet</p>
            ) : (
              board.map((row, i) => (
                /* A whole row is the target rather than the name in it: on a
                   phone a 16px word is not something anybody hits, and there is
                   nothing else on the row to press by mistake. */
                <button
                  type="button"
                  key={row.account.id}
                  data-testid="leaderboard-row"
                  data-account-id={row.account.id}
                  data-rank={i + 1}
                  onClick={() => setViewing(row.account.id === account?.id ? null : row.account)}
                  className={`w-full text-left flex items-center justify-between py-1.5 ${
                    row.account.id === subject?.id ? '' : 'opacity-75'
                  }`}
                >
                  <div className="flex items-center gap-2 min-w-0">
                    <small className="w-5 shrink-0">{i + 1}.</small>
                    <span className="display text-xl truncate">{row.account.name}</span>
                    {row.account.id === account?.id && <small className="shrink-0">(you)</small>}
                  </div>
                  <div className="flex items-center gap-4 shrink-0">
                    <span className="number text-lg text-muted">{percent(row.winRate)}</span>
                    <span className="number text-2xl text-[var(--accent)]">{row.wins}</span>
                    <small className="text-muted w-12 text-right">of {row.games}</small>
                  </div>
                </button>
              ))
            )}
          </div>
        )}

        <div className="flex justify-center gap-3.5">
          <SketchButton variant="ghost" testId="stats-done" onClick={onClose}>← back</SketchButton>
          {account && (
            <SketchButton variant="ghost" testId="sign-out" onClick={() => { void logout() }}>sign out</SketchButton>
          )}
        </div>
      </div>
    </div>
  )
}
