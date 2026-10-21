import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useMe } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { useMerge } from '../api';

/** Merges another member into this one: the other one is deleted after its anken, leave and Jira history move here. */
export function MergeDialog({ keep, users, close }: { keep: Schemas['UserRow']; users: Schemas['UserRow'][]; close: () => void }) {
  const toast = useToast();
  const merge = useMerge();
  const meId = useMe().data?.member_id;
  // The signed-in member cannot be merged away.
  const others = users.filter((u) => u.member.id !== keep.member.id && u.member.id !== meId);
  const [removeId, setRemoveId] = useState(others[0]?.member.id ?? '');
  const other = others.find((u) => u.member.id === removeId)?.member;
  const submit = () => merge.mutate({ keep_id: keep.member.id, remove_id: removeId }, {
    onSuccess: close,
    onError: (e) => toast(errorMessage(e), true),
  });
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Merge members" onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title" style={{ flex: 1 }}>Merge {other?.name ?? '…'} into {keep.member.name}</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <Select label="Member to merge" value={removeId} options={others.map((u) => ({ value: u.member.id, label: `${u.member.name} · ${u.member.email || u.member.id}` }))} onChange={setRemoveId} />
        {other && <div style={{ fontSize: 13 }}>This moves {other.name}'s anken, leave and Jira history to {keep.member.name} and deletes {other.name}.</div>}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={merge.isPending || !other} onClick={submit}>Merge</Button>
        </div>
      </div>
    </div>
  );
}
