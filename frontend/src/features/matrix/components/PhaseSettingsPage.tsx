import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useState, type DragEvent, type KeyboardEvent } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { matrixQuery } from '../api';
import { applyLayout, useMatrixLayout, type MatrixLayout } from '../model/layout';
import { MatrixHeader } from './MatrixHeader';

const route = getRouteApi('/app/anken/$epic/matrix/phases');

/** The order of phases and of the Tasks (sub-phases) in each, and the Tasks shown on the matrix. */
interface Draft { phases: string[]; tasks: Record<string, string[]>; shown: string[] }
type Drag = { kind: 'phase'; g: string } | { kind: 'task'; g: string; key: string };

/** The saved layout over the anken's current Tasks; without one, the default order and the default shown Tasks. */
const toDraft = (m: Schemas['MatrixView'], layout: MatrixLayout | null): Draft => {
  const parents = applyLayout(m.parents, layout);
  const phases = [...new Set(parents.map((p) => p.group))];
  const shown = parents.map((p) => p.key).filter((k) => layout?.shown.includes(k));
  return {
    phases,
    tasks: Object.fromEntries(phases.map((g) => [g, parents.filter((p) => p.group === g).map((p) => p.key)])),
    shown: shown.length ? shown : m.task_keys,
  };
};

/** The list with {@code item} moved to where {@code target} was. */
const move = <T,>(list: T[], item: T, target: T) => {
  const to = list.indexOf(target);
  const out = list.filter((x) => x !== item);
  out.splice(to, 0, item);
  return out;
};

export function PhaseSettingsPage() {
  const { epic } = route.useParams();
  const q = useQuery(matrixQuery(epic)); // without a layout: the default order and shown Tasks
  const [layout, setLayout] = useMatrixLayout(epic);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0 }}>
      <MatrixHeader epic={epic} title={q.data?.title} tab="phases" />
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} />
        : <PhaseSettings key={JSON.stringify(toDraft(q.data, layout))} m={q.data} saved={toDraft(q.data, layout)} custom={!!layout} setLayout={setLayout} />}
    </div>
  );
}

function PhaseSettings({ m, saved, custom, setLayout }: {
  m: Schemas['MatrixView']; saved: Draft; custom: boolean; setLayout: (l: MatrixLayout | null) => void;
}) {
  const toast = useToast();
  const [d, setD] = useState<Draft>(saved);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const byKey = Object.fromEntries(m.parents.map((p) => [p.key, p]));
  const dirty = JSON.stringify(d) !== JSON.stringify(saved);

  if (!m.parents.length) {
    return <div className="card" style={{ padding: 24, color: 'var(--text-muted)' }}>This anken has no Tasks yet.</div>;
  }

  const order = (g: string) => d.tasks[g] ?? [];
  const setShown = (next: string[]) => {
    if (next.length) setD({ ...d, shown: d.phases.flatMap(order).filter((k) => next.includes(k)) });
  };
  const movePhase = (g: string, target: string) => g !== target && setD({ ...d, phases: move(d.phases, g, target) });
  const moveTask = (g: string, key: string, target: string) => key !== target && setD({ ...d, tasks: { ...d.tasks, [g]: move(order(g), key, target) } });
  const step = <T,>(list: T[], item: T, by: number) => list[Math.max(0, Math.min(list.length - 1, list.indexOf(item) + by))];
  const end = () => { setDrag(null); setOver(null); };
  const body = (x: Draft): MatrixLayout => {
    const tasks = x.phases.flatMap((g) => x.tasks[g] ?? []);
    return { phases: x.phases, tasks, shown: tasks.filter((k) => x.shown.includes(k)) };
  };

  // Drop targets accept only what they can take: a phase on a phase, a Task on a Task of its own phase.
  const phaseTarget = (g: string) => ({
    onDragOver: (e: DragEvent) => { if (drag?.kind === 'phase') { e.preventDefault(); setOver('phase:' + g); } },
    onDrop: (e: DragEvent) => { if (drag?.kind === 'phase') { e.preventDefault(); movePhase(drag.g, g); end(); } },
  });
  const taskTarget = (g: string, key: string) => ({
    onDragOver: (e: DragEvent) => { if (drag?.kind === 'task' && drag.g === g) { e.preventDefault(); e.stopPropagation(); setOver('task:' + key); } },
    onDrop: (e: DragEvent) => { if (drag?.kind === 'task' && drag.g === g) { e.preventDefault(); e.stopPropagation(); moveTask(g, drag.key, key); end(); } },
  });
  const start = (e: DragEvent, x: Drag) => {
    e.stopPropagation();
    e.dataTransfer?.setData('text/plain', x.kind === 'phase' ? x.g : x.key);
    if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
    setDrag(x);
  };

  return (
    <section className="card" aria-label="Setting phase" style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: '14px 16px' }}>
      <div>
        <div className="title">Phases and sub-phases</div>
        <div className="eyebrow">
          Drag a phase to change the phase order, or a sub-phase to change its order within its phase (Alt + arrow keys work too).
          Click a sub-phase to show or hide it on the Matrix, or a phase name for all of its sub-phases. Saved in this browser: only you see this layout.
        </div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {d.phases.map((g) => {
          const keys = order(g);
          const all = keys.every((k) => d.shown.includes(k));
          const some = keys.some((k) => d.shown.includes(k));
          return (
            <div key={g} role="group" aria-label={g} draggable onDragStart={(e) => start(e, { kind: 'phase', g })} onDragEnd={end} {...phaseTarget(g)}
              style={{
                display: 'flex', alignItems: 'center', gap: 6, padding: '6px 8px', borderRadius: 12, background: 'var(--surface-card)',
                border: `1px ${over === 'phase:' + g ? 'dashed var(--border-focus)' : 'solid var(--border-subtle)'}`,
                opacity: drag?.kind === 'phase' && drag.g === g ? 0.5 : 1,
              }}>
              <button aria-label={`Move ${g}`} title="Drag, or Alt + ↑ / ↓, to move the phase"
                onKeyDown={(e: KeyboardEvent) => { if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) { e.preventDefault(); movePhase(g, step(d.phases, g, e.key === 'ArrowUp' ? -1 : 1)); } }}
                style={{ display: 'flex', alignItems: 'center', alignSelf: 'stretch', flexShrink: 0, padding: '0 2px', border: 0, background: 'transparent', color: 'var(--text-muted)', cursor: 'grab' }}>
                <Icon name="grip-vertical" size={16} />
              </button>
              <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 6, minWidth: 0 }}>
                <button className="hv-bg" aria-pressed={all} title={all ? `Hide every sub-phase of ${g}` : `Show every sub-phase of ${g}`}
                  onClick={() => setShown(all ? d.shown.filter((k) => !keys.includes(k)) : [...d.shown, ...keys])}
                  style={{ height: 28, padding: '0 8px', border: 0, borderRadius: 8, background: 'transparent', cursor: 'pointer', font: '700 12px/1 var(--font-text)', color: some ? 'var(--text-heading)' : 'var(--text-muted)' }}>
                  {g}
                </button>
                {keys.map((k) => {
                  const t = byKey[k];
                  const on = d.shown.includes(k);
                  return (
                    <button key={k} className="hv-bd" draggable aria-pressed={on} title={`${t.key} · ${t.summary}\nDrag, or Alt + ← / →, to move it within ${g}`}
                      onClick={() => setShown(on ? d.shown.filter((x) => x !== k) : [...d.shown, k])}
                      onKeyDown={(e: KeyboardEvent) => { if (e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) { e.preventDefault(); moveTask(g, k, step(keys, k, e.key === 'ArrowLeft' ? -1 : 1)); } }}
                      onDragStart={(e) => start(e, { kind: 'task', g, key: k })} onDragEnd={end} {...taskTarget(g, k)}
                      style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6, height: 28, padding: '0 10px', borderRadius: 8, cursor: 'grab', whiteSpace: 'nowrap',
                        border: `1px ${over === 'task:' + k ? 'dashed' : 'solid'} ${on || over === 'task:' + k ? 'var(--border-focus)' : 'var(--border-subtle)'}`,
                        background: on ? 'var(--surface-tint)' : 'var(--surface-card)', color: 'var(--text-heading)', font: `${on ? 600 : 500} 12px/1 var(--font-text)`,
                        opacity: drag?.kind === 'task' && drag.key === k ? 0.5 : 1,
                      }}>
                      {t.name || t.summary}
                      <span style={{ font: '600 10px/16px var(--font-mono)', padding: '0 5px', borderRadius: 999, background: 'var(--surface-hover)', color: t.functions ? 'var(--text-heading)' : 'var(--text-disabled)' }}>{t.functions}</span>
                    </button>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span className="eyebrow">{d.shown.length} of {m.parents.length} sub-phases shown on the Matrix</span>
        <span className="spacer" />
        <Button appearance="subtle" disabled={!custom} onClick={() => { setLayout(null); toast('Phase layout reset to the default'); }}>Reset to default</Button>
        <Button appearance="subtle" disabled={!dirty} onClick={() => setD(saved)}>Discard changes</Button>
        <Button disabled={!dirty} onClick={() => { setLayout(body(d)); toast('Phase layout saved in this browser'); }}>Save</Button>
      </div>
    </section>
  );
}
