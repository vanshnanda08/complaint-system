import type { GlyphKey, IssueStatus, RuleShape, StatusToken } from "@/lib/status";
import { OVERDUE, STATUS, statusWord } from "@/lib/status";

/**
 * Status, encoded three ways at once: colour, shape, word.
 *
 * The only component permitted to render a status. Nothing else picks a status
 * colour or writes a status word -- see src/lib/status.ts for why the mapping
 * is centralised and why three statuses deliberately share one colour.
 *
 * The glyphs are inline SVG rather than unicode characters. A status glyph is
 * load-bearing here, not decoration: it is one of the three encodings, and it
 * must not silently degrade to a tofu box because a font on a cheap Android
 * lacks U+25D4.
 */

const GLYPH_SIZE = 14;

function Glyph({ kind, color }: { kind: GlyphKey; color: string }) {
  const common = {
    width: GLYPH_SIZE,
    height: GLYPH_SIZE,
    viewBox: "0 0 16 16",
    "aria-hidden": true as const,
    focusable: "false" as const,
    style: { flex: "0 0 auto" },
  };

  switch (kind) {
    case "circle-open":
      return (
        <svg {...common}>
          <circle cx="8" cy="8" r="5.5" fill="none" stroke={color} strokeWidth="2" />
        </svg>
      );
    // A quarter / half / three-quarter progression for the three statuses that
    // share --st-active. In greyscale these are the ONLY thing distinguishing
    // them from each other besides the word, so the fill fractions are large
    // and unambiguous rather than subtle.
    case "quarter":
      return (
        <svg {...common}>
          <circle cx="8" cy="8" r="5.5" fill="none" stroke={color} strokeWidth="2" />
          <path d="M8 8 L8 2.5 A5.5 5.5 0 0 1 13.5 8 Z" fill={color} />
        </svg>
      );
    case "half":
      return (
        <svg {...common}>
          <circle cx="8" cy="8" r="5.5" fill="none" stroke={color} strokeWidth="2" />
          <path d="M8 2.5 A5.5 5.5 0 0 1 8 13.5 Z" fill={color} />
        </svg>
      );
    case "three-quarter":
      return (
        <svg {...common}>
          <circle cx="8" cy="8" r="5.5" fill="none" stroke={color} strokeWidth="2" />
          <path d="M8 2.5 A5.5 5.5 0 1 1 2.5 8 Z" fill={color} />
        </svg>
      );
    case "pause":
      return (
        <svg {...common}>
          <rect x="3.5" y="2.5" width="3.5" height="11" rx="1" fill={color} />
          <rect x="9" y="2.5" width="3.5" height="11" rx="1" fill={color} />
        </svg>
      );
    case "check":
      return (
        <svg {...common}>
          <path
            d="M2.5 8.5 L6.5 12.5 L13.5 3.5"
            fill="none"
            stroke={color}
            strokeWidth="2.5"
            strokeLinecap="square"
          />
        </svg>
      );
    case "reopen":
      return (
        <svg {...common}>
          <path
            d="M13 8a5 5 0 1 1-1.6-3.7"
            fill="none"
            stroke={color}
            strokeWidth="2"
            strokeLinecap="square"
          />
          <path d="M13.5 1.5 L13.5 5.5 L9.5 5.5 Z" fill={color} />
        </svg>
      );
    case "square":
      return (
        <svg {...common}>
          <rect x="3" y="3" width="10" height="10" fill={color} />
        </svg>
      );
    case "cross":
      return (
        <svg {...common}>
          <path
            d="M3.5 3.5 L12.5 12.5 M12.5 3.5 L3.5 12.5"
            stroke={color}
            strokeWidth="2.5"
            strokeLinecap="square"
          />
        </svg>
      );
    case "warning":
      return (
        <svg {...common}>
          <path d="M8 1.5 L15 14 L1 14 Z" fill={color} />
          <path d="M8 6 L8 10" stroke="var(--surface-raised)" strokeWidth="1.75" />
          <circle cx="8" cy="12" r="0.9" fill="var(--surface-raised)" />
        </svg>
      );
  }
}

/**
 * The left rule -- the shape half of the encoding.
 *
 * Every shape paints inside a fixed-width track rather than relying on
 * `border-left-style`. That is not a stylistic preference: the first version
 * used border styles plus a background trick for `outlined`, and because the
 * element had no intrinsic width the backgrounds never painted, so RESOLVED
 * rendered with no rule at all. The greyscale screenshot is what caught it --
 * in colour the green glyph and green word carried the row and the missing
 * third encoding was invisible.
 *
 * The track is a constant 6px so the rules align down a column of rows; the
 * mark inside it varies in width, fill and rhythm.
 */
const RULE_TRACK = 6;

function ruleStyle(shape: RuleShape, color: string): React.CSSProperties {
  const track: React.CSSProperties = {
    alignSelf: "stretch",
    width: RULE_TRACK,
    flex: `0 0 ${RULE_TRACK}px`,
    backgroundRepeat: "no-repeat",
  };

  switch (shape) {
    case "solid":
      return {
        ...track,
        backgroundImage: `linear-gradient(${color}, ${color})`,
        backgroundSize: "3px 100%",
      };
    case "heavy":
      return { ...track, backgroundImage: `linear-gradient(${color}, ${color})`, backgroundSize: "6px 100%" };
    case "hairline":
      return { ...track, backgroundImage: `linear-gradient(${color}, ${color})`, backgroundSize: "1px 100%" };
    case "double":
      // Two 2px bars with a 2px gap.
      return {
        ...track,
        backgroundImage: `linear-gradient(${color}, ${color}), linear-gradient(${color}, ${color})`,
        backgroundSize: "2px 100%, 2px 100%",
        backgroundPosition: "0 0, 4px 0",
      };
    case "outlined":
      // Present but hollow: two hairlines with a clear centre. Reads as an
      // outline at a glance and stays distinct from `double` because the bars
      // are 1px rather than 2px and sit tight against the track edges.
      return {
        ...track,
        backgroundImage: `linear-gradient(${color}, ${color}), linear-gradient(${color}, ${color})`,
        backgroundSize: "1px 100%, 1px 100%",
        backgroundPosition: "0 0, 3px 0",
      };
    case "dashed":
      return {
        ...track,
        backgroundImage: `repeating-linear-gradient(to bottom, ${color} 0 6px, transparent 6px 11px)`,
        backgroundSize: "3px 100%",
      };
    case "dotted":
      return {
        ...track,
        backgroundImage: `repeating-linear-gradient(to bottom, ${color} 0 3px, transparent 3px 7px)`,
        backgroundSize: "3px 100%",
      };
  }
}

export interface StatusRuleProps {
  status: IssueStatus;
  /** True when the clock has run out. Renders the overdue overlay alongside. */
  overdue?: boolean;
  /** Staff surfaces use the technical vocabulary; public ones do not. */
  audience?: "public" | "staff";
  /** Hide the word. Only legitimate where an adjacent cell carries it. */
  wordless?: boolean;
}

export function StatusRule({
  status,
  overdue = false,
  audience = "public",
  wordless = false,
}: StatusRuleProps) {
  const token: StatusToken = STATUS[status];
  const word = statusWord(status, audience);

  return (
    <span className="inline-flex items-stretch gap-2" style={{ minHeight: 20 }}>
      <span style={ruleStyle(token.shape, token.color)} aria-hidden="true" />
      <span className="inline-flex items-center gap-1.5">
        <Glyph kind={token.glyph} color={token.color} />
        {!wordless && (
          <span className="text-meta whitespace-nowrap" style={{ color: token.color }}>
            {word}
          </span>
        )}
      </span>

      {overdue && (
        <>
          <span style={ruleStyle(OVERDUE.shape, OVERDUE.color)} aria-hidden="true" />
          <span className="inline-flex items-center gap-1.5">
            <Glyph kind={OVERDUE.glyph} color={OVERDUE.color} />
            {!wordless && (
              <span className="text-meta whitespace-nowrap" style={{ color: OVERDUE.color }}>
                {OVERDUE.word}
              </span>
            )}
          </span>
        </>
      )}

      {/* The full statement for assistive technology, in one utterance, so a
          screen reader user is not left assembling "In progress" and "Overdue"
          from two separate visual fragments. */}
      <span className="sr-only">
        {word}
        {overdue ? `, ${OVERDUE.word}` : ""}
      </span>
    </span>
  );
}
