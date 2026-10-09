import { useNavigate, useParams } from '@tanstack/react-router';
import type { ChangeEvent } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useMembers } from '../../../shared/api/queries';
import { Button, Icon, IconButton } from '../../../shared/ds';
import { LEAVE, LEAVE_KEYS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { TEMPLATE_URL, useImportConfirm, useImportPreview } from '../api';
import { useImportState } from './CalendarPage';

const STEPS = ['Download template', 'Upload file', 'Preview', 'Confirm'];
const GRID = '40px 90px 96px 96px 110px minmax(0,1fr) minmax(0,1.6fr)';

/** /calendar/import/$step — the step is in the URL; the parsed file lives in the calendar page while the wizard is open. */
export function ImportWizardRoute() {
  const { step: raw } = useParams({ strict: false }) as { step: string };
  const navigate = useNavigate();
  const toast = useToast();
  const [state, setState] = useImportState();
  const preview = useImportPreview();
  const confirm = useImportConfirm();
  const members = useMembers().data ?? [];
  const requested = Math.min(4, Math.max(1, Number(raw) || 1));
  const step = requested > 2 && !state ? 2 : requested; // preview/confirm need a file
  const go = (n: number) => navigate({ to: '/calendar/import/$step', params: { step: String(n) }, search: (p) => p });
  const close = () => { setState(null); navigate({ to: '/calendar', search: (p) => ({ month: (p as { month?: string }).month }) }); };
  const ok = state?.rows.filter((r) => !r.error) ?? [];
  const bad = state?.rows.filter((r) => r.error) ?? [];
  const load = (file: File | 'sample') => preview.mutate(file, {
    onSuccess: (r) => setState({ file: r.file, rows: r.rows, working_days: r.working_days }),
    onError: (e) => toast(errorMessage(e), true),
  });
  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) load(f);
    e.target.value = '';
  };
  const doImport = () => confirm.mutate(ok.map((r) => [r.member, r.date_from, r.date_to, r.type_text, r.note]), {
    onSuccess: (res) => {
      toast(`Imported ${res.imported} leave days from ${state?.file}`);
      setState(null);
      navigate({ to: '/calendar', search: { month: res.month ?? undefined } });
    },
    onError: (e) => toast(errorMessage(e), true),
  });

  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Import leave from Excel" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(860px,100%)', maxHeight: 'calc(100vh - 48px)', overflow: 'auto', gap: 18 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title">Import leave from Excel</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <ol style={{ display: 'grid', gridTemplateColumns: 'repeat(4,minmax(0,1fr))', gap: 8, listStyle: 'none', padding: 0, margin: 0 }}>
          {STEPS.map((l, i) => {
            const done = i < step - 1, cur = i === step - 1;
            return (
              <li key={l} aria-current={cur ? 'step' : undefined} style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <span style={{
                  width: 24, height: 24, borderRadius: '50%', flex: 'none', font: '700 12px/24px var(--font-text)', textAlign: 'center',
                  background: done ? 'var(--surface-accent)' : cur ? 'var(--button-primary-bg)' : 'var(--surface-hover)', color: done || cur ? 'var(--button-primary-fg)' : 'var(--text-muted)',
                }}>{done ? '✓' : i + 1}</span>
                <span className="ellipsis" style={{ fontSize: 12, fontWeight: cur ? 700 : 500, color: cur ? 'var(--text-heading)' : 'var(--text-muted)' }}>{l}</span>
              </li>
            );
          })}
        </ol>

        {step === 1 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div className="heading">Fill one row per leave period. Use the member's display name and one of the leave types below.</div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 8, fontSize: 12 }}>
              {[['Member', members.map((m) => m.name).join(', ')], ['From / To', 'YYYY-MM-DD, weekends skipped'],
                ['Type', LEAVE_KEYS.map((k) => LEAVE[k].label).join(', ')], ['Note', 'Optional']].map(([h, d]) => (
                <div key={h} style={{ padding: '10px 12px', borderRadius: 10, background: 'var(--surface-hover)' }}><b className="heading">{h}</b><div className="muted">{d}</div></div>
              ))}
            </div>
            <div><a href={TEMPLATE_URL} download style={{ textDecoration: 'none' }}><Button appearance="secondary" iconBefore="download" tabIndex={-1}>Download template</Button></a></div>
          </div>
        )}

        {step === 2 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <label style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, padding: '28px 16px', border: '2px dashed var(--border-strong)', borderRadius: 14, background: 'var(--surface-hover)', cursor: 'pointer', textAlign: 'center' }}>
              <Icon name="file-spreadsheet" size={28} />
              <span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{preview.isPending ? 'Reading file…' : 'Choose a .xlsx or .csv file'}</span>
              <span className="eyebrow">Columns: Member, From, To, Type, Note</span>
              <input type="file" accept=".xlsx,.csv" onChange={onFile} style={{ display: 'none' }} aria-label="Leave file" />
            </label>
            {state ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border-subtle)' }}>
                <Icon name="file-check" size={16} />
                <b className="heading" style={{ flex: 1 }}>{state.file}</b>
                <span className="eyebrow">{state.rows.length} rows read</span>
              </div>
            ) : (
              <div><Button appearance="link" size="sm" onClick={() => load('sample')}>Use a sample file</Button></div>
            )}
          </div>
        )}

        {step === 3 && state && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', fontSize: 12 }}>
              <span style={{ padding: '3px 10px', borderRadius: 99, background: 'var(--hl-ok)', color: 'var(--text-heading)', fontWeight: 600 }}>{ok.length} valid</span>
              {bad.length > 0 && <span style={{ padding: '3px 10px', borderRadius: 99, background: 'var(--hl-red)', color: 'var(--hl-red-fg)', fontWeight: 600 }}>{bad.length} with errors</span>}
              <span className="muted" style={{ alignSelf: 'center' }}>Rows with errors are skipped. Fix them in the file and re-upload to include them.</span>
            </div>
            <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 12, overflow: 'auto', fontSize: 12 }}>
              <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 10, padding: '8px 12px', background: 'var(--surface-hover)', fontWeight: 600, color: 'var(--text-muted)', minWidth: 680 }}>
                <span>Row</span><span>Member</span><span>From</span><span>To</span><span>Type</span><span>Note</span><span>Check</span>
              </div>
              {state.rows.map((r) => (
                <div key={r.row} style={{ display: 'grid', gridTemplateColumns: GRID, gap: 10, padding: '8px 12px', borderTop: '1px solid var(--border-subtle)', background: r.error ? 'var(--hl-red)' : 'transparent', color: 'var(--text-heading)', minWidth: 680 }}>
                  <span className="mono muted">{r.row}</span><span>{r.member}</span><span>{r.date_from}</span><span>{r.date_to}</span><span>{r.type_text}</span>
                  <span className="muted">{r.note}</span>
                  <span style={{ color: r.error ? 'var(--hl-red-fg)' : 'var(--text-muted)', fontWeight: r.error ? 600 : 400 }}>{r.error || 'Valid'}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {step === 4 && state && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: 16, borderRadius: 12, background: 'var(--surface-hover)' }}>
            <b className="heading">Ready to import from {state.file}</b>
            <span className="heading">{ok.length} rows · {state.working_days} working days{bad.length ? ` · ${bad.length} rows with errors will be skipped` : ''}</span>
            <span className="eyebrow">Planning, capacity and timesheet expectations update right after import.</span>
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {step > 1 && <Button appearance="subtle" iconBefore="arrow-left" onClick={() => go(step - 1)}>Back</Button>}
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          {step < 4
            ? <Button iconAfter="arrow-right" disabled={(step === 2 && !state) || (step === 3 && !ok.length)} onClick={() => go(step + 1)}>Next</Button>
            : <Button iconBefore="check" disabled={confirm.isPending || !ok.length} onClick={doImport}>Import {ok.length} rows</Button>}
        </div>
      </div>
    </div>
  );
}
