import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';

type Status = 'RUNNING' | 'STOPPED' | 'STARTING' | 'STOPPING' | 'ERROR';

function isOnline(status: Status): boolean {
  return status === 'RUNNING';
}
function isTransitional(status: Status): boolean {
  return status === 'STARTING' || status === 'STOPPING';
}
function isEditable(status: Status): boolean {
  return status === 'STOPPED' || status === 'ERROR';
}
function statusLabel(status: Status): string {
  if (isTransitional(status)) return status === 'STARTING' ? 'Starting…' : 'Stopping…';
  return isOnline(status) ? 'Online' : 'Offline';
}
interface ApplicationSummary {
  id: number;
  name: string;
  status: Status;
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
    if (!isEditable(app.status)) return;
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
    if (!isEditable(app.status)) return;
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

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-semibold">Applications</h1>
        <button onClick={() => { if (editingId !== null) cancelEdit(); setShowForm((s) => !s); }} className="text-sm px-3 py-2 bg-blue-600 text-white rounded">
          {showForm ? 'Cancel' : '+ Add Application'}
        </button>
      </div>
      {listError && <p role="alert" className="text-sm text-red-600 mb-4">{listError}</p>}
      {(showForm || (editingId !== null && !loadingEdit)) && (
        <form onSubmit={editingId !== null ? handleUpdate : handleCreate} noValidate className="border rounded p-4 mb-6 max-w-xl">
          <h2 className="font-medium mb-3">{editingId !== null ? 'Edit Application' : 'New Application'}</h2>
          <label className="block mb-3">
            <span className="block text-sm text-gray-700 mb-1">Name</span>
            <input value={form.name} onChange={(e) => updateField('name', e.target.value)} className="w-full border rounded px-3 py-2" required />
          </label>
          <label className="block mb-3">
            <span className="block text-sm text-gray-700 mb-1">Server IP</span>
            <input value={form.serverIp} onChange={(e) => updateField('serverIp', e.target.value)} placeholder="192.168.1.10" className="w-full border rounded px-3 py-2" required />
          </label>
          <div className="grid grid-cols-2 gap-3 mb-3">
            <label className="block">
              <span className="block text-sm text-gray-700 mb-1">SSH Username</span>
              <input value={form.sshUsername} onChange={(e) => updateField('sshUsername', e.target.value)} className="w-full border rounded px-3 py-2" required />
            </label>
            <label className="block">
              <span className="block text-sm text-gray-700 mb-1">SSH Password</span>
              <input type="password" value={form.sshPassword} onChange={(e) => updateField('sshPassword', e.target.value)} className="w-full border rounded px-3 py-2" required />
            </label>
          </div>
          <label className="block mb-3">
            <span className="block text-sm text-gray-700 mb-1">SSH Host Key Fingerprint</span>
            <input value={form.sshHostKeyFingerprint} readOnly className="w-full border rounded px-3 py-2 font-mono text-sm bg-gray-50 cursor-default" />
          </label>
          <div className="mb-4">
            <button type="button" onClick={handleTestConnection} disabled={testStatus === 'testing' || !form.serverIp || !form.sshUsername || !form.sshPassword} className="text-sm px-3 py-2 border rounded disabled:opacity-40">
              {testStatus === 'testing' ? 'Testing…' : 'Test Connection'}
            </button>
            {testStatus === 'verified' && <span className="ml-2 text-sm text-green-700">✓ Verified — fingerprint captured</span>}
            {testStatus === 'failed' && <span className="ml-2 text-sm text-red-600">✗ {testMessage}</span>}
          </div>
          <label className="block mb-3">
            <span className="block text-sm text-gray-700 mb-1">Start Script</span>
            <textarea value={form.startScript} onChange={(e) => updateField('startScript', e.target.value)} className="w-full border rounded px-3 py-2 font-mono text-sm" rows={3} required />
          </label>
          <label className="block mb-3">
            <span className="block text-sm text-gray-700 mb-1">Stop Script</span>
            <textarea value={form.stopScript} onChange={(e) => updateField('stopScript', e.target.value)} className="w-full border rounded px-3 py-2 font-mono text-sm" rows={3} required />
          </label>
          <label className="block mb-3">
            <span className="block text-sm text-gray-700 mb-1">Log Script</span>
            <textarea value={form.logScript} onChange={(e) => updateField('logScript', e.target.value)} className="w-full border rounded px-3 py-2 font-mono text-sm" rows={3} required />
          </label>
          <label className="block mb-4">
            <span className="block text-sm text-gray-700 mb-1">Poll Interval (seconds)</span>
            <input type="number" min={1} value={form.pollIntervalSeconds} onChange={(e) => updateField('pollIntervalSeconds', e.target.value)} className="w-32 border rounded px-3 py-2" />
          </label>
          {formError && <p role="alert" className="text-sm text-red-600 mb-4">{formError}</p>}
          <div className="flex gap-2">
            <button type="submit" disabled={submitting || testStatus !== 'verified'} className="text-sm px-4 py-2 bg-blue-600 text-white rounded disabled:opacity-50">
              {submitting ? (editingId !== null ? 'Saving…' : 'Creating…') : (editingId !== null ? 'Save Changes' : 'Create Application')}
            </button>
            {editingId !== null && (
              <button type="button" onClick={cancelEdit} className="text-sm px-4 py-2 border rounded">Cancel</button>
            )}
          </div>
        </form>
      )}
      {!showForm && editingId === null && (
        loading ? (
          <p>Loading…</p>
        ) : apps.length === 0 ? (
          <p className="text-gray-600">No applications yet. Add one above.</p>
        ) : (
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="text-left border-b">
                <th className="py-2">Name</th>
                <th className="py-2">Status</th>
                <th className="py-2"></th>
              </tr>
            </thead>
            <tbody>
              {apps.map((app) => (
                <tr key={app.id} className="border-b">
                  <td className="py-2">{app.name}</td>
                  <td className="py-2">
                    <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
                      isTransitional(app.status)
                        ? 'bg-yellow-100 text-yellow-800'
                        : isOnline(app.status)
                          ? 'bg-green-100 text-green-700'
                          : 'bg-gray-200 text-gray-700'
                    }`}>
                      {statusLabel(app.status)}
                    </span>
                    {app.status === 'ERROR' && (
                      <p className="text-xs text-red-600 mt-1">⚠ Last start/stop attempt failed</p>
                    )}
                  </td>
                  <td className="py-2 text-right space-x-2">
                    <button disabled={!isEditable(app.status)} onClick={() => handleEditClick(app)} className="text-xs px-3 py-1 bg-blue-600 text-white rounded disabled:opacity-40" title={!isEditable(app.status) ? 'Must be Offline to edit' : undefined}>
                      Edit
                    </button>
                    <button disabled={!isEditable(app.status) || deletingId === app.id} onClick={() => handleDelete(app)} className="text-xs px-3 py-1 bg-red-600 text-white rounded disabled:opacity-40" title={!isEditable(app.status) ? 'Must be Offline to delete' : undefined}>
                      {deletingId === app.id ? 'Deleting…' : 'Delete'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      )}
    </div>
  );
}
