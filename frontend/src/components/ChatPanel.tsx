import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import { Icon, IconButton, TextField } from '../ds';
import { findTask, short, STAT } from '../lib/domain';
import { useApp } from '../state/AppContext';

const SUGG = [
  'Which functions are late?', 'Which review tickets is Minh holding?', 'Which tickets ping-pong for many rounds?',
  'Which tickets have waited long for JM?', 'How many hours has Bình logged this week?',
];

interface Msg { role: 'me' | 'bot'; text: string; chips: string[] }

export function ChatPanel({ right, onClose }: { right: number; onClose: () => void }) {
  const { snap, openTicket, failed } = useApp();
  const [msgs, setMsgs] = useState<Msg[]>([
    { role: 'bot', text: 'Hi. I answer questions about tasks in the Payment flow revision anken. Pick a suggestion or type your own question.', chips: [] },
  ]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }); }, [msgs]);

  const ask = async (q: string) => {
    if (!q.trim() || busy) return;
    setInput('');
    setMsgs((m) => [...m, { role: 'me', text: q, chips: [] }]);
    setBusy(true);
    try {
      const a = await api.chat(q);
      setMsgs((m) => [...m, { role: 'bot', ...a }]);
    } catch (e) {
      failed(e);
    } finally {
      setBusy(false);
    }
  };
  const send = (e?: FormEvent) => { e?.preventDefault(); ask(input); };

  return (
    <aside
      aria-label="Task Q&A"
      style={{
        position: 'fixed', bottom: 84, right, width: 380, maxWidth: 'calc(100vw - 40px)', height: 'min(600px, calc(100vh - 108px))', zIndex: 41,
        borderRadius: 18, overflow: 'hidden', background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: 'var(--shadow-overlay)',
        display: 'flex', flexDirection: 'column',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px 10px 18px', borderBottom: '1px solid var(--border-subtle)' }}>
        <Icon name="message-square" size={16} />
        <span className="title" style={{ flex: 1 }}>Task Q&amp;A</span>
        <IconButton icon="x" label="Close" size="sm" onClick={onClose} />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        {msgs.map((mg, i) => {
          const isMe = mg.role === 'me';
          return (
            <div key={i} style={{ display: 'flex', justifyContent: isMe ? 'flex-end' : 'flex-start' }}>
              <div style={{
                maxWidth: '88%', padding: '10px 14px', borderRadius: 16, display: 'flex', flexDirection: 'column', gap: 8,
                background: isMe ? 'var(--button-primary-bg)' : 'var(--surface-hover)', color: isMe ? 'var(--button-primary-fg)' : 'var(--text-body)',
              }}>
                <div style={{ whiteSpace: 'pre-line' }}>{mg.text}</div>
                {mg.chips.length > 0 && (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {mg.chips.map((k) => {
                      const c = findTask(snap, k);
                      return (
                        <button key={k} className="hv-bd" onClick={() => openTicket(k)} style={{
                          display: 'inline-flex', alignItems: 'center', gap: 6, height: 26, padding: '0 10px', border: '1px solid var(--border-strong)',
                          borderRadius: 999, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 11px/1 var(--font-text)', cursor: 'pointer',
                        }}>
                          <span className="dot" style={{ width: 7, height: 7, background: `var(--st-${c ? STAT[c.status].v : 'nys'})` }} />
                          <span className="mono" style={{ fontWeight: 600 }}>{k}</span>
                          <span className="muted">{c ? short(c) : ''}</span>
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>
          );
        })}
        <div ref={end} />
      </div>
      <div style={{ padding: '10px 16px', borderTop: '1px solid var(--border-subtle)', display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {SUGG.map((q) => (
            <button key={q} onClick={() => ask(q)} style={{
              height: 26, padding: '0 10px', border: '1px solid var(--border-subtle)', borderRadius: 13, background: 'var(--surface-tint)',
              color: 'var(--text-heading)', font: '500 11px/1 var(--font-text)', cursor: 'pointer',
            }}>{q}</button>
          ))}
        </div>
        <form onSubmit={send} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <div style={{ flex: 1 }}><TextField placeholder="Ask about tasks, reviews, effort…" value={input} onChange={setInput} aria-label="Question" /></div>
          <IconButton icon="send" label="Send" type="submit" disabled={busy} />
        </form>
      </div>
    </aside>
  );
}
