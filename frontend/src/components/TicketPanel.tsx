import { api } from '../api/client';
import type { Status } from '../api/types';
import { Icon, IconButton, Select, TextField, type IconName } from '../ds';
import { add, dm, f1 } from '../lib/dates';
import { av, findTask, STAT, title } from '../lib/domain';
import { useApp } from '../state/AppContext';
import { Avatar } from './bits';

export function TicketPanel() {
  const { snap, ticket, openTicket, write } = useApp();
  const t = ticket ? findTask(snap, ticket) : null;
  if (!t) return null;
  const today = snap.today;

  const warns: { icon: IconName; label: string; bg: string; fg: string }[] = [];
  if (t.late) warns.push({ icon: 'alarm-clock', label: `Late by ${t.lateDays} days`, bg: 'var(--hl-red)', fg: 'var(--hl-red-fg)' });
  if (t.over) warns.push({ icon: 'trending-up', label: `Over estimate ${f1(t.logged - t.est)}h`, bg: 'var(--hl-amber)', fg: 'var(--hl-amber-fg)' });
  if (t.isFix && t.rounds >= snap.config.rounds) warns.push({ icon: 'refresh-cw', label: `${t.rounds} round`, bg: 'var(--hl-amber)', fg: 'var(--hl-amber-fg)' });

  // Review ping-pong reconstructed from the round count (Jira changelog stand-in).
  const rev = t.sk === 'fixj' || (t.title ?? '').includes('JM') ? 'tanaka' : 'minh';
  const ba = t.ba || t.assignee;
  const hist: { date: string; from: string; to: string; text: string; round: string }[] = [];
  let d = t.pe < today ? t.pe : today;
  for (let i = t.rounds; i >= 1; i--) {
    const atRev = i === t.rounds && t.assignee === rev;
    if (i < t.rounds || atRev) {
      hist.push({ date: dm(d), from: ba, to: rev, text: 'Fixed, sent back for review', round: 'R' + i });
      d = add(d, -1);
    }
    hist.push({ date: dm(d), from: rev, to: ba, text: 'Sent comments', round: 'R' + i });
    d = add(d, -2);
  }

  const tot = Math.max(t.est, t.logged) || 1;
  const inn = Math.min(t.logged, t.est);
  const ov = Math.max(0, t.logged - t.est);
  const members = snap.members;
  const upd = (k: 'status' | 'assignee' | 'ps' | 'pe', label: string, v: string, fmt: (x: string) => string) => {
    if (!v || v === t[k]) return;
    write(api.patchTicket(t.key, { [k]: v }), `${t.key} ${label}: ${fmt(t[k])} → ${fmt(v)}`);
  };

  return (
    <aside
      aria-label={`Ticket ${t.key}`}
      style={{
        position: 'fixed', top: 8, bottom: 8, right: 8, width: 440, maxWidth: 'calc(100vw - 16px)', zIndex: 30, borderRadius: 16, overflow: 'hidden',
        background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: 'var(--shadow-overlay)', display: 'flex', flexDirection: 'column',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px 10px 18px', borderBottom: '1px solid var(--border-subtle)' }}>
        <span className="key">{t.key}</span>
        <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{t.phase}</span>
        <span className="spacer" />
        <IconButton icon="x" label="Close" size="sm" onClick={() => openTicket(null)} />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 18 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <h2 style={{ margin: 0, font: '700 16px/1.35 var(--font-display)', color: 'var(--text-heading)' }}>{title(t)}</h2>
          {warns.length > 0 && (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {warns.map((w) => (
                <span key={w.label} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, font: '600 11px/22px var(--font-text)', padding: '0 8px', borderRadius: 6, background: w.bg, color: w.fg }}>
                  <Icon name={w.icon} size={12} />{w.label}
                </span>
              ))}
            </div>
          )}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <Select label="Status" options={(Object.keys(STAT) as Status[]).map((k) => ({ value: k, label: STAT[k].label }))} value={t.status}
            onChange={(v) => upd('status', 'status', v, (x) => STAT[x as Status].label)} />
          <Select label="Assignee" options={Object.keys(members).map((id) => ({ value: id, label: `${members[id].name} (${members[id].role})` }))} value={t.assignee}
            onChange={(v) => upd('assignee', 'assignee', v, (x) => members[x].name)} />
          <TextField label="Planned Start" type="date" value={t.ps} onChange={(v) => upd('ps', 'Planned Start', v, dm)} />
          <TextField label="Planned End" type="date" value={t.pe} onChange={(v) => upd('pe', 'Planned End', v, dm)} />
        </div>
        <div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <span className="title">Effort</span>
            <span className="spacer" />
            <span style={{ font: '600 13px/1 var(--font-mono)', color: t.over ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{f1(t.logged)} / {f1(t.est)}h</span>
          </div>
          <div style={{ display: 'flex', gap: 2, height: 8, borderRadius: 99, background: 'var(--border-subtle)', marginTop: 8, overflow: 'hidden' }}>
            <div style={{ height: 8, width: (inn / tot) * 100 + '%', background: 'var(--st-wip)' }} />
            <div style={{ height: 8, width: (ov / tot) * 100 + '%', background: 'var(--hl-red-fg)' }} />
          </div>
          <div style={{ display: 'flex', gap: 14, marginTop: 6, fontSize: 11, color: 'var(--text-muted)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: 'var(--st-wip)' }} />Within original estimate <b className="mono heading">{f1(inn)}h</b>
            </span>
            {ov > 0 && (
              <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                <span style={{ width: 8, height: 8, borderRadius: 2, background: 'var(--hl-red-fg)' }} />Additional <b className="mono" style={{ color: 'var(--hl-red-fg)' }}>+{f1(ov)}h</b>
              </span>
            )}
          </div>
        </div>
        {t.isFix && t.rounds > 0 && (
          <div>
            <div className="title" style={{ marginBottom: 8 }}>Assignee history · {t.rounds} round</div>
            {hist.map((hs, i) => (
              <div key={i} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--border-subtle)', fontSize: 12 }}>
                <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-muted)', width: 40 }}>{hs.date}</span>
                <Avatar a={av(snap, hs.from)} size={18} />
                <Icon name="arrow-right" size={12} />
                <Avatar a={av(snap, hs.to)} size={18} />
                <span style={{ flex: 1, color: 'var(--text-body)' }}>{hs.text}</span>
                <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>{hs.round}</span>
              </div>
            ))}
          </div>
        )}
        <div>
          <div className="title" style={{ marginBottom: 8 }}>Worklog</div>
          {t.worklogs.map((wl, i) => (
            <div key={i} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--border-subtle)', fontSize: 12 }}>
              <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-muted)', width: 40 }}>{dm(wl.date)}</span>
              <Avatar a={av(snap, wl.who)} size={18} />
              <span style={{ flex: 1, color: 'var(--text-body)' }}>{wl.note}</span>
              <span style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--text-heading)' }}>{f1(wl.h)}h</span>
            </div>
          ))}
          {!t.worklogs.length && <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>No worklogs yet.</div>}
        </div>
      </div>
    </aside>
  );
}
