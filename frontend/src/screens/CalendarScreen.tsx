import { useState, type ChangeEvent } from 'react';
import { api } from '../api/client';
import type { ImportRow, LeaveTypeKey } from '../api/types';
import { Avatar, PageHead } from '../components/bits';
import { Button, Icon, IconButton, Select, TextField } from '../ds';
import { add, dm, dow, monLabel, monthDays, shiftMon, WDN } from '../lib/dates';
import { HT, leaveBg, mix } from '../lib/domain';
import { useApp } from '../state/AppContext';

interface Dlg { editId?: number; who: string; type: LeaveTypeKey; from: string; to: string; note: string }
interface Imp { step: number; file: string | null; rows: ImportRow[]; busy?: boolean }

const download = (href: string) => {
  const a = document.createElement('a');
  a.href = href;
  document.body.appendChild(a);
  a.click();
  a.remove();
};

export function CalendarScreen() {
  const { snap, cal, write, showToast } = useApp();
  const { today, members, leaveTypes, config, leaves } = snap;
  const [mon, setMon] = useState(today.slice(0, 7));
  const [dlg, setDlg] = useState<Dlg | null>(null);
  const [imp, setImp] = useState<Imp | null>(null);

  const days = monthDays(mon).filter(cal.vis);
  const typeKeys = (Object.keys(leaveTypes) as (LeaveTypeKey | 'holiday')[]).filter((k): k is LeaveTypeKey => k !== 'holiday');

  const save = async () => {
    if (!dlg) return;
    const s = await write(api.saveLeave(dlg));
    if (s) { setDlg(null); showToast(`${members[dlg.who].name} · ${leaveTypes[dlg.type].label} saved`); }
  };
  const del = async () => {
    if (dlg?.editId == null) return;
    const s = await write(api.deleteLeave(dlg.editId));
    if (s) { setDlg(null); showToast('Leave removed'); }
  };
  const exportXls = () => {
    download(api.exportUrl(mon));
    showToast(`Exported ${leaves.filter((l) => l.date.startsWith(mon)).length} leave days · ${monLabel(mon)}`);
  };
  const ti = days.indexOf(today);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <PageHead eyebrow="Leave, WFH and holidays · feeds Planning, capacity and date suggestions" title="Team Calendar">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <Button appearance="secondary" size="sm" iconBefore="file-up" onClick={() => setImp({ step: 1, file: null, rows: [] })}>Import Excel</Button>
          <Button appearance="secondary" size="sm" iconBefore="file-down" onClick={exportXls}>Export Excel</Button>
          <Button size="sm" iconBefore="plus" onClick={() => setDlg({ who: Object.keys(members)[0], type: 'full', from: today, to: today, note: '' })}>Add leave</Button>
        </div>
      </PageHead>

      <section className="card card-clip">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
          <IconButton icon="chevron-left" label="Previous month" appearance="secondary" size="sm" onClick={() => setMon(shiftMon(mon, -1))} />
          <span style={{ fontWeight: 700, color: 'var(--text-heading)', minWidth: 120, textAlign: 'center' }}>{monLabel(mon)}</span>
          <IconButton icon="chevron-right" label="Next month" appearance="secondary" size="sm" onClick={() => setMon(shiftMon(mon, 1))} />
          <span className="spacer" />
          <div className="legend">
            {(Object.keys(leaveTypes) as (LeaveTypeKey | 'holiday')[]).map((k) => (
              <span key={k}>
                <span style={{ minWidth: 26, height: 18, padding: '0 3px', boxSizing: 'border-box', borderRadius: 4, background: leaveBg(snap, k), font: '700 9px/18px var(--font-text)', color: 'var(--text-heading)', textAlign: 'center' }}>{leaveTypes[k].ab}</span>
                {leaveTypes[k].label}
              </span>
            ))}
          </div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ width: '100%', minWidth: days.length * 28 + 230, position: 'relative' }}>
            {ti >= 0 && <div className="today-frame" style={{ left: `calc(170px + (100% - 230px) * ${(ti / days.length).toFixed(5)})`, width: `calc((100% - 230px) / ${days.length})` }} />}
            <div style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
              <div className="sticky-col" style={{ width: 170, padding: '8px 14px', fontSize: 11, color: 'var(--text-muted)' }}>Member</div>
              {days.map((s) => {
                const w = dow(s), t = s === today;
                return (
                  <div key={s} className="day-head" style={{ flex: '1 1 0', minWidth: 0, padding: '4px 0', background: t ? 'var(--surface-tint)' : 'transparent', color: t ? 'var(--text-heading)' : config.days.includes(w) ? 'var(--text-muted)' : 'var(--text-disabled)' }}>
                    <div>{WDN[w][0]}</div><div className="n" style={{ fontWeight: t ? 700 : 500 }}>{s.slice(8, 10)}</div>
                  </div>
                );
              })}
              <div style={{ width: 60, flex: 'none', boxSizing: 'border-box', padding: '8px 10px', borderLeft: '1px solid var(--border-subtle)', fontSize: 11, color: 'var(--text-muted)', textAlign: 'right' }}>Days off</div>
            </div>
            {Object.keys(members).map((id) => {
              const m = members[id];
              let nl = 0;
              const cells = days.map((s) => {
                const h = cal.isHoliday(s, id);
                const lv = cal.lv(id, s);
                let bg = 'transparent', v = '', tip = WDN[dow(s)] + ' ' + dm(s);
                if (h) { bg = HT('var(--border-strong)') + ',' + mix(leaveTypes.holiday.c, 22); v = 'H'; tip += ' · ' + h[1]; }
                else if (lv) { const t = leaveTypes[lv.type]; v = t.ab; tip += ' · ' + t.label + (lv.note ? ' · ' + lv.note : ''); bg = leaveBg(snap, lv.type); nl += t.full ? 1 : 0.5; }
                return { s, v, bg, tip, h: !!h, lv };
              });
              return (
                <div key={id} style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
                  <div className="sticky-col" style={{ width: 170, padding: '6px 14px', display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Avatar a={{ ini: m.name[0], color: m.color }} size={22} />
                    <div>
                      <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{m.name}</div>
                      <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>{m.role} · {m.c}</div>
                    </div>
                  </div>
                  {cells.map((c) => (
                    <button
                      key={c.s}
                      className="hv-dim2"
                      title={c.tip}
                      aria-label={`${m.name} ${c.tip}`}
                      disabled={c.h}
                      onClick={() => setDlg(c.lv ? { editId: c.lv.id, who: id, type: c.lv.type, from: c.s, to: c.s, note: c.lv.note } : { who: id, type: 'full', from: c.s, to: c.s, note: '' })}
                      style={{
                        flex: '1 1 0', minWidth: 0, boxSizing: 'border-box', height: 42, padding: 0, border: 0, borderLeft: '1px solid var(--border-subtle)',
                        background: c.bg, color: 'var(--text-heading)', font: '700 10px/1 var(--font-text)', cursor: c.h ? 'default' : 'pointer',
                      }}
                    >{c.v}</button>
                  ))}
                  <div style={{ width: 60, flex: 'none', boxSizing: 'border-box', padding: '0 10px', borderLeft: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', fontWeight: 700, color: 'var(--text-heading)' }}>{nl || '–'}</div>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {dlg && (
        <div className="scrim" onClick={() => setDlg(null)}>
          <div className="dialog" role="dialog" aria-modal="true" aria-label={dlg.editId ? 'Edit leave' : 'Add leave'} onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px,100%)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="dialog-title">{dlg.editId ? 'Edit leave' : 'Add leave'}</span>
              <IconButton icon="x" label="Close" size="sm" onClick={() => setDlg(null)} />
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <Select label="Member" options={Object.keys(members).map((id) => ({ value: id, label: `${members[id].name} (${members[id].role})` }))} value={dlg.who} onChange={(v) => setDlg({ ...dlg, who: v })} />
              <Select label="Type" options={typeKeys.map((k) => ({ value: k, label: leaveTypes[k].label }))} value={dlg.type} onChange={(v) => setDlg({ ...dlg, type: v as LeaveTypeKey })} />
              <TextField label="From" type="date" value={dlg.from} onChange={(v) => setDlg({ ...dlg, from: v })} />
              <TextField label="To" type="date" value={dlg.to} onChange={(v) => setDlg({ ...dlg, to: v })} />
            </div>
            <TextField label="Note" placeholder="Optional" value={dlg.note} onChange={(v) => setDlg({ ...dlg, note: v })} />
            <div className="eyebrow">Non-working days inside the range are skipped. All-day leave blocks the day in Planning and half days count as 50% capacity. Tentative leave is shown hatched.</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              {dlg.editId != null && <Button appearance="subtle" iconBefore="trash-2" onClick={del}>Delete</Button>}
              <span className="spacer" />
              <Button appearance="subtle" onClick={() => setDlg(null)}>Cancel</Button>
              <Button disabled={!(dlg.from && dlg.to && dlg.to >= dlg.from)} onClick={save}>Save</Button>
            </div>
          </div>
        </div>
      )}

      {imp && <ImportWizard imp={imp} setImp={setImp} onImported={(m) => m && setMon(m)} />}
    </div>
  );
}

function ImportWizard({ imp, setImp, onImported }: { imp: Imp; setImp: (i: Imp | null) => void; onImported: (month: string | null) => void }) {
  const { snap, setSnap, showToast, failed } = useApp();
  const ok = imp.rows.filter((r) => !r.err);
  const bad = imp.rows.filter((r) => r.err);
  const workDays = ok.reduce((a, r) => {
    let n = 0;
    for (let s = r.from; s <= r.to; s = add(s, 1)) if (snap.config.days.includes(dow(s))) n++;
    return a + n;
  }, 0);
  const close = () => setImp(null);
  const load = async (p: Promise<{ file: string; rows: ImportRow[] }>) => {
    setImp({ ...imp, busy: true });
    try {
      const r = await p;
      setImp({ ...imp, busy: false, file: r.file, rows: r.rows });
    } catch (e) {
      failed(e);
      setImp({ ...imp, busy: false });
    }
  };
  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) load(api.importPreview(f));
    e.target.value = '';
  };
  const confirm = async () => {
    try {
      const raw = ok.map((r) => [r.member, r.from, r.to, r.type, r.note]);
      const res = await api.importConfirm(imp.file ?? '', raw);
      setSnap(res.snapshot);
      onImported(res.month);
      close();
      showToast(`Imported ${res.imported} leave days from ${imp.file}`);
    } catch (e) {
      failed(e);
    }
  };
  const memberNames = Object.values(snap.members).map((m) => m.name).join(', ');
  const typeNames = (Object.keys(snap.leaveTypes) as string[]).filter((k) => k !== 'holiday').map((k) => snap.leaveTypes[k as LeaveTypeKey].label).join(', ');
  const steps = ['Download template', 'Upload file', 'Preview', 'Confirm'];
  const IMP_GRID = '40px 90px 96px 96px 110px minmax(0,1fr) minmax(0,1.6fr)';

  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Import leave from Excel" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(860px,100%)', maxHeight: 'calc(100vh - 48px)', overflow: 'auto', gap: 18 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title">Import leave from Excel</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,minmax(0,1fr))', gap: 8 }}>
          {steps.map((l, i) => {
            const done = i < imp.step - 1, cur = i === imp.step - 1;
            return (
              <div key={l} style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <span style={{
                  width: 24, height: 24, borderRadius: '50%', flex: 'none', font: '700 12px/24px var(--font-text)', textAlign: 'center',
                  background: done ? 'var(--surface-accent)' : cur ? 'var(--button-primary-bg)' : 'var(--surface-hover)', color: done || cur ? 'var(--button-primary-fg)' : 'var(--text-muted)',
                }}>{done ? '✓' : i + 1}</span>
                <span className="ellipsis" style={{ fontSize: 12, fontWeight: cur ? 700 : 500, color: cur ? 'var(--text-heading)' : 'var(--text-muted)' }}>{l}</span>
              </div>
            );
          })}
        </div>

        {imp.step === 1 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div className="heading">Fill one row per leave period. Use the member's display name and one of the leave types below.</div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 8, fontSize: 12 }}>
              {[['Member', memberNames], ['From / To', 'YYYY-MM-DD, weekends skipped'], ['Type', typeNames], ['Note', 'Optional']].map(([h, d]) => (
                <div key={h} style={{ padding: '10px 12px', borderRadius: 10, background: 'var(--surface-hover)' }}><b className="heading">{h}</b><div className="muted">{d}</div></div>
              ))}
            </div>
            <div><Button appearance="secondary" iconBefore="download" onClick={() => download(api.templateUrl)}>Download template</Button></div>
          </div>
        )}

        {imp.step === 2 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <label style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, padding: '28px 16px', border: '2px dashed var(--border-strong)', borderRadius: 14, background: 'var(--surface-hover)', cursor: 'pointer', textAlign: 'center' }}>
              <Icon name="file-spreadsheet" size={28} />
              <span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{imp.busy ? 'Reading file…' : 'Choose a .xlsx or .csv file'}</span>
              <span className="eyebrow">Columns: Member, From, To, Type, Note</span>
              <input type="file" accept=".xlsx,.csv" onChange={onFile} style={{ display: 'none' }} />
            </label>
            {imp.file ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border-subtle)' }}>
                <Icon name="file-check" size={16} />
                <b className="heading" style={{ flex: 1 }}>{imp.file}</b>
                <span className="eyebrow">{imp.rows.length} rows read</span>
              </div>
            ) : (
              <div><Button appearance="link" size="sm" onClick={() => load(api.importSample())}>Use a sample file</Button></div>
            )}
          </div>
        )}

        {imp.step === 3 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', fontSize: 12 }}>
              <span style={{ padding: '3px 10px', borderRadius: 99, background: 'var(--hl-ok)', color: 'var(--text-heading)', fontWeight: 600 }}>{ok.length} valid</span>
              {bad.length > 0 && <span style={{ padding: '3px 10px', borderRadius: 99, background: 'var(--hl-red)', color: 'var(--hl-red-fg)', fontWeight: 600 }}>{bad.length} with errors</span>}
              <span className="muted" style={{ alignSelf: 'center' }}>Rows with errors are skipped. Fix them in the file and re-upload to include them.</span>
            </div>
            <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 12, overflow: 'auto', fontSize: 12 }}>
              <div style={{ display: 'grid', gridTemplateColumns: IMP_GRID, gap: 10, padding: '8px 12px', background: 'var(--surface-hover)', fontWeight: 600, color: 'var(--text-muted)', minWidth: 680 }}>
                <span>Row</span><span>Member</span><span>From</span><span>To</span><span>Type</span><span>Note</span><span>Check</span>
              </div>
              {imp.rows.map((r) => (
                <div key={r.n} style={{ display: 'grid', gridTemplateColumns: IMP_GRID, gap: 10, padding: '8px 12px', borderTop: '1px solid var(--border-subtle)', background: r.err ? 'var(--hl-red)' : 'transparent', color: 'var(--text-heading)', minWidth: 680 }}>
                  <span className="mono muted">{r.n}</span><span>{r.member}</span><span>{r.from}</span><span>{r.to}</span><span>{r.type}</span>
                  <span className="muted">{r.note}</span>
                  <span style={{ color: r.err ? 'var(--hl-red-fg)' : 'var(--text-muted)', fontWeight: r.err ? 600 : 400 }}>{r.err || 'Valid'}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {imp.step === 4 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: 16, borderRadius: 12, background: 'var(--surface-hover)' }}>
            <b className="heading">Ready to import from {imp.file}</b>
            <span className="heading">{ok.length} rows · {workDays} working days{bad.length ? ` · ${bad.length} rows with errors will be skipped` : ''}</span>
            <span className="eyebrow">Planning, capacity and timesheet expectations update right after import.</span>
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {imp.step > 1 && <Button appearance="subtle" iconBefore="arrow-left" onClick={() => setImp({ ...imp, step: imp.step - 1 })}>Back</Button>}
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          {imp.step < 4
            ? <Button iconAfter="arrow-right" disabled={(imp.step === 2 && !imp.file) || (imp.step === 3 && !ok.length)} onClick={() => setImp({ ...imp, step: imp.step + 1 })}>Next</Button>
            : <Button iconBefore="check" onClick={confirm}>Import {ok.length} rows</Button>}
        </div>
      </div>
    </div>
  );
}
