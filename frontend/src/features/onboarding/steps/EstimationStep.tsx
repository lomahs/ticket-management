import { useRef } from 'react';
import { EstimationEditor, type SaveRef } from '../estimation/EstimationEditor';
import { StepShell } from '../components/StepShell';

/** Step 3: the estimation, each row under a Task of the epic (one created in step 2, or any other). */
export function EstimationStep({ epic }: { epic: string }) {
  const save: SaveRef = useRef(null);
  return (
    <StepShell epic={epic} step="estimation" title="Estimation"
      sub="Enter or import the functions and their effort, and pick the Task each one's sub-tasks go under."
      onSave={() => save.current?.() ?? Promise.resolve()}>
      <EstimationEditor epic={epic} save={save} />
    </StepShell>
  );
}
