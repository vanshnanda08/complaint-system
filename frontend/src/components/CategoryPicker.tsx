"use client";

import { glyphFor } from "@/lib/categoryGlyphs";

/**
 * The report composer's category picker.
 *
 * A two-column grid of labelled targets, minimum 48x48px (blueprint 3.2 and 8):
 * this is the control a person operates one-handed, outdoors, in glare, and it
 * is the last thing in the flow before they submit.
 *
 * Every target is a real radio input with a real label, so it is reachable and
 * operable from a keyboard and announced as a group -- rather than a div with
 * an onClick, which is the usual way a picker like this stops working for
 * anybody not using a mouse.
 *
 * Selection is indicated by a heavier border and a filled ink chip, never by
 * colour: a category is not a status.
 */

export interface Category {
  code: string;
  displayName: string;
}

export interface CategoryPickerProps {
  categories: Category[];
  value?: string;
  onChange?: (code: string) => void;
  name?: string;
}

export function CategoryPicker({
  categories,
  value,
  onChange,
  name = "categoryCode",
}: CategoryPickerProps) {
  return (
    <fieldset className="border-0 p-0 m-0">
      <legend className="text-meta text-ink mb-2">What is the problem?</legend>

      <div className="grid grid-cols-2 gap-2">
        {categories.map((c) => {
          const selected = value === c.code;
          return (
            <label
              key={c.code}
              // Selection is confirmed by a brief scale overshoot on the glyph
              // (u-pop, below) rather than by colour alone. This control is
              // used outdoors, one-handed, often on a screen that is not
              // clean, and the border going from 1px to 2px is not much to
              // notice under those conditions.
              className="flex items-center gap-3 px-3 py-2 cursor-pointer bg-surface-raised
                         u-press transition-[border-color,background-color]
                         duration-[var(--dur-fast)] hover:bg-surface
                         has-[:focus-visible]:outline has-[:focus-visible]:outline-2
                         has-[:focus-visible]:outline-offset-2"
              style={{
                minHeight: "var(--hit-min)",
                borderRadius: "var(--radius)",
                border: `${selected ? 2 : 1}px solid var(--ink)`,
                borderColor: selected ? "var(--ink)" : "var(--rule)",
                outlineColor: "var(--ink)",
              }}
            >
              <input
                type="radio"
                name={name}
                value={c.code}
                checked={selected}
                onChange={() => onChange?.(c.code)}
                className="sr-only"
              />
              <span
                // `key` changes with the selected state, which remounts the
                // span and so replays the animation. Without that, React keeps
                // the same element and the keyframes -- already finished --
                // never run again, so the pop happens once and never more.
                key={selected ? "on" : "off"}
                className={`flex items-center justify-center shrink-0 ${selected ? "u-pop" : ""}`}
                style={{
                  width: 32,
                  height: 32,
                  borderRadius: "var(--radius-sm)",
                  background: selected ? "var(--ink)" : "transparent",
                  color: selected ? "var(--surface-raised)" : "var(--ink)",
                  transition: "background-color var(--dur-fast) var(--ease-standard), "
                            + "color var(--dur-fast) var(--ease-standard)",
                }}
              >
                {glyphFor(c.code)}
              </span>
              <span className="text-dense">{c.displayName}</span>
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
