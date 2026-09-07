import { theme } from "../../theme";
import { RoughBox } from "../ui/RoughShapes";

const DIMS = {
  small: { w: 52, h: 76, fs: 14, num: 30, corner: 13, sigil: 26 },
  normal: { w: 92, h: 132, fs: 22, num: 64, corner: 19, sigil: 50 },
  deck: { w: 100, h: 142, fs: 22, num: 68, corner: 20, sigil: 48 },
};

/** The house ink a gambler card's back is tinted with, mirrored from the catalog's default. */
const GAMBLER_INK = "#6a4a82";

export function CardBack({
  size = "deck",
  variant = "deck",
  style = {},
}: {
  size?: "small" | "normal" | "deck";
  /**
   * Which back this is. `gambler` is somebody's hidden hand rather than the draw
   * pile — tinted, and marked differently — because a rack of house backs
   * beside a player would read as cards they were about to draw.
   */
  variant?: "deck" | "gambler";
  style?: React.CSSProperties;
}) {
  const dims = DIMS[size];
  const sw = theme.strokeWidth;
  const gambler = variant === "gambler";
  const ink = gambler ? GAMBLER_INK : theme.ink;
  const paper = gambler
    ? `color-mix(in srgb, ${GAMBLER_INK} 16%, ${theme.cardBack})`
    : theme.cardBack;
  return (
    <div
      style={{
        width: dims.w,
        height: dims.h,
        position: "relative",
        flexShrink: 0,
        borderRadius: 4,
        animation: `sway 3s ease-in-out infinite`,
        ...style,
      }}
    >
      <div
        style={{
          position: "absolute",
          inset: sw,
          background: paper,
          borderRadius: 3,
          zIndex: 0,
        }}
      />
      <RoughBox
        width={dims.w}
        height={dims.h}
        stroke={ink}
        strokeWidth={sw}
        roughness={1.8}
      />
      <div
        style={{
          position: "absolute",
          inset: 10,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          zIndex: 2,
        }}
      >
        <svg
          width="70%"
          height="70%"
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          style={{ position: "absolute" }}
        >
          <g
            stroke={ink}
            strokeWidth={sw * 0.55}
            strokeLinecap="round"
            fill="none"
            opacity="0.8"
          >
            {gambler ? (
              // A ring rather than a star: a card kept, not a card waiting.
              <>
                <circle cx="50" cy="50" r="30" />
                <circle cx="50" cy="50" r="20" opacity="0.6" />
              </>
            ) : (
              <>
                <line x1="22" y1="22" x2="78" y2="78" />
                <line x1="78" y1="22" x2="22" y2="78" />
                <line x1="50" y1="16" x2="50" y2="84" />
                <line x1="16" y1="50" x2="84" y2="50" />
              </>
            )}
          </g>
        </svg>
        <div
          style={{
            fontFamily: theme.fontDisplay,
            fontSize: dims.fs * 0.95,
            color: ink,
            fontWeight: 700,
            background: paper,
            padding: "2px 6px",
            position: "relative",
            zIndex: 2,
            letterSpacing: "0.04em",
            filter: "url(#wiggle-soft)",
          }}
        >
          {gambler ? "?" : "LIR"}
        </div>
      </div>
    </div>
  );
}
