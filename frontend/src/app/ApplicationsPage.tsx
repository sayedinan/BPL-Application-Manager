import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';

type Status = 'RUNNING' | 'STOPPED' | 'STARTING' | 'STOPPING' | 'ERROR';

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
      await loadApplications();
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : 'Failed to create application.');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete(app: ApplicationSummary) {
    if (app.status !== 'STOPPED') return;
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
        <button onClick={() => setShowForm((s) => !s)} className="text-sm px-3 py-2 bg-blue-600 text-white rounded">
          {showForm ? 'Cancel' : '+ Add Application'}
        </button>
      </div>
      {listError && <p role="alert" className="text-sm text-red-600 mb-4">{listError}</p>}
      {showForm && (
        <form onSubmit={handleCreate} noValidate className="border rounded p-4 mb-6 max-w-xl">
          <h2 className="font-medium mb-3">New Application</h2>
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
            <input value={form.sshHostKeyFingerprint} onChange={(e) => updateField('sshHostKeyFingerprint', e.target.value)} placeholder="aa:bb:cc:dd:..." className="w-full border rounded px-3 py-2 font-mono text-sm" required />
            <span className="block text-xs text-gray-500 mt-1">
              MD5 colon-hex format (JSch's default), not OpenSSH's SHA256 format. From this server, run:{' '}
              <code className="bg-gray-100 px-1">ssh-keyscan -t rsa &lt;server-ip&gt; | ssh-keygen -lf - -E md5</code>{' '}
              and use the part after &quot;MD5:&quot;.
            </span>
          </label>
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
          <button type="submit" disabled={submitting} className="text-sm px-4 py-2 bg-blue-600 text-white rounded disabled:opacity-50">
            {submitting ? 'Creating…' : 'Create Application'}
          </button>
        </form>
      )}
      {loading ? (
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
                <td className="py-2">{app.status}</td>
                <td className="py-2 text-right">
                  <button disabled={app.status !== 'STOPPED' || deletingId === app.id} onClick={() => handleDelete(app)} className="text-xs px-3 py-1 bg-red-600 text-white rounded disabled:opacity-40" title={app.status !== 'STOPPED' ? 'Must be STOPPED to delete' : undefined}>
                    {deletingId === app.id ? 'Deleting…' : 'Delete'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
