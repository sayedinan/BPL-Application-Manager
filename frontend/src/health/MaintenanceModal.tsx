import { useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { Alert } from '@/components/ui/Alert';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';

const DURATIONS = [
  { minutes: 30, label: '30 minutes' },
  { minutes: 60, label: '1 hour' },
  { minutes: 120, label: '2 hours' },
  { minutes: 240, label: '4 hours' },
  { minutes: 480, label: '8 hours' },
  { minutes: 1440, label: '24 hours' },
];

const inputClass =
  'w-full rounded-xl border border-slate-200 bg-white shadow-sm px-3 py-2 text-sm text-slate-900 ' +
  'placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  'dark:border-gh-border dark:bg-surface-dark dark:text-gh-fg';

export function MaintenanceModal({
  applicationId,
  applicationName,
  activeUntil,
  onClose,
}: {
  applicationId: number;
  applicationName: string;
  /** When the current window ends, or null when none is open. */
  activeUntil: string | null;
  onClose: () => void;
}): JSX.Element {
  const [minutes, setMinutes] = useState(60);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState<'start' | 'end' | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run(action: 'start' | 'end') {
    setError(null);
    setBusy(action);
    try {
      if (action === 'start') {
        await api.put(API.APPLICATIONS.MAINTENANCE(applicationId), { minutes, note: note.trim() || undefined });
      } else {
        await api.delete(API.APPLICATIONS.MAINTENANCE(applicationId));
      }
      onClose();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'The change could not be saved.');
    } finally {
      setBusy(null);
    }
  }

  return (
    <Modal onClose={onClose} widthClass="max-w-md">
      <div className="border-b border-slate-200 px-6 py-4 pr-14 dark:border-gh-border">
        <h2 className="text-lg font-semibold text-slate-900 dark:text-white">Maintenance</h2>
        <p className="mt-1 text-xs text-slate-500 dark:text-gh-muted">{applicationName}</p>
      </div>

      <div className="space-y-4 p-6">
        {error && <Alert>{error}</Alert>}

        <p className="text-sm text-slate-600 dark:text-gh-fgSoft">
          While maintenance is on, this application's offline and online alerts (email and SMS) are held back.
          Changes are still recorded in the audit log, and if it is still offline when maintenance ends, the alert is sent then.
        </p>

        {activeUntil && (
          <Alert tone="warning">Maintenance is on until {new Date(activeUntil).toLocaleString()}.</Alert>
        )}

        <div>
          <label className="mb-1 block text-sm font-medium text-slate-700 dark:text-gh-fgSoft" htmlFor="maintenance-minutes">
            {activeUntil ? 'Change to end in' : 'Hold alerts for'}
          </label>
          <select
            id="maintenance-minutes"
            className={inputClass}
            value={minutes}
            onChange={(e) => setMinutes(Number(e.target.value))}
          >
            {DURATIONS.map((d) => (
              <option key={d.minutes} value={d.minutes}>
                {d.label}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-slate-700 dark:text-gh-fgSoft" htmlFor="maintenance-note">
            Note (optional)
          </label>
          <input
            id="maintenance-note"
            className={inputClass}
            value={note}
            maxLength={200}
            onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. Monthly update"
            autoComplete="off"
          />
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 pt-4 dark:border-gh-border">
          <div>
            {activeUntil && (
              <Button
                variant="secondary"
                loading={busy === 'end'}
                disabled={busy !== null && busy !== 'end'}
                onClick={() => void run('end')}
              >
                End maintenance now
              </Button>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" disabled={busy !== null} onClick={onClose}>
              Cancel
            </Button>
            <Button loading={busy === 'start'} disabled={busy !== null && busy !== 'start'} onClick={() => void run('start')}>
              {activeUntil ? 'Update' : 'Start maintenance'}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
