import { useEffect, useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { Alert } from '@/components/ui/Alert';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';
import { LoadingBlock } from '@/components/ui/Spinner';
import type { HealthConfig, HealthTestResult } from './types';

const inputClass =
  'w-full rounded-xl border border-slate-200 bg-white shadow-sm px-3 py-2 text-sm text-slate-900 ' +
  'placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 ' +
  'dark:border-gh-border dark:bg-surface-dark dark:text-gh-fg';
const labelClass = 'mb-1 block text-sm font-medium text-slate-700 dark:text-gh-fgSoft';
const hintClass = 'mt-1 text-xs text-slate-500 dark:text-gh-muted';

const MIN_INTERVAL = 3;
const MAX_INTERVAL = 300;

/**
 * Sys.Admin form for an application's health endpoint. Everything here
 * goes through /applications/{id}/health/config and /health/test, which
 * the backend restricts to Sys.Admin. The API key is write-only: the
 * server never sends it back, only whether one is saved.
 */
export function HealthSettingsModal({
  applicationId,
  applicationName,
  onClose,
}: {
  applicationId: number;
  applicationName: string;
  onClose: () => void;
}): JSX.Element {
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [configured, setConfigured] = useState(false);
  const [hasApiKey, setHasApiKey] = useState(false);

  const [enabled, setEnabled] = useState(true);
  const [url, setUrl] = useState('');
  const [format, setFormat] = useState<'CONTRACT' | 'ACTUATOR'>('CONTRACT');
  const [apiKey, setApiKey] = useState('');
  const [removeKey, setRemoveKey] = useState(false);
  const [pin, setPin] = useState('');
  const [interval, setIntervalValue] = useState('10');

  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<HealthTestResult | null>(null);
  // What the last passing test was run with. A test only counts for Save while
  // the form still holds exactly those values.
  const [testedSignature, setTestedSignature] = useState<string | null>(null);
  const [saveAnyway, setSaveAnyway] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .get<HealthConfig>(API.APPLICATIONS.HEALTH_CONFIG(applicationId))
      .then((c) => {
        if (cancelled) return;
        setConfigured(c.configured);
        if (c.configured) {
          setEnabled(c.enabled ?? true);
          setUrl(c.url ?? '');
          setFormat(c.format ?? 'CONTRACT');
          setHasApiKey(c.hasApiKey ?? false);
          setPin(c.tlsPinSha256 ?? '');
          setIntervalValue(String(c.pollIntervalSeconds ?? 10));
        }
      })
      .catch((err) => {
        if (!cancelled) setLoadError(err instanceof ApiError ? err.message : 'Failed to load settings.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [applicationId]);

  const isHttps = url.trim().toLowerCase().startsWith('https://');

  // apiKey: left out = keep the saved key, '' = remove it, text = replace it.
  function keyField(): string | undefined {
    if (removeKey) return '';
    return apiKey.trim() ? apiKey : undefined;
  }

  // The values a test depends on. null for the key means "keep the saved one".
  function currentSignature(): string {
    return JSON.stringify([url.trim(), format, keyField() ?? null, isHttps ? pin.trim() : '']);
  }

  const testPassed = testResult?.ok === true && testedSignature === currentSignature();
  // A wrong URL makes the application look offline and sends alerts, so while
  // monitoring is on, Save needs a passing test (or an explicit override).
  const needsTest = enabled && !testPassed && !saveAnyway;

  function describeError(err: unknown, fallback: string): string {
    return err instanceof ApiError ? err.message : fallback;
  }

  async function handleTest() {
    setFormError(null);
    setTestResult(null);
    setTestedSignature(null);
    if (!url.trim()) {
      setFormError('Enter the health URL first.');
      return;
    }
    setTesting(true);
    try {
      const result = await api.post<HealthTestResult>(API.APPLICATIONS.HEALTH_TEST(applicationId), {
        url: url.trim(),
        format,
        apiKey: keyField(),
        tlsPinSha256: isHttps ? pin.trim() : '',
      });
      setTestResult(result);
      setTestedSignature(result.ok ? currentSignature() : null);
    } catch (err) {
      setFormError(describeError(err, 'The test could not be run.'));
    } finally {
      setTesting(false);
    }
  }

  async function handleSave() {
    setFormError(null);
    if (needsTest) {
      setFormError('Run "Test connection" first, or tick "Save without a successful test".');
      return;
    }
    const seconds = Number(interval);
    if (!Number.isInteger(seconds) || seconds < MIN_INTERVAL || seconds > MAX_INTERVAL) {
      setFormError(`Check interval must be a whole number between ${MIN_INTERVAL} and ${MAX_INTERVAL} seconds.`);
      return;
    }
    if (!url.trim()) {
      setFormError('The health URL is required.');
      return;
    }
    setSaving(true);
    try {
      await api.put(API.APPLICATIONS.HEALTH_CONFIG(applicationId), {
        enabled,
        url: url.trim(),
        format,
        apiKey: keyField(),
        tlsPinSha256: isHttps ? pin.trim() : '',
        pollIntervalSeconds: seconds,
      });
      onClose();
    } catch (err) {
      setFormError(describeError(err, 'Failed to save the settings.'));
    } finally {
      setSaving(false);
    }
  }

  async function handleRemove() {
    setFormError(null);
    setRemoving(true);
    try {
      await api.delete(API.APPLICATIONS.HEALTH_CONFIG(applicationId));
      onClose();
    } catch (err) {
      setFormError(describeError(err, 'Failed to remove health monitoring.'));
      setConfirmRemove(false);
    } finally {
      setRemoving(false);
    }
  }

  const busy = saving || testing || removing;

  return (
    <Modal onClose={onClose} widthClass="max-w-xl">
      <div className="border-b border-slate-200 px-6 py-4 pr-14 dark:border-gh-border">
        <h2 className="text-lg font-semibold text-slate-900 dark:text-white">Health monitoring</h2>
        <p className="mt-1 text-xs text-slate-500 dark:text-gh-muted">{applicationName}</p>
      </div>

      <div className="space-y-4 p-6">
        {loading ? (
          <LoadingBlock label="Loading settings…" className="py-6" />
        ) : loadError ? (
          <Alert>{loadError}</Alert>
        ) : (
          <>
            {formError && <Alert>{formError}</Alert>}

            <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-gh-fgSoft">
              <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
              Monitor this application through its health endpoint
            </label>

            <div>
              <label className={labelClass} htmlFor="health-url">Health URL</label>
              <input
                id="health-url"
                className={inputClass}
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="http://10.0.0.5:8080/status/dashboard"
                autoComplete="off"
              />
            </div>

            <div>
              <label className={labelClass} htmlFor="health-format">Response format</label>
              <select
                id="health-format"
                className={inputClass}
                value={format}
                onChange={(e) => setFormat(e.target.value === 'ACTUATOR' ? 'ACTUATOR' : 'CONTRACT')}
              >
                <option value="CONTRACT">Standard contract (HEALTH_CONTRACT.md)</option>
                <option value="ACTUATOR">Spring Boot Actuator (/actuator/health)</option>
              </select>
            </div>

            <div>
              <label className={labelClass} htmlFor="health-key">API key</label>
              <input
                id="health-key"
                type="password"
                className={inputClass}
                value={apiKey}
                onChange={(e) => {
                  setApiKey(e.target.value);
                  setRemoveKey(false);
                }}
                placeholder={hasApiKey ? 'A key is saved — leave blank to keep it' : 'Optional'}
                autoComplete="new-password"
              />
              {hasApiKey && (
                <label className="mt-2 flex items-center gap-2 text-xs text-slate-600 dark:text-gh-fgSoft">
                  <input
                    type="checkbox"
                    checked={removeKey}
                    onChange={(e) => {
                      setRemoveKey(e.target.checked);
                      if (e.target.checked) setApiKey('');
                    }}
                  />
                  Remove the saved key
                </label>
              )}
              <p className={hintClass}>Sent as the X-API-Key header. It is stored encrypted and never shown again.</p>
            </div>

            {isHttps && (
              <div>
                <label className={labelClass} htmlFor="health-pin">Certificate fingerprint (self-signed only)</label>
                <input
                  id="health-pin"
                  className={inputClass}
                  value={pin}
                  onChange={(e) => setPin(e.target.value)}
                  placeholder="Leave empty for a normally trusted certificate"
                  autoComplete="off"
                  spellCheck={false}
                />
                <details className="mt-1 text-xs text-slate-500 dark:text-gh-muted">
                  <summary className="cursor-pointer">How to get the fingerprint</summary>
                  <p className="mt-1">Run this on a server that can reach the application, then paste the result:</p>
                  <code className="mt-1 block break-all rounded-md bg-slate-100 p-2 dark:bg-gh-inset">
                    openssl s_client -connect HOST:PORT {'</dev/null'} 2{'>'}/dev/null | openssl x509 -noout -fingerprint -sha256
                  </code>
                </details>
              </div>
            )}

            <div>
              <label className={labelClass} htmlFor="health-interval">Check interval (seconds)</label>
              <input
                id="health-interval"
                type="number"
                min={MIN_INTERVAL}
                max={MAX_INTERVAL}
                className={inputClass}
                value={interval}
                onChange={(e) => setIntervalValue(e.target.value)}
              />
              <p className={hintClass}>
                Between {MIN_INTERVAL} and {MAX_INTERVAL}. The application counts as offline after two failed checks in a row.
              </p>
            </div>

            {testResult && (
              <Alert tone={testResult.ok ? (testResult.status === 'UP' ? 'success' : 'warning') : 'error'}>
                {testResult.ok ? (
                  <>
                    Connected. The application reports <strong>{testResult.status}</strong>
                    {testResult.responseMs !== null ? ` (HTTP ${testResult.httpStatus}, ${testResult.responseMs} ms)` : ''}.
                    {testResult.sslDaysRemaining !== null && ` Certificate valid for ${testResult.sslDaysRemaining} more days.`}
                  </>
                ) : (
                  <>
                    Test failed: {testResult.error ?? 'unknown problem'}
                    {testResult.httpStatus !== null ? ` (HTTP ${testResult.httpStatus})` : ''}
                  </>
                )}
              </Alert>
            )}

            {enabled && !testPassed && (
              <label className="flex items-start gap-2 text-xs text-slate-600 dark:text-gh-fgSoft">
                <input
                  type="checkbox"
                  className="mt-0.5"
                  checked={saveAnyway}
                  onChange={(e) => setSaveAnyway(e.target.checked)}
                />
                <span>
                  Save without a successful test. A wrong address makes the application look offline and sends
                  alerts, so only use this if the application is stopped on purpose.
                </span>
              </label>
            )}

            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 pt-4 dark:border-gh-border">
              <div className="flex items-center gap-2">
                {configured &&
                  (confirmRemove ? (
                    <>
                      <Button variant="danger" size="sm" loading={removing} disabled={busy && !removing} onClick={() => void handleRemove()}>
                        Confirm remove
                      </Button>
                      <Button variant="ghost" size="sm" disabled={removing} onClick={() => setConfirmRemove(false)}>
                        Cancel
                      </Button>
                    </>
                  ) : (
                    <Button variant="secondary" size="sm" disabled={busy} onClick={() => setConfirmRemove(true)}>
                      Remove monitoring
                    </Button>
                  ))}
              </div>
              <div className="flex items-center gap-2">
                <Button variant="secondary" loading={testing} disabled={busy && !testing} onClick={() => void handleTest()}>
                  Test connection
                </Button>
                <Button loading={saving} disabled={needsTest || (busy && !saving)} onClick={() => void handleSave()}>
                  Save
                </Button>
              </div>
            </div>
            {configured && confirmRemove && (
              <p className={hintClass}>
                After removing, this application goes back to being checked by its SSH status script.
              </p>
            )}
          </>
        )}
      </div>
    </Modal>
  );
}
