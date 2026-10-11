import { useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { Alert } from '@/components/ui/Alert';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';

const inputClass =
  'w-full rounded-xl border border-slate-200 bg-white shadow-sm px-3 py-2 text-sm text-slate-900 ' +
  'placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  'dark:border-gh-border dark:bg-surface-dark dark:text-gh-fg';

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** Value for <input type="datetime-local">: the browser's local time, no timezone. */
function toLocalInput(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function MaintenanceModal({
  applicationId,
  applicationName,
  current,
  onClose,
}: {
  applicationId: number;
  applicationName: string;
  /** The open or scheduled window, or null when there is none. */
  current: { startsAt: string | null; endsAt: string; active: boolean } | null;
  onClose: () => void;
}): JSX.Element {
  const [from, setFrom] = useState(() => toLocalInput(current?.startsAt ? new Date(current.startsAt) : new Date()));
  const [to, setTo] = useState(() => toLocalInput(current ? new Date(current.endsAt) : new Date(Date.now() + 3600_000)));
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState<'start' | 'end' | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run(action: 'start' | 'end') {
    setError(null);
    if (action === 'start') {
      const end = new Date(to);
      const start = current?.active ? new Date() : new Date(from);
      if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
        setError('Choose both a start and an end time.');
        return;
      }
      if (end.getTime() <= start.getTime()) {
        setError('The end must be after the start.');
        return;
      }
    }
    setBusy(action);
    try {
      if (action === 'start') {
        await api.put(API.APPLICATIONS.MAINTENANCE(applicationId), {
          // An open window keeps running; only its end (and note) change.
          startsAt: current?.active ? undefined : new Date(from).toISOString(),
          endsAt: new Date(to).toISOString(),
          note: note.trim() || undefined,
        });
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
          Choose when maintenance starts and ends. Between those times this application's offline and online alerts
          (email and SMS) are held back. Changes are still recorded in the audit log, and if it is still offline when
          maintenance ends, the alert is sent then.
        </p>

        {current && (
          <Alert tone="warning">
            {current.active
              ? `Maintenance is on until ${new Date(current.endsAt).toLocaleString()}.`
              : `Maintenance is scheduled from ${current.startsAt ? new Date(current.startsAt).toLocaleString() : '?'} to ${new Date(current.endsAt).toLocaleString()}.`}
          </Alert>
        )}

        {!current?.active && (
          <div>
            <label className="mb-1 block text-sm font-medium text-slate-700 dark:text-gh-fgSoft" htmlFor="maintenance-from">
              From
            </label>
            <input
              id="maintenance-from"
              type="datetime-local"
              className={inputClass}
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </div>
        )}

        <div>
          <label className="mb-1 block text-sm font-medium text-slate-700 dark:text-gh-fgSoft" htmlFor="maintenance-to">
            To
          </label>
          <input
            id="maintenance-to"
            type="datetime-local"
            className={inputClass}
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
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
            {current && (
              <Button
                variant="secondary"
                loading={busy === 'end'}
                disabled={busy !== null && busy !== 'end'}
                onClick={() => void run('end')}
              >
                {current.active ? 'End maintenance now' : 'Cancel scheduled maintenance'}
              </Button>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" disabled={busy !== null} onClick={onClose}>
              Cancel
            </Button>
            <Button loading={busy === 'start'} disabled={busy !== null && busy !== 'start'} onClick={() => void run('start')}>
              {current ? 'Update' : 'Save maintenance'}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
