import { useVisibleAnken } from '../api/queries';
import { Select } from '../ds';
import { ankenLabel, useAnkenScope } from '../state/anken';

/** A per-anken screen (Analysis, Burndown, Daily Overview): under "All my anken" it asks for one, which then becomes the switcher's. */
export function PickAnken() {
  const { setAnken } = useAnkenScope();
  const visible = useVisibleAnken();
  return (
    <div className="card" style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 12, alignItems: 'flex-start' }}>
      <div className="title">Pick an anken to see this view.</div>
      <Select
        aria-label="Pick an anken"
        style={{ width: 280 }}
        value=""
        options={[{ value: '', label: 'Choose…' }, ...visible.map((a) => ({ value: a.key, label: ankenLabel(a) }))]}
        onChange={(k) => k && setAnken(k)}
      />
    </div>
  );
}
