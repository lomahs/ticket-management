import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { Loading, QueryError } from '../../../shared/ui/states';
import { HEAD, ROW, StructureEditor, type Template } from '../../../shared/ui/StructureEditor';
import { structurePreviewQuery, structureQuery, useImportStructure, useSaveStructure } from '../api';
import { AnkenPicker, useSettingsTarget } from './AnkenPicker';
import { Section, useDebounced } from './common';

const SEEN_GRID = '110px minmax(220px,2fr) minmax(120px,1fr) minmax(160px,1.4fr)';

export function StructureTab() {
  const t = useSettingsTarget();
  const anken = t.status === 'ready' ? t.anken : null;
  const [rev, setRev] = useState(0);
  const q = useQuery({ ...structureQuery(anken), enabled: t.status === 'ready' });
  if (t.status === 'pending') return <Loading />;
  if (t.status === 'error') return <QueryError error={t.error} />;
  return (
    <>
      <AnkenPicker anken={anken} untracked={t.untracked} onCopied={() => setRev((r) => r + 1)} />
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <StructureForm key={`${anken ?? ''}:${rev}`} anken={anken} v={q.data} />}
    </>
  );
}

function StructureForm({ v, anken }: { v: Schemas['StructureView']; anken: string | null }) {
  const save = useSaveStructure(anken);
  const importFile = useImportStructure(anken);
  return (
    <StructureEditor v={v} onSave={save.mutateAsync} onImport={importFile.mutateAsync} saving={save.isPending} importing={importFile.isPending}
      saved="Phases & tasks saved · synced" extra={(form) => <Seen anken={anken} v={v} form={form} />} />
  );
}

/** The Tasks of the tracked Epics, classified with the unsaved form. */
function Seen({ anken, v, form }: { anken: string | null; v: Schemas['StructureView']; form: { phases: string[]; tasks: Template[] } }) {
  const body = useDebounced(form, 250);
  const seen = useQuery(structurePreviewQuery(anken, body)).data?.seen ?? v.seen;
  const unmatched = seen.filter((s) => s.template == null).length;
  return (
    <Section pad={false} title="Tasks in Jira" sub={`Tasks of the tracked Epics, classified with the settings above before you save. ${unmatched} without a matching task: their phase comes from a leading [Phase] in the Summary.`}>
      <div style={{ overflowX: 'auto' }}>
        <div style={{ minWidth: 640 }}>
          <div style={{ ...HEAD, gridTemplateColumns: SEEN_GRID }}><span>Key</span><span>Summary</span><span>Phase</span><span>Matched task</span></div>
          {seen.map((s) => (
            <div key={s.key} style={{ ...ROW, gridTemplateColumns: SEEN_GRID, fontSize: 12 }}>
              <span className="mono">{s.key}</span>
              <span className="heading">{s.summary}</span>
              <span>{s.phase || '—'}</span>
              {s.template == null
                ? <span style={{ fontWeight: 600, color: 'var(--hl-amber-fg)' }}>No match</span>
                : <span>{form.tasks[s.template]?.match}</span>}
            </div>
          ))}
        </div>
      </div>
    </Section>
  );
}
