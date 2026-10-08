import { useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { Alert } from '@/components/ui/Alert';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';

const MAX_LENGTH = 60;

const inputClass =
  'w-full rounded-xl border border-slate-200 bg-white shadow-sm px-3 py-2 text-sm text-slate-900 ' +
  'placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  'dark:border-gh-border dark:bg-surface-dark dark:text-gh-fg';

export function ApplicationGroupModal({
  applicationId,
  applicationName,
  currentGroup,
  existingGroups,
  onClose,
  onSaved,
}: {
  applicationId: number;
  applicationName: string;
  currentGroup: string | null;
  existingGroups: string[];
  onClose: () => void;
  onSaved: () => void;
}): JSX.Element {
  const [group, setGroup] = useState(currentGroup ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save(value: string) {
    setError(null);
    setBusy(true);
    try {
      await api.put(API.APPLICATIONS.GROUP(applicationId), { groupName: value.trim() || undefined });
      onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'The group could not be saved.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal onClose={onClose} widthClass="max-w-md">
      <div className="border-b border-slate-200 px-6 py-4 pr-14 dark:border-gh-border">
        <h2 className="text-lg font-semibold text-slate-900 dark:text-white">Group</h2>
        <p className="mt-1 text-xs text-slate-500 dark:text-gh-muted">{applicationName}</p>
      </div>

      <div className="space-y-4 p-6">
        {error && <Alert>{error}</Alert>}

        <p className="text-sm text-slate-600 dark:text-gh-fgSoft">
          Applications in the same group are shown together on the Dashboard, with a "healthy" count for the
          group. Applications without a group are listed under "Other".
        </p>

        <div>
          <label className="mb-1 block text-sm font-medium text-slate-700 dark:text-gh-fgSoft" htmlFor="app-group">
            Group name
          </label>
          <input
            id="app-group"
            className={inputClass}
            list="app-group-suggestions"
            value={group}
            maxLength={MAX_LENGTH}
            onChange={(e) => setGroup(e.target.value)}
            placeholder="e.g. Onboarding"
            autoComplete="off"
          />
          <datalist id="app-group-suggestions">
            {existingGroups.map((g) => (
              <option key={g} value={g} />
            ))}
          </datalist>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 pt-4 dark:border-gh-border">
          <div>
            {currentGroup && (
              <Button variant="secondary" disabled={busy} onClick={() => void save('')}>
                Remove from group
              </Button>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" disabled={busy} onClick={onClose}>
              Cancel
            </Button>
            <Button loading={busy} disabled={!group.trim() || group.trim() === (currentGroup ?? '')} onClick={() => void save(group)}>
              Save
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
