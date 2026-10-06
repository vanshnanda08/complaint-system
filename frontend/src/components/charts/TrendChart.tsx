"use client";

import { useId, useState } from "react";
import { fmt, niceTicks, shortDay, useWidth } from "./chartKit";

/**
 * A daily series chart: one or two lines on ONE y-axis, sharing a unit.
 *
 * Built for "reported against resolved", which is an emphasis chart rather
 * than a categorical one: the resolved line is the point and wears the
 * resolved status colour; new issues are the context it is read against, in
 * the validated context gray. Both are issues per day, so one axis is honest
 * -- a second axis is never offered.
 *
 * Interaction, per the dataviz rules: a crosshair snaps to the nearest day and
 * one tooltip lists every series there; the same readout follows keyboard
 * focus (arrow keys) so nothing is pointer-only; and every value is also in
 * the table view beneath, so the tooltip enhances and never gates.
 */
export interface TrendSeries<K extends string> {
  key: K;
  label: string;
  /** A CSS colour, normally a token: var(--st-resolved). */
  color: string;
}

export interface TrendChartProps<K extends string> {
  /** Accessible name and the table's caption. */
  title: string;
  series: TrendSeries<K>[];
  rows: ({ day: string } & Record<K, number>)[];
  height?: number;
}

const PAD = { top: 12, right: 72, bottom: 28, left: 36 };

export function TrendChart<K extends string>({ title, series, rows, height = 220 }: TrendChartProps<K>) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const tableId = useId();

  const plotW = Math.max(0, width - PAD.left - PAD.right);
  const plotH = height - PAD.top - PAD.bottom;
  const max = Math.max(1, ...rows.flatMap((r) => series.map((s) => r[s.key])));
  const ticks = niceTicks(max);
  const top = ticks[ticks.length - 1];
  const x = (i: number) => PAD.left + (rows.length <= 1 ? plotW / 2 : (i / (rows.length - 1)) * plotW);
  const y = (v: number) => PAD.top + plotH - (v / top) * plotH;

  // Six date labels at most, evenly spaced, always including the last day.
  const every = Math.max(1, Math.ceil(rows.length / 6));
  const xLabels = rows.map((r, i) => ({ i, day: r.day })).filter(({ i }) => (rows.length - 1 - i) % every === 0);

  // End labels only when the two ends are far enough apart to stay attached
  // to their lines. Nudging them apart would detach them; the legend and the
  // tooltip carry identity either way.
  const last = rows[rows.length - 1];
  const endYs = last ? series.map((s) => y(last[s.key])) : [];
  const endLabelsFit = endYs.length < 2 || Math.abs(endYs[0] - endYs[1]) >= 14;

  const onMove = (clientX: number, rect: DOMRect) => {
    if (rows.length === 0 || plotW <= 0) return;
    const rel = (clientX - rect.left - PAD.left) / plotW;
    setHover(Math.min(rows.length - 1, Math.max(0, Math.round(rel * (rows.length - 1)))));
  };

  const active = hover === null ? null : rows[hover];

  return (
    <figure className="m-0">
      {/* Legend: always present for two or more series, line keys to match the marks. */}
      {series.length > 1 && (
        <ul className="flex flex-wrap gap-x-5 gap-y-1 list-none p-0 m-0 mb-2 text-meta text-ink-muted">
          {series.map((s) => (
            <li key={s.key} className="flex items-center gap-2">
              <span aria-hidden="true" style={{ width: 14, height: 2, background: s.color, borderRadius: 1 }} />
              {s.label}
            </li>
          ))}
        </ul>
      )}

      <div ref={ref} className="relative" style={{ height }}>
        {width > 0 && (
          <svg
            width={width}
            height={height}
            role="img"
            aria-label={`${title}. Use the left and right arrow keys to read each day.`}
            aria-describedby={tableId}
            tabIndex={0}
            className="block outline-none focus-visible:ring-2 focus-visible:ring-ink"
            style={{ borderRadius: "var(--radius)" }}
            onPointerMove={(e) => onMove(e.clientX, e.currentTarget.getBoundingClientRect())}
            onPointerLeave={() => setHover(null)}
            onBlur={() => setHover(null)}
            onKeyDown={(e) => {
              if (rows.length === 0) return;
              if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
                e.preventDefault();
                const step = e.key === "ArrowRight" ? 1 : -1;
                setHover((h) => Math.min(rows.length - 1, Math.max(0, (h ?? rows.length - 1) + (h === null ? 0 : step))));
              }
            }}
          >
            {/* Recessive grid: solid hairlines, one step off the surface. */}
            {ticks.map((t) => (
              <g key={t}>
                <line x1={PAD.left} x2={PAD.left + plotW} y1={y(t)} y2={y(t)} stroke="var(--rule)" strokeWidth={1} />
                <text x={PAD.left - 6} y={y(t)} dy="0.32em" textAnchor="end" className="text-meta tabular-nums" fill="var(--ink-muted)">
                  {fmt(t)}
                </text>
              </g>
            ))}
            {xLabels.map(({ i, day }) => (
              <text key={day} x={x(i)} y={height - 8} textAnchor="middle" className="text-meta" fill="var(--ink-muted)">
                {shortDay(day)}
              </text>
            ))}

            {series.map((s) => (
              <polyline
                key={s.key}
                fill="none"
                stroke={s.color}
                strokeWidth={2}
                strokeLinejoin="round"
                strokeLinecap="round"
                points={rows.map((r, i) => `${x(i)},${y(r[s.key])}`).join(" ")}
              />
            ))}

            {/* End markers with a surface ring, and end labels when they fit. */}
            {last &&
              series.map((s, n) => (
                <g key={s.key}>
                  <circle cx={x(rows.length - 1)} cy={endYs[n]} r={4} fill={s.color} stroke="var(--surface-raised)" strokeWidth={2} />
                  {endLabelsFit && (
                    <text x={x(rows.length - 1) + 10} y={endYs[n]} dy="0.32em" className="text-meta" fill="var(--ink)">
                      {fmt(last[s.key])} {s.label.toLowerCase()}
                    </text>
                  )}
                </g>
              ))}

            {active && hover !== null && (
              <g pointerEvents="none">
                <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotH} stroke="var(--ink-muted)" strokeWidth={1} />
                {series.map((s) => (
                  <circle key={s.key} cx={x(hover)} cy={y(active[s.key])} r={4} fill={s.color} stroke="var(--surface-raised)" strokeWidth={2} />
                ))}
              </g>
            )}
          </svg>
        )}

        {active && hover !== null && (
          <div
            role="status"
            className="absolute pointer-events-none bg-surface-raised border border-rule px-3 py-2 text-meta shadow-sm"
            style={{
              top: PAD.top,
              left: Math.min(Math.max(0, x(hover) + 12), Math.max(0, width - 170)),
              borderRadius: "var(--radius)",
              minWidth: 150,
            }}
          >
            <div className="text-ink-muted">{shortDay(active.day)}</div>
            {series.map((s) => (
              <div key={s.key} className="flex items-center gap-2 mt-1">
                <span aria-hidden="true" style={{ width: 10, height: 2, background: s.color }} />
                <strong className="text-ink tabular-nums">{fmt(active[s.key])}</strong>
                <span className="text-ink-muted">{s.label.toLowerCase()}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      <details id={tableId} className="mt-2">
        <summary className="text-meta text-ink-muted cursor-pointer">Show as a table</summary>
        <div className="mt-2 overflow-x-auto" style={{ maxHeight: 280 }}>
          <table className="w-full text-dense border-collapse">
            <caption className="sr-only">{title}</caption>
            <thead>
              <tr className="border-b border-rule text-meta text-ink-muted">
                <th scope="col" className="text-left font-medium py-1 pr-4">Day</th>
                {series.map((s) => (
                  <th key={s.key} scope="col" className="text-right font-medium py-1 pl-4">{s.label}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {[...rows].reverse().map((r) => (
                <tr key={r.day} className="border-b border-rule">
                  <th scope="row" className="text-left font-normal py-1 pr-4">{shortDay(r.day)}</th>
                  {series.map((s) => (
                    <td key={s.key} className="text-right py-1 pl-4 tabular-nums">{fmt(r[s.key])}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}
