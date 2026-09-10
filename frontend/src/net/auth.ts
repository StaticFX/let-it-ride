import { useAuthStore } from '../state/authStore'
import type { AuthView, LeaderboardRow, PlayerStats } from '../game/types'

/**
 * Signing in, and reading what you have done.
 *
 * Same origin as everything else, so the session cookie rides along on its own
 * and nothing in here ever touches a token. The one thing worth knowing about
 * this file is that it treats every failure as "no account": a server with no
 * identity provider, a server too old to have these routes at all, and a server
 * that is simply down all mean the same thing to the front door, which is that
 * there is no button to draw.
 */

async function json<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error((body as { error?: string } | null)?.error ?? 'something went wrong')
  }
  return body as T
}

const SIGNED_OUT: AuthView = { enabled: false, provider: '' }

/**
 * Asks who this is, once on load and again after signing out.
 *
 * Never throws. The store is the single answer to "can anybody sign in here",
 * and a screen that had to handle a third state — enabled, disabled, and *did
 * not load* — would grow a spinner on the front door for a feature most tables
 * are not using.
 */
export async function fetchAuth(): Promise<AuthView> {
  const view = await fetch('/api/auth/me')
    .then((r) => (r.ok ? (r.json() as Promise<AuthView>) : SIGNED_OUT))
    .catch(() => SIGNED_OUT)
  useAuthStore.getState().setAuth(view)
  return view
}

/**
 * Where the sign-in starts.
 *
 * A plain navigation and not a fetch — the whole point is to leave this page
 * for the identity provider's and come back — so this is an address rather than
 * a call. `return` carries where to land afterwards; the server refuses
 * anything that is not a path on itself.
 */
export function loginHref(returnTo: string = window.location.pathname + window.location.search): string {
  return `/api/auth/login?return=${encodeURIComponent(returnTo)}`
}

export async function logout(): Promise<void> {
  await fetch('/api/auth/logout', { method: 'POST' }).catch(() => undefined)
  await fetchAuth()
}

export async function fetchMyStats(): Promise<PlayerStats> {
  return json<PlayerStats>(await fetch('/api/stats/me'))
}

/**
 * Somebody else's record.
 *
 * Public, and deliberately so: the leaderboard already names everybody on it,
 * and half the fun of one is finding out what the person above you keeps
 * busting to. Nothing here is reachable that the board does not already show a
 * row for.
 */
export async function fetchPlayerStats(accountId: string): Promise<PlayerStats> {
  return json<PlayerStats>(await fetch(`/api/stats/player/${encodeURIComponent(accountId)}`))
}

export async function fetchLeaderboard(): Promise<LeaderboardRow[]> {
  const body = await json<{ rows: LeaderboardRow[] }>(await fetch('/api/stats/leaderboard'))
  return body.rows ?? []
}

/**
 * The message a failed sign-in came back with, taken out of the address bar as
 * it is read.
 *
 * The server has no way to show anybody anything — the callback is a redirect —
 * so it puts what went wrong in the query and leaves it to the page. Removing
 * it as it is read is the same move the invite code makes: a message about one
 * attempt should not survive a reload into being a message about nothing.
 */
export function takeAuthError(): string | null {
  const url = new URL(window.location.href)
  const message = url.searchParams.get('authError')
  if (!message) return null
  url.searchParams.delete('authError')
  window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`)
  return message
}
