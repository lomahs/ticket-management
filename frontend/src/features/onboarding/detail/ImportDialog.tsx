import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton } from '../../../shared/ds';
import { dm, f1 } from '../../../shared/format/dates';
import { stepLabel } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useApplyImport, type ImportLine } from './api';

type Change = Schemas['ImportChange'];

const SLOT: Record<string, string> = { ba: 'BA', bal: 'BAL', jm: 'JM' };

/** What a line changes, in words: people by name, hours, dates as day/month. */
export function changeText(changes: Change[], name: (id?: string | null) => string): string[] {
  const out: string[] = [];
  for (const c of changes) {
    if (SLOT[c.field]) out.push(`${SLOT[c.field]} ${name(c.before) || '–'} → ${name(c.after)}`);
    else if (c.field === 'effort_h') out.push(`Effort ${f1(Number(c.before))}h → ${f1(Number(c.after))}h`);
  }
  const start = changes.find((c) => c.field === 'start');
  const end = changes.find((c) => c.field === 'end');
  if (start && end) {
    const was = start.before && end.before ? `${dm(start.before)}–${dm(end.before)}` : 'not computed';
    out.push(`Plan ${was} → ${dm(start.after ?? '')}–${dm(end.after ?? '')} (set by hand)`);
  }
  return out;
}

/** The review of an imported detail plan: what each line changes and what is wrong; the lines without errors are applied. */
export function ImportDialog({ epic, file, lines, name, close }: {
  epic: string; file: string; lines: ImportLine[]; name: (id?: string | null) => string; close: () => void;
}) {
  const toast = useToast();
  const apply = useApplyImport(epic);
  const changed = lines.filter((l) => l.errors.length === 0 && l.changes.length > 0);
  const wrong = lines.filter((l) => l.errors.length > 0);
  const same = lines.length - changed.length - wrong.length;
  const where = (l: ImportLine) => `Line ${l.line} · ${l.function} · ${l.step ? stepLabel(l.step) : 'Ticket'}`;
  // a line with errors may name no sub-task of the plan: its step is not known
  const at = (l: ImportLine) => `Line ${l.line}${l.function ? ` · ${l.function}` : ''}`;
  const submit = () => apply.mutate(changed, {
    onSuccess: () => { toast(`${changed.length} line${changed.length === 1 ? '' : 's'} applied · set by hand`); close(); },
    onError: (e) => toast(errorMessage(e), true),
  });
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Import the detail plan" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(720px,100%)', maxHeight: '85vh' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title" style={{ flex: 1 }}>Import the detail plan</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <span role="status" style={{ fontSize: 13 }}>
          {`${file}: ${changed.length} line${changed.length === 1 ? '' : 's'} to change · ${same} unchanged`}
          {wrong.length > 0 && ` · ${wrong.length} with errors, left out`}
        </span>
        <div style={{ overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 6, minHeight: 0 }}>
          {wrong.map((l) => (
            <div key={`e${l.line}`} style={{ fontSize: 12, color: 'var(--hl-red-fg)' }}>{`${at(l)}: ${l.errors.join(', ')}`}</div>
          ))}
          {changed.map((l) => (
            <div key={`c${l.line}`} style={{ display: 'flex', flexDirection: 'column', gap: 2, fontSize: 12, padding: '6px 0', borderTop: '1px solid var(--border-subtle)' }}>
              <span className="heading" style={{ fontWeight: 600 }}>{where(l)}</span>
              {changeText(l.changes, name).map((t) => <span key={t}>{t}</span>)}
            </div>
          ))}
          {lines.length > 0 && changed.length === 0 && wrong.length === 0 && (
            <span className="muted" style={{ fontSize: 13 }}>Every line has what the plan has: nothing to change.</span>
          )}
        </div>
        <span className="eyebrow">What changes is set by hand, as on the screen: Auto allocate keeps it, and the plan is computed again around it.</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={changed.length === 0 || apply.isPending} onClick={submit}>
            {apply.isPending ? 'Applying…' : `Apply ${changed.length} line${changed.length === 1 ? '' : 's'}`}
          </Button>
        </div>
      </div>
    </div>
  );
}
