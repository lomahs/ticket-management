import { useEffect, useState } from 'react';
import { Button, Icon } from '../../../shared/ds';
import type { DetailPlanView } from './api';
import { PushFlow } from './PushControls';
import { CHECKS, FORCEABLE, pushTable, type PushRow } from './pushTable';

/** Asks the plan matrix to show a line: its levels unfolded and the filters cleared when they hide it, then scrolled to. */
export const GO_TO_LINE = 'detail-plan:go';
export const goToLine = (ref: string) => window.dispatchEvent(new CustomEvent<string>(GO_TO_LINE, { detail: ref }));

function Cell({ msgs }: { msgs: string[] | null }) {
  if (msgs == null) return <td className="pp-na" aria-label="Does not apply">–</td>;
  if (!msgs.length) return <td className="pp-ok" aria-label="OK">O</td>;
  return (
    <td className="pp-x" aria-label={`X: ${msgs.join('; ')}`}>
      <span className="pp-xm">X</span>{msgs.map((m) => <span key={m} className="pp-why">{m}</span>)}
    </td>
  );
}

/**
 * What stands before a push of `refs` (every line when null), as a table: a row per line the push sends, a column per check, O
 * when it passes, X with what to change. A row opens its line on the matrix. Force push is offered while only Not yet plan and
 * Step order fail: without a Task, a PIC or the epic no sub-task can be created.
 */
export function PushProblems({ epic, v, name, refs, close, onDone }: {
  epic: string; v: DetailPlanView; name: (id: string) => string; refs: string[] | null; close: () => void; onDone?: () => void;
}) {
  const [all, setAll] = useState(false);
  const t = pushTable(v, refs);
  const shown = all ? t.rows : t.failing;
  const hard = t.general.length > 0 || t.failing.some((r) => CHECKS.some((c) => !FORCEABLE.has(c.k) && (r.checks[c.k]?.length ?? 0) > 0));
  const forced = [...t.general, ...t.failing.flatMap((r) => CHECKS.flatMap((c) => (r.checks[c.k] ?? []).map((m) => `${r.label}: ${m}`)))];
  const go = (r: PushRow) => { close(); goToLine(r.ref); };
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', key);
    return () => document.removeEventListener('keydown', key);
  }, [close]);
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog pp" role="dialog" aria-modal="true" aria-label="Before the push" onClick={(e) => e.stopPropagation()}>
        <div className="pp-head">
          <span className="dialog-title">
            {t.failing.length ? `${t.failing.length} line${t.failing.length === 1 ? '' : 's'} to fix before the push` : 'Fix before the push'}
          </span>
          <span className="spacer" />
          <label className="pp-all"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />Show all lines</label>
        </div>
        {t.general.map((m) => (
          <div key={m} role="alert" className="pp-general"><Icon name="circle-alert" size={14} />{m}</div>
        ))}
        {shown.length > 0 && (
          <div className="pp-scroll">
            <table className="pp-table">
              <thead>
                <tr>
                  <th scope="col">Function – step</th>
                  {CHECKS.map((c) => <th key={c.k} scope="col">{c.label}</th>)}
                </tr>
              </thead>
              <tbody>
                {shown.map((r) => (
                  <tr key={r.ref} aria-label={r.label}>
                    <th scope="row">
                      <button type="button" className="pp-go" title="Show this line on the plan" onClick={() => go(r)}>
                        {r.key && <span className="key">{r.key}</span>}<span>{r.label}</span><Icon name="arrow-right" size={13} />
                      </button>
                    </th>
                    {CHECKS.map((c) => <Cell key={c.k} msgs={r.checks[c.k]} />)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="pp-foot">
          <span className="eyebrow">{hard ? 'A Task, a PIC and the epic are needed to create a sub-task.' : 'Force push goes past Not yet plan and Step order.'}</span>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Close</Button>
          {!hard && (
            <PushFlow epic={epic} v={v} name={name} body={{ refs, force: true }} label="Force push" icon="upload" appearance="secondary"
              title="Push anyway: Not yet plan and steps out of order are ignored" forced={forced} onDone={() => { close(); onDone?.(); }} />
          )}
        </div>
      </div>
    </div>
  );
}
