import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { Card, PageHeader } from '@/components/ui/Card';
import { Alert } from '@/components/ui/Alert';

interface ApplicationSummary {
  id: number;
  name: string;
  online: boolean;
  startedAt: string | null;
}

interface FormState {
  name: string;
  serverIp: string;
  sshUsername: string;
  sshPassword: string;
  sshHostKeyFingerprint: string;
  startScript: string;
  stopScript: string;
  logScript: string;
  pollIntervalSeconds: string;
}

const EMPTY_FORM: FormState = {
  name: '', serverIp: '', sshUsername: '', sshPassword: '', sshHostKeyFingerprint: '',
  startScript: '', stopScript: '', logScript: '', pollIntervalSeconds: '5',
};

const inputClass =
  'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 ' +
  'placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  'dark:border-slate-700 dark:bg-surface-dark dark:text-slate-100';
const labelClass = 'mb-1 block text-sm font-medium text-slate-700 dark:text-slate-300';

export function ApplicationsPage(): JSX.Element {
  const [apps, setApps] = useState<ApplicationSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [loadingEdit, setLoadingEdit] = useState(false);
  const [testStatus, setTestStatus] = useState<'untested' | 'testing' | 'verified' | 'failed'>('untested');
  const [testMessage, setTestMessage] = useState<string | null>(null);

  async function loadApplications() {
    try {
      const list = await api.get<ApplicationSummary[]>(API.APPLICATIONS.LIST);
      setApps(list);
      setListError(null);
    } catch (err) {
      setListError(err instanceof ApiError ? err.message : 'Failed to load applications.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void loadApplications(); }, []);

  function updateField<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => ({ ...f, [key]: value }));
    if (key === 'serverIp' || key === 'sshUsername' || key === 'sshPassword') {
      setTestStatus('untested');
    }
  }

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    const pollInterval = Number(form.pollIntervalSeconds);
    if (!Number.isFinite(pollInterval) || pollInterval <= 0) { setFormError('Poll interval must be a positive number of seconds.'); return; }
    if (!form.name.trim()) { setFormError('Name is required.'); return; }
    setSubmitting(true);
    try {
      await api.post(API.APPLICATIONS.CREATE, {
        name: form.name, serverIp: form.serverIp, sshUsername: form.sshUsername,
        sshPassword: form.sshPassword, sshHostKeyFingerprint: form.sshHostKeyFingerprint,
        startScript: form.startScript, stopScript: form.stopScript, logScript: form.logScript,
        pollIntervalSeconds: pollInterval,
      });
      setForm(EMPTY_FORM);
      setShowForm(false);
      setTestStatus('untested');
      await loadApplications();
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : 'Failed to create application.');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleTestConnection() {
    setTestStatus('testing');
    setTestMessage(null);
    try {
      const result = await api.post<{ status: string; fingerprint?: string }>(
        API.APPLICATIONS.TEST_CONNECTION,
        { serverIp: form.serverIp, sshUsername: form.sshUsername, sshPassword: form.sshPassword, sshHostKeyFingerprint: form.sshHostKeyFingerprint || undefined },
      );
      if (result.fingerprint) updateField('sshHostKeyFingerprint', result.fingerprint);
      setTestStatus('verified');
    } catch (err) {
      setTestStatus('failed');
      setTestMessage(err instanceof ApiError ? err.message : 'Connection failed.');
    }
  }

  async function handleEditClick(app: ApplicationSummary) {
    if (app.online === false) return;
    setFormError(null);
    setShowForm(false);
    setTestStatus('untested');
    setLoadingEdit(true);
    setEditingId(app.id);
    try {
      const detail = await api.get<Record<string, unknown>>(API.APPLICATIONS.DETAIL(app.id));
      setForm({
        name: String(detail.name ?? ''),
        serverIp: String(detail.serverIp ?? ''),
        sshUsername: String(detail.sshUsername ?? ''),
        sshPassword: '',
        sshHostKeyFingerprint: '',
        startScript: String(detail.startScript ?? ''),
        stopScript: String(detail.stopScript ?? ''),
        logScript: String(detail.logScript ?? ''),
        pollIntervalSeconds: String(detail.pollIntervalSeconds ?? '5'),
      });
    } catch (err) {
      setListError(err instanceof ApiError ? err.message : `Failed to load ${app.name} for editing.`);
      setEditingId(null);
    } finally {
      setLoadingEdit(false);
    }
  }

  function cancelEdit() {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setFormError(null);
    setTestStatus('untested');
  }

  async function handleUpdate(e: FormEvent) {
    e.preventDefault();
    if (editingId === null) return;
    setFormError(null);
    const pollInterval = Number(form.pollIntervalSeconds);
    if (!Number.isFinite(pollInterval) || pollInterval <= 0) { setFormError('Poll interval must be a positive number of seconds.'); return; }
    if (!form.name.trim()) { setFormError('Name is required.'); return; }
    setSubmitting(true);
    try {
      await api.put(API.APPLICATIONS.UPDATE(editingId), {
        name: form.name, serverIp: form.serverIp, sshUsername: form.sshUsername,
        sshPassword: form.sshPassword || undefined,
        startScript: form.startScript, stopScript: form.stopScript, logScript: form.logScript,
        pollIntervalSeconds: pollInterval,
      });
      cancelEdit();
      await loadApplications();
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : 'Failed to update application. It may no longer be offline.');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete(app: ApplicationSummary) {
    if (app.online === false) return;
    if (!window.confirm(`Delete "${app.name}"? This cannot be undone.`)) return;
    setDeletingId(app.id);
    try {
      await api.delete(API.APPLICATIONS.DELETE(app.id));
      await loadApplications();
    } catch (err) {
      setListError(err instanceof ApiError ? err.message : `Failed to delete ${app.name}.`);
    } finally {
      setDeletingId(null);
    }
  }

  const formOpen = showForm || (editingId !== null && !loadingEdit);

  return (
    <div className="p-6 sm:p-8 max-w-6xl mx-auto">
      <PageHeader
        title="Applications"
        description="SSH-backed order applications — create, edit, and monitor."
        actions={
          <Button
            variant={showForm ? 'secondary' : 'primary'}
            onClick={() => { if (editingId !== null) cancelEdit(); setShowForm((s) => !s); }}
          >
            {showForm ? 'Cancel' : '+ Add Application'}
          </Button>
        }
      />

      {listError && <Alert className="mb-4">{listError}</Alert>}

      {formOpen && (
        <Card className="mb-6 max-w-xl animate-fade-in p-5">
          <form onSubmit={editingId !== null ? handleUpdate : handleCreate} noValidate>
            <h2 className="mb-4 text-base font-semibold text-slate-900 dark:text-white">
              {editingId !== null ? 'Edit Application' : 'New Application'}
            </h2>

            <label className="mb-3 block">
              <span className={labelClass}>Name</span>
              <input value={form.name} onChange={(e) => updateField('name', e.target.value)} className={inputClass} required />
            </label>

            <label className="mb-3 block">
              <span className={labelClass}>Server IP</span>
              <input value={form.serverIp} onChange={(e) => updateField('serverIp', e.target.value)} placeholder="192.168.1.10" className={inputClass} required />
            </label>

            <div className="mb-3 grid grid-cols-2 gap-3">
              <label className="block">
                <span className={labelClass}>SSH Username</span>
                <input value={form.sshUsername} onChange={(e) => updateField('sshUsername', e.target.value)} className={inputClass} required />
              </label>
              <label className="block">
                <span className={labelClass}>SSH Password</span>
                <input type="password" value={form.sshPassword} onChange={(e) => updateField('sshPassword', e.target.value)} className={inputClass} required />
              </label>
            </div>

            <label className="mb-3 block">
              <span className={labelClass}>SSH Host Key Fingerprint</span>
              <input
                value={form.sshHostKeyFingerprint}
                readOnly
                placeholder="Captured after a successful Test Connection"
                className={`${inputClass} cursor-default bg-slate-50 font-mono text-xs dark:bg-slate-800/50`}
              />
            </label>

            <div className="mb-5 flex items-center gap-3">
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={handleTestConnection}
                disabled={testStatus === 'testing' || !form.serverIp || !form.sshUsername || !form.sshPassword}
              >
                {testStatus === 'testing' ? 'Testing…' : 'Test Connection'}
              </Button>
              {testStatus === 'verified' && <Badge tone="online">Verified — fingerprint captured</Badge>}
              {testStatus === 'failed' && <Badge tone="error">{testMessage ?? 'Connection failed'}</Badge>}
            </div>

            <label className="mb-3 block">
              <span className={labelClass}>Start Script</span>
              <textarea value={form.startScript} onChange={(e) => updateField('startScript', e.target.value)} className={`${inputClass} font-mono`} rows={3} required />
            </label>
            <label className="mb-3 block">
              <span className={labelClass}>Stop Script</span>
              <textarea value={form.stopScript} onChange={(e) => updateField('stopScript', e.target.value)} className={`${inputClass} font-mono`} rows={3} required />
            </label>
            <label className="mb-3 block">
              <span className={labelClass}>Log Script</span>
              <textarea value={form.logScript} onChange={(e) => updateField('logScript', e.target.value)} className={`${inputClass} font-mono`} rows={3} required />
            </label>

            <label className="mb-5 block">
              <span className={labelClass}>Poll Interval (seconds)</span>
              <input type="number" min={1} value={form.pollIntervalSeconds} onChange={(e) => updateField('pollIntervalSeconds', e.target.value)} className={`${inputClass} w-32`} />
            </label>

            {formError && <Alert className="mb-4">{formError}</Alert>}

            <div className="flex gap-2">
              <Button type="submit" disabled={submitting || testStatus !== 'verified'}>
                {submitting ? (editingId !== null ? 'Saving…' : 'Creating…') : (editingId !== null ? 'Save Changes' : 'Create Application')}
              </Button>
              {editingId !== null && (
                <Button type="button" variant="secondary" onClick={cancelEdit}>Cancel</Button>
              )}
            </div>
          </form>
        </Card>
      )}

      {!showForm && editingId === null && (
        loading ? (
          <p className="text-sm text-slate-500 dark:text-slate-400">Loading…</p>
        ) : apps.length === 0 ? (
          <Card className="p-8 text-center text-sm text-slate-500 dark:text-slate-400">
            No applications yet. Add one above.
          </Card>
        ) : (
          <Card className="overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
                  <th className="px-4 py-3 font-medium">Name</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {apps.map((app) => (
                  <tr key={app.id} className="border-b border-slate-100 last:border-0 dark:border-slate-800/60">
                    <td className="px-4 py-3 font-medium text-slate-900 dark:text-white">{app.name}</td>
                    <td className="px-4 py-3">
                      <Badge tone={app.online ? 'online' : 'offline'}>{app.online ? 'Online' : 'Offline'}</Badge>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-2">
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={app.online}
                          onClick={() => handleEditClick(app)}
                          title={app.online ? 'Must be Offline to edit' : undefined}
                        >
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="danger"
                          disabled={app.online || deletingId === app.id}
                          onClick={() => handleDelete(app)}
                          title={app.online ? 'Must be Offline to delete' : undefined}
                        >
                          {deletingId === app.id ? 'Deleting…' : 'Delete'}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        )
      )}
    </div>
  );
}