"use client";

import { useId, useState } from "react";
import { fmt, niceTicks, useWidth } from "./chartKit";

/**
 * One series of columns over ordered categories: the backlog-age histogram,
 * the daily compliance trend.
 *
 * A single series is one colour for every column: colouring the taller
 * columns darker would spend the only free channel re-saying what the height
 * already says. Its colour is a neutral mark token, because colour in this
 * system means status and an age bucket is not one.
 *
 * Optionally stacked (`parts`), for a whole split into kinds -- resolved, as
 * on time and late. Segments are separated by a 2px surface gap, never by a
 * drawn border, and a legend is shown because there are two series.
 *
 * Marks: at most 24px wide however wide the slot, 4px rounded at the data end
 * and square at the baseline. Each column is its own hover and focus target,
 * the full height of the plot rather than only its painted pixels, so a
 * one-issue column is as easy to read as a fifty-issue one.
 */
export interface Column {
  key: string;
  label: string;
  /**
   * The x-axis text when the full label will not fit its slot -- "2–4w" for
   * "2–4 weeks". The full label stays in the tooltip, the accessible name and
   * the table, so nothing is only abbreviated.
   */
  axisLabel?: string;
  /** One number per part, bottom first. A single-series chart has one. */
  values: number[];
  /** Tooltip and table text; defaults to the total. */
  detail?: string;
}

export interface ColumnPart {
  label: string;
  color: string;
}

export interface ColumnChartProps {
  title: string;
  columns: Column[];
  height?: number;
  /** Value labels on every cap. Right for six buckets; wrong for thirty days. */
  labelCaps?: boolean;
  /** Show every nth x label. */
  labelEvery?: number;
  valueLabel?: string;
  /** Defaults to one neutral part. */
  parts?: ColumnPart[];
}

const PAD = { top: 18, right: 8, bottom: 28, left: 36 };
const MAX_BAR = 24;
const R = 4;

export function ColumnChart({
  title,
  columns,
  height = 200,
  labelCaps = false,
  labelEvery = 1,
  valueLabel = "Value",
  parts = [{ label: valueLabel, color: "var(--chart-mark)" }],
}: ColumnChartProps) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const tableId = useId();

  const plotW = Math.max(0, width - PAD.left - PAD.right);
  const plotH = height - PAD.top - PAD.bottom;
  const total = (c: Column) => c.values.reduce((a, b) => a + b, 0);
  const ticks = niceTicks(Math.max(1, ...columns.map(total)));
  const top = ticks[ticks.length - 1];
  const slot = columns.length ? plotW / columns.length : 0;
  // Leaves at least 2px of surface between neighbours even when slots are narrow.
  const barW = Math.max(2, Math.min(MAX_BAR, slot - 2));
  const y = (v: number) => PAD.top + plotH - (v / top) * plotH;
  const base = PAD.top + plotH;

  /**
   * One segment from `from` to `to` (values). Only the topmost segment is
   * rounded -- the data end -- and a 2px surface gap is cut below every
   * segment that sits on another.
   */
  const segment = (cx: number, from: number, to: number, isTop: boolean) => {
    const yBottom = y(from) - (from > 0 ? 2 : 0);
    const yTop = y(to);
    const h = yBottom - yTop;
    if (h <= 0.5) return "";
    const r = isTop ? Math.min(R, h, barW / 2) : 0;
    const l = cx - barW / 2;
    const rt = cx + barW / 2;
    return `M${l},${yBottom} V${yTop + r} Q${l},${yTop} ${l + r},${yTop} H${rt - r} Q${rt},${yTop} ${rt},${yTop + r} V${yBottom} Z`;
  };

  const active = hover === null ? null : columns[hover];

  return (
    <figure className="m-0">
      {parts.length > 1 && (
        <ul className="flex flex-wrap gap-x-5 gap-y-1 list-none p-0 m-0 mb-2 text-meta text-ink-muted">
          {parts.map((p) => (
            <li key={p.label} className="flex items-center gap-2">
              <span aria-hidden="true" style={{ width: 10, height: 10, background: p.color, borderRadius: 2 }} />
              {p.label}
            </li>
          ))}
        </ul>
      )}
      <div ref={ref} className="relative" style={{ height }}>
        {width > 0 && (
          <svg width={width} height={height} role="img" aria-label={title} aria-describedby={tableId} className="block">
            {ticks.map((t) => (
              <g key={t}>
                <line x1={PAD.left} x2={PAD.left + plotW} y1={y(t)} y2={y(t)} stroke="var(--rule)" strokeWidth={1} />
                <text x={PAD.left - 6} y={y(t)} dy="0.32em" textAnchor="end" className="text-meta tabular-nums" fill="var(--ink-muted)">
                  {fmt(t)}
                </text>
              </g>
            ))}

            {columns.map((c, i) => {
              const cx = PAD.left + slot * (i + 0.5);
              const lift = hover === i;
              return (
                <g
                  key={c.key}
                  tabIndex={0}
                  role="img"
                  aria-label={`${c.label}: ${c.detail ?? fmt(total(c))}`}
                  className="outline-none"
                  onPointerEnter={() => setHover(i)}
                  onPointerLeave={() => setHover(null)}
                  onFocus={() => setHover(i)}
                  onBlur={() => setHover(null)}
                >
                  {/* The hit target: the whole slot, top to baseline. */}
                  <rect x={cx - slot / 2} y={PAD.top} width={slot} height={plotH} fill="transparent" />
                  {(() => {
                    const topPart = c.values.reduce((last, v, n) => (v > 0 ? n : last), -1);
                    let acc = 0;
                    return c.values.map((v, n) => {
                      const from = acc;
                      acc += v;
                      return (
                        <path
                          key={n}
                          d={segment(cx, from, acc, n === topPart)}
                          fill={parts[n]?.color ?? "var(--chart-mark)"}
                          opacity={hover !== null && !lift ? 0.55 : 1}
                        />
                      );
                    });
                  })()}
                  {labelCaps && total(c) > 0 && (
                    <text x={cx} y={y(total(c)) - 5} textAnchor="middle" className="text-meta tabular-nums" fill="var(--ink)">
                      {fmt(total(c))}
                    </text>
                  )}
                  {(columns.length - 1 - i) % labelEvery === 0 && (
                    <text x={cx} y={height - 8} textAnchor="middle" className="text-meta" fill="var(--ink-muted)">
                      {c.axisLabel ?? c.label}
                    </text>
                  )}
                </g>
              );
            })}
            <line x1={PAD.left} x2={PAD.left + plotW} y1={base} y2={base} stroke="var(--rule-strong)" strokeWidth={1} />
          </svg>
        )}

        {active && hover !== null && (
          <div
            role="status"
            className="absolute pointer-events-none bg-surface-raised border border-rule px-3 py-2 text-meta shadow-sm"
            style={{
              top: 0,
              left: Math.min(Math.max(0, PAD.left + slot * (hover + 0.5) + 14), Math.max(0, width - 180)),
              borderRadius: "var(--radius)",
            }}
          >
            <strong className="text-ink tabular-nums">{active.detail ?? fmt(total(active))}</strong>
            <div className="text-ink-muted">{active.label}</div>
          </div>
        )}
      </div>

      <details id={tableId} className="mt-2">
        <summary className="text-meta text-ink-muted cursor-pointer">Show as a table</summary>
        <table className="mt-2 w-full text-dense border-collapse">
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr className="border-b border-rule text-meta text-ink-muted">
              <th scope="col" className="text-left font-medium py-1 pr-4">&nbsp;</th>
              <th scope="col" className="text-right font-medium py-1 pl-4">{valueLabel}</th>
            </tr>
          </thead>
          <tbody>
            {columns.map((c) => (
              <tr key={c.key} className="border-b border-rule">
                <th scope="row" className="text-left font-normal py-1 pr-4">{c.label}</th>
                <td className="text-right py-1 pl-4 tabular-nums">{c.detail ?? fmt(total(c))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}
