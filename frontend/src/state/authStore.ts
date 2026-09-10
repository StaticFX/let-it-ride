import { create } from 'zustand'
import type { Account, AuthView } from '../game/types'

/**
 * Who is signed in, if anybody.
 *
 * Deliberately apart from `gameStore`, which is a mirror of one room and is
 * emptied every time somebody leaves a table. An account outlives rooms — it is
 * a fact about the browser, not about the game — and putting it in the same
 * store would mean either clearing it on `reset()` or remembering not to.
 *
 * Like `gameStore` this decides nothing. `enabled` is the server's answer, not
 * a guess from whether an account came back, so a signed-out player still gets
 * a button and a signed-out player on a server with no provider does not.
 */
export interface AuthStore {
  /** Whether this server can sign anybody in. False until the server says otherwise. */
  enabled: boolean
  /** The operator's name for their identity provider — what the button says. */
  provider: string
  account: Account | null
  /** False until the first answer lands, so nothing flashes a wrong state. */
  loaded: boolean

  setAuth: (view: AuthView) => void
}

export const useAuthStore = create<AuthStore>()((set) => ({
  enabled: false,
  provider: '',
  account: null,
  loaded: false,

  setAuth: (view) =>
    set({
      enabled: view.enabled,
      provider: view.provider,
      account: view.account ?? null,
      loaded: true,
    }),
}))
