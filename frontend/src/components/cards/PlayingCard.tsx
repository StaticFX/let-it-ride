import type { Card as CardType } from "../../game/types";
import { findAction, findPassive, useCatalog } from "../../state/gameStore";
import { theme } from "../../theme";
import { RoughBox, RoughSquiggle } from "../ui/RoughShapes";

interface PlayingCardProps {
  card: CardType;
  size?: "small" | "normal" | "deck";
  faceDown?: boolean;
  dimmed?: boolean;
  glowing?: boolean;
  style?: React.CSSProperties;
}

const DIMS = {
  small: { w: 52, h: 76, fs: 14, num: 30, corner: 13, sigil: 26 },
  normal: { w: 92, h: 132, fs: 22, num: 64, corner: 19, sigil: 50 },
  deck: { w: 100, h: 142, fs: 22, num: 68, corner: 20, sigil: 48 },
};

const SUIT_GLYPHS: Record<string, string> = {
  hearts: "\u2020",
  diamonds: "\u2726",
  clubs: "\u2620",
  spades: "\u273A",
};

function cardHash(id: string) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
  return Math.abs(h);
}

export function PlayingCard({
  card,
  size = "normal",
  faceDown = false,
  dimmed = false,
  glowing = false,
  style = {},
}: PlayingCardProps) {
  const dims = DIMS[size];
  const sw = theme.strokeWidth;
  const ink = theme.ink;
  const catalog = useCatalog();

  const phase = (cardHash(card.id) % 1000) / 1000;
  const swayDur = 2.2 + phase * 1.6;
  const swayDelay = -phase * swayDur;

  const baseStyle: React.CSSProperties = {
    width: dims.w,
    height: dims.h,
    position: "relative",
    flexShrink: 0,
    background: "transparent",
    borderRadius: 4,
    opacity: dimmed ? 0.45 : 1,
    transition: "transform 220ms cubic-bezier(.2,.9,.3,1.2)",
    boxShadow: glowing ? `3px 3px 0 0 ${theme.actionAccent}` : "none",
    animation: `sway ${swayDur}s ease-in-out ${swayDelay}s infinite`,
    ...style,
  };

  const paperFill = (
    <div
      style={{
        position: "absolute",
        inset: sw,
        background: theme.cardFace,
        borderRadius: 3,
        zIndex: 0,
      }}
    />
  );

  // Face down
  if (faceDown) {
    return (
      <div style={baseStyle}>
        <div
          style={{
            position: "absolute",
            inset: sw,
            background: theme.cardBack,
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
              <line x1="22" y1="22" x2="78" y2="78" />
              <line x1="78" y1="22" x2="22" y2="78" />
              <line x1="50" y1="16" x2="50" y2="84" />
              <line x1="16" y1="50" x2="84" y2="50" />
            </g>
          </svg>
          <div
            style={{
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * 0.95,
              color: ink,
              fontWeight: 700,
              background: theme.cardBack,
              padding: "2px 6px",
              position: "relative",
              zIndex: 2,
              letterSpacing: "0.04em",
            }}
          >
            Leck ei
          </div>
        </div>
      </div>
    );
  }

  // Action/passive card — the face comes from the backend's catalog.
  const action = findAction(catalog, card.defId);
  const passive = findPassive(catalog, card.defId);
  const cardDef = action ?? passive;
  if (cardDef) {
    const isAction = !!action;
    return (
      <div style={baseStyle}>
        {paperFill}
        <RoughBox
          width={dims.w}
          height={dims.h}
          stroke={ink}
          strokeWidth={sw}
          roughness={1.7}
        />
        <div
          style={{
            position: "absolute",
            inset: sw + 4,
            display: "flex",
            flexDirection: "column",
            alignItems: "stretch",
            zIndex: 2,
          }}
        >
          <div
            style={{
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * 0.78,
              color: ink,
              fontWeight: 700,
              textAlign: "center",
              padding: "4px 4px 2px",
              letterSpacing: "0.02em",
              lineHeight: 1,
              position: "relative",
            }}
          >
            {cardDef.name}
            <div
              style={{
                position: "absolute",
                bottom: -3,
                left: "10%",
                width: "80%",
                height: 8,
              }}
            >
              <RoughSquiggle
                width={Math.round(dims.w * 0.7)}
                height={8}
                stroke={ink}
                strokeWidth={sw * 0.6}
                amplitude={1.5}
                segments={5}
                roughness={1.4}
              />
            </div>
          </div>
          <div
            style={{
              flex: 1,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: dims.sigil,
              color: isAction ? theme.actionAccent : ink,
              fontFamily: theme.fontDisplay,
              fontWeight: 700,
              lineHeight: 1,
            }}
          >
            {cardDef.sigil}
          </div>
          {size !== "small" && (
            <div
              style={{
                padding: "0 6px 6px",
                fontFamily: theme.fontBody,
                fontSize: dims.fs * 0.46,
                color: ink,
                textAlign: "center",
                lineHeight: 1.05,
              }}
            >
              {cardDef.description}
            </div>
          )}
        </div>
      </div>
    );
  }

  // Number card
  const value = card.value;
  // `label` is what is printed: the value for the numeric decks, the rank for
  // the classic 52-card deck. It is also what duplicates are matched on.
  const face = card.label || String(card.value);
  const suitGlyph = (card.suit && SUIT_GLYPHS[card.suit]) || "";
  const isHot = value >= 7;
  const numColor = isHot ? theme.actionAccent : ink;
  const digitRotation = ((value * 13) % 7) - 3;

  return (
    <div style={baseStyle}>
      {paperFill}
      <RoughBox
        width={dims.w}
        height={dims.h}
        stroke={ink}
        strokeWidth={sw}
        roughness={1.7}
      />
      <div style={{ position: "absolute", inset: sw + 4, zIndex: 2 }}>
        {/* Corner top-left */}
        <div
          style={{
            position: "absolute",
            top: 2,
            left: 5,
            fontFamily: theme.fontNumber,
            fontWeight: 700,
            fontSize: dims.corner,
            lineHeight: 1,
            color: numColor,
          }}
        >
          {face}
        </div>
        {/* Corner bottom-right */}
        <div
          style={{
            position: "absolute",
            bottom: 2,
            right: 5,
            fontFamily: theme.fontNumber,
            fontWeight: 700,
            fontSize: dims.corner,
            lineHeight: 1,
            color: numColor,
            transform: "rotate(180deg)",
          }}
        >
          {face}
        </div>
        {/* Center number */}
        {size !== "small" && (
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontFamily: theme.fontNumber,
              fontSize: dims.num,
              color: numColor,
              fontWeight: 700,
              lineHeight: 0.9,
              transform: `rotate(${digitRotation}deg)`,
            }}
          >
            {face}
          </div>
        )}
        {size === "small" && (
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontFamily: theme.fontNumber,
              fontSize: dims.num,
              color: numColor,
              fontWeight: 700,
              lineHeight: 0.9,
              transform: `rotate(${digitRotation}deg)`,
            }}
          >
            {face}
          </div>
        )}
        {/* Suit glyph */}
        {size !== "small" && (
          <div
            style={{
              position: "absolute",
              bottom: 8,
              left: 0,
              right: 0,
              textAlign: "center",
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * 0.48,
              color: isHot ? theme.actionAccent : theme.inkSoft,
              opacity: 0.85,
            }}
          >
            {suitGlyph}
          </div>
        )}
        {/* Hot mark */}
        {isHot && size !== "small" && (
          <div
            style={{
              position: "absolute",
              top: 4,
              right: 6,
              fontFamily: theme.fontDisplay,
              fontSize: dims.fs * 0.5,
              color: theme.actionAccent,
              fontWeight: 700,
              lineHeight: 1,
              transform: "rotate(6deg)",
            }}
          >
            !
          </div>
        )}
      </div>
    </div>
  );
}
