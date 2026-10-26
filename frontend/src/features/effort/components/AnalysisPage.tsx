import { useQuery } from '@tanstack/react-query';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import { f1, hrs } from '../../../shared/format/dates';
import { useAnkenScope } from '../../../shared/state/anken';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { analysisQuery } from '../api';
import { EffortHeader } from './EffortHeader';
import { PickAnken } from '../../../shared/ui/PickAnken';

const RW_GRID = 'minmax(150px,1.2fr) 80px 90px 110px minmax(180px,2fr) 90px 90px';
const pct = (x: number | null | undefined) => (x == null ? '–' : Math.round(x * 100) + '%');

export function AnalysisPage() {
  const { anken } = useAnkenScope();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <EffortHeader tab="analysis" />
      {anken ? <AnalysisOf anken={anken} /> : <PickAnken />}
    </div>
  );
}

function AnalysisOf({ anken }: { anken: string }) {
  const q = useQuery(analysisQuery(anken));
  return q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <Analysis a={q.data} />;
}

function Bars({ list }: { list: Schemas['EffortBar'][] }) {
  const mx = Math.max(1, ...list.flatMap((x) => [x.estimate_h, x.logged_h]));
  return (
    <>
      {list.map((b) => {
        const r = b.estimate_h ? b.logged_h / b.estimate_h : 0;
        return (
          <div key={b.label} style={{ display: 'grid', gridTemplateColumns: '130px minmax(0,1fr) 120px', gap: 10, alignItems: 'center' }}>
            <span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{b.label}</span>
            <div style={{ position: 'relative', height: 18 }}>
              <div style={{ position: 'absolute', left: 0, top: 0, height: 18, width: (b.estimate_h / mx) * 100 + '%', borderRadius: 5, background: 'var(--surface-tint)' }} />
              <div style={{ position: 'absolute', left: 0, top: 5, height: 8, width: (b.logged_h / mx) * 100 + '%', borderRadius: 4, background: b.logged_h > b.estimate_h ? 'var(--hl-red-fg)' : 'var(--surface-accent)' }} />
            </div>
            <span style={{ textAlign: 'right', fontSize: 12, color: 'var(--text-heading)' }}>
              {f1(b.logged_h)} / {hrs(b.estimate_h)} <b style={{ color: r > 1 ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{Math.round(r * 100)}%</b>
            </span>
          </div>
        );
      })}
    </>
  );
}

function Analysis({ a }: { a: Schemas['EffortAnalysis'] }) {
  const members = useMemberMap();
  const k = a.kpis;
  const kpis: [string, string, string][] = [
    ['Logged / estimate', k.estimate_h ? pct(k.logged_h / k.estimate_h) : '–', `${f1(k.logged_h)} / ${hrs(k.estimate_h)} on started sub-tasks`],
    ['Team rework rate', pct(k.rework_rate), 'Fix comment hours ÷ Create hours'],
    ['Avg review rounds', k.avg_rounds == null ? '–' : k.avg_rounds.toFixed(1), `${k.fix_tickets} Fix comment tickets`],
    ['Over estimate', String(k.over_subtasks), 'sub-tasks'],
  ];
  const mxRate = Math.max(0.01, ...a.rework.map((r) => r.rate));
  return (
    <>
      <div className="card" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))' }}>
        {kpis.map(([label, value, sub], i) => (
          <div key={label} style={{ padding: '14px 18px', borderLeft: i ? '1px solid var(--border-subtle)' : 0 }}>
            <div className="eyebrow">{label}</div>
            <div style={{ font: '700 24px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>{value}</div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{sub}</div>
          </div>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,380px),1fr))', gap: 14 }}>
        <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div className="title">Estimate vs logged · by phase</div>
            <div className="eyebrow">Started sub-tasks only · {a.title}</div>
          </div>
          <Bars list={a.by_phase} />
          <div style={{ display: 'flex', gap: 14, fontSize: 11, color: 'var(--text-muted)', flexWrap: 'wrap' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span style={{ width: 16, height: 10, borderRadius: 3, background: 'var(--surface-tint)' }} />Estimate</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span style={{ width: 16, height: 6, borderRadius: 3, background: 'var(--surface-accent)' }} />Logged</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span style={{ width: 16, height: 6, borderRadius: 3, background: 'var(--hl-red-fg)' }} />Logged over estimate</span>
          </div>
        </section>
        <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div className="title">Estimate vs logged · by step</div>
            <div className="eyebrow">Started sub-tasks only · function steps</div>
          </div>
          <Bars list={a.by_step} />
        </section>
      </div>
      <section className="card card-clip">
        <div style={{ padding: '14px 18px' }}>
          <div className="title">Rework by member</div>
          <div className="eyebrow">Rework rate = hours on Fix comment BAL/JM ÷ hours on Create</div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 680 }}>
            <div style={{ display: 'grid', gridTemplateColumns: RW_GRID, gap: 12, padding: '8px 18px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
              <span>BA</span><span style={{ textAlign: 'right' }}>Functions</span><span style={{ textAlign: 'right' }}>Create</span><span style={{ textAlign: 'right' }}>Fix comment</span>
              <span>Rework rate</span><span style={{ textAlign: 'right' }}>Avg rounds</span><span style={{ textAlign: 'right' }}>Max rounds</span>
            </div>
            {a.rework.map((r) => {
              const m = av(members[r.member_id], r.member_id);
              const hot = r.rate > 0.35;
              return (
                <div key={r.member_id} style={{ display: 'grid', gridTemplateColumns: RW_GRID, gap: 12, alignItems: 'center', padding: '10px 18px', borderTop: '1px solid var(--border-subtle)' }}>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Avatar a={m} size={22} /><b className="heading">{m.name}</b></span>
                  <span className="heading" style={{ textAlign: 'right' }}>{r.functions}</span>
                  <span className="heading" style={{ textAlign: 'right' }}>{hrs(r.create_h)}</span>
                  <span className="heading" style={{ textAlign: 'right' }}>{hrs(r.fix_h)}</span>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ flex: 1, height: 6, borderRadius: 99, background: 'var(--border-subtle)' }}>
                      <span style={{ display: 'block', height: 6, borderRadius: 99, width: (r.rate / mxRate) * 100 + '%', background: hot ? 'var(--hl-red-fg)' : 'var(--surface-accent)' }} />
                    </span>
                    <b style={{ width: 40, textAlign: 'right', color: hot ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{pct(r.rate)}</b>
                  </span>
                  <span style={{ textAlign: 'right', fontWeight: 600, color: 'var(--text-heading)' }}>{r.avg_rounds ? r.avg_rounds.toFixed(1) : '–'}</span>
                  <span style={{ textAlign: 'right', fontWeight: 600, color: r.max_rounds >= 3 ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{r.max_rounds || '–'}</span>
                </div>
              );
            })}
            {!a.rework.length && <div className="empty-row">No functions in this anken yet.</div>}
          </div>
        </div>
      </section>
    </>
  );
}
