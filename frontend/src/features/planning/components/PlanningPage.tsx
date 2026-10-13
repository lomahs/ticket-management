import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { Fragment, useRef, useState, type DragEvent } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { JIRA_WRITE_OFF, useCapability, useMemberMap, useMeta } from '../../../shared/api/queries';
import { Button, Icon, IconButton } from '../../../shared/ds';
import { add, dm, dow, f1, hrs, mondayOf, WDN } from '../../../shared/format/dates';
import { HOLIDAY_BG, LEAVE, OTHER_STEP, STEPS, dayHeadStyle, hatch, mix, statusOf, stepColor } from '../../../shared/format/labels';
import { taskTitle } from '../../../shared/format/task';
import { useToast } from '../../../shared/state/toast';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av, PageHead } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { planningPreviewQuery, planningQuery, usePushPlan } from '../api';
import { layoutLanes, startForDrop } from '../model/lanes';
import { usePendingMoves } from '../model/pending';

const route = getRouteApi('/planning');
const LANE_H = 32;

export function PlanningPage() {
  const meta = useMeta();
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const today = meta.data?.today;
  const start = mondayOf(search.start ?? today ?? new Date().toISOString().slice(0, 10));
  const weeks = search.weeks ?? 2;
  const hidden = (search.hide ?? '').split(',').filter(Boolean);
  const setSearch = (p: Partial<{ start: string; weeks: 1 | 2; hide: string }>) =>
    navigate({ search: (prev) => ({ ...prev, ...p, hide: p.hide !== undefined ? p.hide || undefined : prev.hide }) });
  const view = useQuery(planningQuery(start, weeks));
  const last = add(start, weeks * 7 - 1);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <PageHead eyebrow="All projects · Planned Start / End from Jira" title="Planning">
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
          <div className="seg" style={{ marginRight: 6 }}>
            {([[1, '1 week'], [2, '2 weeks']] as const).map(([k, l]) => (
              <button key={k} aria-pressed={weeks === k} onClick={() => setSearch({ weeks: k })}
                style={{ background: weeks === k ? 'var(--button-primary-bg)' : 'transparent', color: weeks === k ? 'var(--button-primary-fg)' : 'var(--text-heading)' }}>{l}</button>
            ))}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginRight: 6 }}>
            <input type="date" value={start} aria-label="Start date" title="Start date (snaps to Monday)"
              onChange={(e) => e.target.value && setSearch({ start: mondayOf(e.target.value) })}
              style={{ height: 32, boxSizing: 'border-box', padding: '0 10px', border: '1px solid var(--border-strong)', borderRadius: 8, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '600 13px/1 var(--font-text)' }} />
            <span style={{ fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>to {dm(last)}/{last.slice(0, 4)}</span>
          </div>
          <IconButton icon="chevron-left" label="Previous" appearance="secondary" size="sm" onClick={() => setSearch({ start: add(start, -weeks * 7) })} />
          <Button appearance="secondary" size="sm" onClick={() => setSearch({ start: undefined })}>Today</Button>
          <IconButton icon="chevron-right" label="Next" appearance="secondary" size="sm" onClick={() => setSearch({ start: add(start, weeks * 7) })} />
        </div>
      </PageHead>
      {view.isPending ? <Loading /> : view.isError ? <QueryError error={view.error} /> : (
        <Planning v={view.data} hidden={hidden} setHidden={(h) => setSearch({ hide: h.join(',') })} />
      )}
    </div>
  );
}

function Planning({ v, hidden, setHidden }: { v: Schemas['PlanningView']; hidden: string[]; setHidden: (h: string[]) => void }) {
  const members = useMemberMap();
  const open = useOpenTicket();
  const toast = useToast();
  const canWrite = useCapability('jiraWrite');
  const { moves, set, revert, clear } = usePendingMoves();
  const preview = useQuery(planningPreviewQuery(v.start, moves));
  const push = usePushPlan();
  const [dropRow, setDropRow] = useState<string | null>(null);
  const drag = useRef<{ key: string; grab: number; start: string; from: { start: string; member_id: string | null } } | null>(null);

  const days = v.days.map((d) => d.date);
  const C = Math.max(1, days.length);
  const pc = (n: number) => ((n / C) * 100).toFixed(4) + '%';
  const changes = moves.length ? preview.data?.changes ?? [] : [];
  const moved = Object.fromEntries(changes.map((c) => [c.key, c]));
  const capacity = (moves.length && preview.data ? preview.data.capacity : v.capacity);

  // Items with pending moves are drawn where they will land (dates from the server preview).
  const allBlocks = v.rows.flatMap((r) => r.items.map((b) => ({ b, member: r.member_id })));
  const placed = (memberId: string) => allBlocks
    .map(({ b, member }) => {
      const m = moved[b.card.key];
      return { b, member: m ? m.to_member : member, start: m ? m.to_start : b.card.planned_start!, end: m ? m.to_end : b.card.planned_end!, pend: !!m };
    })
    .filter((x) => x.member === memberId && !hidden.includes(x.b.card.project) && x.end >= v.start && x.start <= v.end);

  const onDrop = (e: DragEvent<HTMLDivElement>, memberId: string) => {
    e.preventDefault();
    const d = drag.current;
    drag.current = null;
    setDropRow(null);
    if (!d) return;
    const r = e.currentTarget.getBoundingClientRect();
    const col = Math.floor(((e.clientX - r.left) / r.width) * C);
    set({ key: d.key, start: startForDrop(days, col, d.start, d.grab), member_id: memberId }, d.from);
  };
  const pushAll = () => push.mutate(moves, {
    onSuccess: () => { toast(`Written to Jira · ${moves.length} plan change${moves.length > 1 ? 's' : ''} from Planning`); clear(); },
    onError: (e) => toast(errorMessage(e), true),
  });
  const ti = v.days.findIndex((d) => d.is_today);
  const weekGroups = [...new Set(days.map((d) => mondayOf(d)))].map((ws) => ({ ws, n: days.filter((d) => mondayOf(d) === ws).length }));
  const name = (id?: string | null) => (id ? members[id]?.name ?? id : '–');

  return (
    <>
      <section className="card card-clip" aria-label="Gantt by member">
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap', padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
          <span className="title">Gantt by member</span>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {v.projects.map((k) => {
              const off = hidden.includes(k);
              return (
                <button key={k} className="hv-bg" aria-pressed={!off} onClick={() => setHidden(off ? hidden.filter((x) => x !== k) : [...hidden, k])} style={{
                  display: 'flex', alignItems: 'center', gap: 6, height: 26, padding: '0 10px', borderRadius: 99, border: '1px solid var(--border-subtle)',
                  background: 'transparent', opacity: off ? 0.4 : 1, font: '500 12px/1 var(--font-text)', color: 'var(--text-heading)', cursor: 'pointer',
                }}><Icon name={off ? 'eye-off' : 'eye'} size={13} />{k}</button>
              );
            })}
          </div>
          <span className="spacer" />
          <div className="legend">
            {[...STEPS.map((s) => [s.label, s.color]), [OTHER_STEP.label, OTHER_STEP.color]].map(([l, c]) => <span key={l}><span style={{ width: 14, height: 9, borderRadius: 3, background: c }} />{l}</span>)}
            <span><span style={{ width: 18, height: 3, borderRadius: 2, background: 'var(--text-heading)' }} />Actual</span>
            <span><span style={{ width: 18, height: 3, borderRadius: 2, background: 'var(--hl-red-fg)' }} />Actual past plan</span>
            <span><span className="swatch" style={{ border: '1px solid var(--border-subtle)', background: HOLIDAY_BG }} />Holiday (VN / JP)</span>
            <span><span className="swatch" style={{ border: '1px solid var(--border-subtle)', background: hatch(LEAVE.full.c) + ',' + mix(LEAVE.full.c, 12) }} />Leave</span>
          </div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ display: 'flex', flexDirection: 'column', minWidth: 720 }}>
            <div style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
              <div style={{ width: 120, flex: 'none', boxSizing: 'border-box', borderRight: '1px solid var(--border-subtle)', padding: '8px 12px', display: 'flex', alignItems: 'flex-end', fontSize: 11, color: 'var(--text-muted)' }}>Member</div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex' }}>
                  {weekGroups.map((w) => (
                    <div key={w.ws} style={{ width: pc(w.n), boxSizing: 'border-box', flex: 'none', padding: '6px 8px', borderLeft: '1px solid var(--border-subtle)', font: '600 11px/1 var(--font-text)', color: 'var(--text-heading)', whiteSpace: 'nowrap', overflow: 'hidden' }}>
                      {dm(w.ws)} – {dm(add(w.ws, 6))}
                    </div>
                  ))}
                </div>
                <div style={{ display: 'flex' }}>
                  {v.days.map((d) => {
                    const h = dayHeadStyle(d);
                    return (
                      <div key={d.date} className="day-head" title={h.tip} style={{ width: pc(1), flex: 'none', padding: '3px 0 5px', background: h.bg, color: h.fg }}>
                        <div>{WDN[dow(d.date)]}</div><div className="n" style={{ fontWeight: h.fw }}>{d.date.slice(8, 10)}</div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
            {v.rows.map((r) => {
              const m = av(members[r.member_id], r.member_id);
              const items = placed(r.member_id);
              const { blocks, lanes } = layoutLanes(items.map((x) => ({ key: x.b.card.key, start: x.start, end: x.end })), days);
              const byKey = Object.fromEntries(items.map((x) => [x.b.card.key, x]));
              return (
                <div key={r.member_id} style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
                  <div style={{ width: 120, flex: 'none', boxSizing: 'border-box', borderRight: '1px solid var(--border-subtle)', padding: 10, display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                    <Avatar a={m} size={22} />
                    <div style={{ minWidth: 0 }}>
                      <div className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{m.name}</div>
                      <div className="ellipsis" style={{ fontSize: 11, color: 'var(--text-muted)' }}>{members[r.member_id]?.role ?? 'No role'}{members[r.member_id]?.external ? ' · client' : ''}</div>
                    </div>
                  </div>
                  <div
                    aria-label={`${m.name} plan`}
                    onDragOver={(e) => { e.preventDefault(); if (dropRow !== r.member_id) setDropRow(r.member_id); }}
                    onDrop={(e) => onDrop(e, r.member_id)}
                    style={{ position: 'relative', flex: 1, minWidth: 0, height: lanes * LANE_H + 10, boxShadow: dropRow === r.member_id ? 'inset 0 0 0 2px var(--border-focus)' : 'none' }}
                  >
                    <div style={{ position: 'absolute', inset: 0, display: 'flex', pointerEvents: 'none' }}>
                      {r.days.map((c) => {
                        const bg = c.kind === 'holiday' ? HOLIDAY_BG
                          : c.kind === 'leave' ? hatch(LEAVE[c.tentative ? 'fullTent' : 'full'].c) + ',' + mix(LEAVE.full.c, 12)
                          : c.kind === 'leave_half' ? `linear-gradient(to bottom,${mix(LEAVE[c.tentative ? 'halfTent' : 'half'].c, 30)} 50%,transparent 50%)`
                          : v.days.find((d) => d.date === c.date)?.is_today ? 'var(--surface-tint)' : v.days.find((d) => d.date === c.date)?.extra_note ? mix(STEPS[0].color, 8) : 'transparent';
                        return <div key={c.date} title={`${dm(c.date)}${c.label ? ' · ' + c.label : ''}`} style={{ width: pc(1), boxSizing: 'border-box', flex: 'none', borderLeft: '1px solid var(--border-subtle)', background: bg }} />;
                      })}
                    </div>
                    {ti >= 0 && <div style={{ position: 'absolute', top: 0, bottom: 0, left: `calc(${pc(ti + 0.5)} - 1px)`, width: 2, background: 'var(--hl-red-fg)', opacity: 0.5, pointerEvents: 'none' }} />}
                    {blocks.map((bl) => {
                      const x = byKey[bl.key];
                      const c = x.b.card;
                      const top = 6 + bl.lane * LANE_H;
                      const act = c.actual_start && c.actual_end && c.actual_end >= v.start && c.actual_start <= v.end
                        ? layoutLanes([{ key: 'a', start: c.actual_start, end: c.actual_end }], days).blocks[0] : undefined;
                      const actLate = !!(c.actual_end && c.planned_end && c.actual_end > c.planned_end);
                      const tip = `${c.key} · ${c.summary}\n${STEPS.find((s) => s.k === x.b.color_step)?.label ?? OTHER_STEP.label} · ${c.project}\n`
                        + `Plan ${dm(x.start)} – ${dm(x.end)}\n${c.actual_start ? `Actual ${dm(c.actual_start)} – ${dm(c.actual_end)}` : 'Actual: not started'}\n`
                        + `${f1(c.logged_h)} / ${hrs(c.estimate_h)} · ${statusOf(c.status).label}${x.pend ? '\nPending push to Jira' : ''}${canWrite ? '\nDrag to change dates or assignee' : ''}`;
                      return (
                        <Fragment key={bl.key}>
                          <div
                            draggable={canWrite}
                            className="hv-bright"
                            title={tip}
                            role="button"
                            aria-label={`${c.key} ${taskTitle(c)}`}
                            onClick={() => open(c.key)}
                            onDragStart={(ev) => {
                              const g = ev.currentTarget.parentElement!.getBoundingClientRect();
                              drag.current = { key: c.key, grab: Math.floor(((ev.clientX - g.left) / g.width) * C) - bl.col, start: x.start,
                                from: { start: c.planned_start!, member_id: c.worker_id } }; // where Jira has it
                              ev.dataTransfer.setData('text/plain', c.key);
                              ev.dataTransfer.effectAllowed = 'move';
                            }}
                            onDragEnd={() => { drag.current = null; setDropRow(null); }}
                            style={{
                              position: 'absolute', zIndex: 1, boxShadow: '0 0 0 1px var(--surface-card)', left: `calc(${pc(bl.col)} + 2px)`, top, width: `calc(${pc(bl.span)} - 4px)`,
                              height: 20, boxSizing: 'border-box', padding: '0 6px', borderRadius: 6, background: stepColor(x.b.color_step),
                              outline: x.pend ? '2px dashed var(--text-heading)' : 'none', outlineOffset: 1, color: '#fff', font: '600 11px/20px var(--font-mono)',
                              whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', cursor: canWrite ? 'grab' : 'pointer',
                            }}
                          >{(c.status === 'C' ? '✓ ' : '') + c.key}</div>
                          {act && (
                            <div style={{ position: 'absolute', left: `calc(${pc(act.col)} + 2px)`, top: top + 23, width: `calc(${pc(act.span)} - 4px)`, height: 3, borderRadius: 2,
                              background: actLate ? 'var(--hl-red-fg)' : 'var(--text-heading)', pointerEvents: 'none' }} />
                          )}
                        </Fragment>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      <section className="card card-clip" aria-label="Changes pending push to Jira">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '12px 16px' }}>
          <Icon name="upload-cloud" size={16} />
          <span className="title">Changes pending push to Jira</span>
          {moves.length > 0 && <span className="count" style={{ padding: '0 8px', background: 'var(--surface-tint)', color: 'var(--text-heading)' }}>{moves.length}{moves.length === 1 ? ' change' : ' changes'}</span>}
          <span className="spacer" />
          {moves.length > 0 && (
            <>
              <Button appearance="subtle" size="sm" onClick={clear}>Discard all</Button>
              <Button size="sm" iconBefore="upload" disabled={!canWrite || push.isPending} title={canWrite ? undefined : JIRA_WRITE_OFF} onClick={pushAll}>Push to Jira</Button>
            </>
          )}
        </div>
        {!moves.length && (
          <div style={{ padding: '0 16px 14px', fontSize: 12, color: 'var(--text-muted)' }}>
            {canWrite ? 'Drag a block sideways to change its dates, or onto another member to reassign it. Changes collect here until you push them.'
              : `${JIRA_WRITE_OFF}. Plan dates are read from Jira.`}
          </div>
        )}
        {preview.isError && <div style={{ padding: '0 16px 12px' }}><QueryError error={preview.error} /></div>}
        {changes.map((c) => {
          const card = allBlocks.find((x) => x.b.card.key === c.key)?.b;
          const diffs = [
            c.from_start !== c.to_start && ['Planned Start', dm(c.from_start), dm(c.to_start)],
            c.from_end !== c.to_end && ['Planned End', dm(c.from_end), dm(c.to_end)],
            c.from_member !== c.to_member && ['Assignee', name(c.from_member), name(c.to_member)],
          ].filter(Boolean) as [string, string, string][];
          return (
            <div key={c.key} style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1.3fr) minmax(0,2fr) auto', gap: 12, alignItems: 'center', padding: '10px 16px', borderTop: '1px solid var(--border-subtle)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <span style={{ width: 10, height: 10, borderRadius: 3, background: stepColor(card?.color_step), flex: 'none' }} />
                <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-link)', flex: 'none' }}>{c.key}</span>
                <span className="ellipsis heading" style={{ minWidth: 0 }}>{card?.card.summary}</span>
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
                {diffs.map(([f, a, b]) => (
                  <span key={f} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 8px', borderRadius: 8, background: 'var(--surface-hover)', fontSize: 12 }}>
                    <span className="muted">{f}</span><span className="muted" style={{ textDecoration: 'line-through' }}>{a}</span><span className="muted">→</span><b className="heading">{b}</b>
                  </span>
                ))}
                {c.day_off.length > 0 && <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--hl-amber-fg)' }}>Lands on a day off: {c.day_off.map(({ date, reason }) => `${dm(date)} ${reason}`).join(', ')}</span>}
              </div>
              <IconButton icon="undo-2" label="Revert" size="sm" onClick={() => revert(c.key)} />
            </div>
          );
        })}
      </section>

      <section className="card" aria-label="Capacity by week" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: 220 }}>
            <div className="title">Capacity by week</div>
            <div className="eyebrow">Planned effort ÷ available hours after leave and holidays · includes pending changes</div>
          </div>
          <div style={{ display: 'flex', gap: 12, fontSize: 11, color: 'var(--text-muted)' }}>
            {([['Healthy', 'var(--hl-ok)'], ['Near full', 'var(--hl-amber)'], ['Over capacity', 'var(--hl-red)']] as const).map(([l, bg]) => (
              <span key={l} style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span className="swatch" style={{ background: bg }} />{l}</span>
            ))}
          </div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 600, display: 'flex', flexDirection: 'column', gap: 4 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '150px repeat(5,minmax(84px,1fr))', gap: 4, fontSize: 11, color: 'var(--text-muted)' }}>
              <span />
              {(capacity[0]?.weeks ?? []).map((w) => <span key={w.week_start} style={{ padding: '0 4px', fontWeight: 600 }}>{dm(w.week_start)} – {dm(add(w.week_start, 6))}</span>)}
            </div>
            {capacity.map((row) => {
              const m = av(members[row.member_id], row.member_id);
              return (
                <div key={row.member_id} style={{ display: 'grid', gridTemplateColumns: '150px repeat(5,minmax(84px,1fr))', gap: 4, alignItems: 'stretch' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Avatar a={m} size={22} /><span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{m.name}</span></div>
                  {row.weeks.map((w) => {
                    const over = w.level === 'over';
                    const bg = { over: 'var(--hl-red)', near: 'var(--hl-amber)', ok: 'var(--hl-ok)', idle: 'var(--surface-hover)' }[w.level] ?? 'var(--surface-hover)';
                    const sub = `${f1(w.planned_h)} / ${f1(w.available_h)}h`;
                    return (
                      <div key={w.week_start} title={sub} style={{ padding: '7px 10px', borderRadius: 8, display: 'flex', alignItems: 'baseline', gap: 6, background: bg }}>
                        <span style={{ font: `${over ? 700 : 600} 14px/1 var(--font-display)`, color: over ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{w.pct == null ? '–' : w.pct + '%'}</span>
                        <span style={{ fontSize: 10, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{sub}</span>
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </div>
        </div>
      </section>
    </>
  );
}
