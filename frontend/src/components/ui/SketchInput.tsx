import { play } from '../../audio/sfx'

type SketchInputProps = React.InputHTMLAttributes<HTMLInputElement>

/**
 * The game's text field. Wraps the plain input purely so typing is audible in
 * one place rather than at every call site.
 */
export function SketchInput({ className = '', onKeyDown, ...rest }: SketchInputProps) {
  return (
    <input
      {...rest}
      className={`sketch-input ${className}`}
      onKeyDown={(e) => {
        // Modifiers and navigation are not keystrokes worth hearing.
        if (e.key.length === 1 || e.key === 'Backspace') play('keystroke')
        onKeyDown?.(e)
      }}
    />
  )
}
