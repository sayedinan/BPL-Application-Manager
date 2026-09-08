// Shared API client for the BPL admin SPA.
//
// SPEC §4.1 calls for RFC 7807 ProblemDetail responses, with a
// `code` field on every error (see SPEC §4.2 for the locked
// list). This client throws an `ApiError` whose `code` field is
// populated from the server's response, so feature code can do:
//
//   try {
//     await api.post('/applications/123/start', body, { ... });
//   } catch (err) {
//     if (err instanceof ApiError && err.code === 'SHELLCHECK_FAILED') {
//       // show the inline error next to the script field
//     }
//   }
//
// 401 is a special case: the SPEC's frontend-auth-session-handling
// skill says "any API call that returns 401 ... triggers a redirect
// to login" — and this client enforces that automatically. A 401
// from any endpoint (including the page-load hydration call from
// `AuthHydrator`) causes a hard `window.location.assign('/login')`,
// so no in-memory React state survives. The thrown `ApiError` is
// not re-raised on the 401 path — the page is reloading.

const API_BASE = '/api/v1';
const LOGIN_PATH = '/login';

export class ApiError extends Error {
  public readonly status: number;
  public readonly code: string | null;
  public readonly details: unknown;

  constructor(
    message: string,
    status: number,
    code: string | null,
    details: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';

interface RequestOptions {
  method?: Method;
  body?: unknown;
  /**
   * If true, the wrapper will NOT redirect to /login on 401. Used
   * by the login endpoint itself, where 401 means "bad
   * credentials" (a normal response) rather than "stale session".
   */
  skipAuthRedirect?: boolean;
  signal?: AbortSignal;
}

interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  details?: unknown;
}

function isProblemDetail(value: unknown): value is ProblemDetail {
  return typeof value === 'object' && value !== null;
}

function redirectToLogin(): void {
  // Hard redirect — the page reload drops all in-memory React
  // state, which is the right behavior for a session-loss event.
  // A SPA-internal `<Navigate to="/login" />` would not clear
  // the in-memory user state, which is the anti-pattern called
  // out in the skill ("Do NOT allow any page other than the
  // change-password screen to render while mustChangePassword =
  // true" — same spirit: trust the server, not client memory).
  if (typeof window !== 'undefined' && window.location.pathname !== LOGIN_PATH) {
    window.location.assign(LOGIN_PATH);
  }
}

export async function apiFetch<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const url = path.startsWith('http') ? path : `${API_BASE}${path}`;
  const init: RequestInit = {
    method: options.method ?? 'GET',
    credentials: 'include', // SPEC §8.4 — send the HttpOnly cookie
    headers: {
      Accept: 'application/json',
      ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
    ...(options.signal ? { signal: options.signal } : {}),
  };

  const response = await fetch(url, init);

  // No-content (204) is a success with an empty body — the
  // caller (currently only the logout endpoint) gets back
  // `undefined` as T.
  if (response.status === 204) {
    return undefined as T;
  }

  // 401 → redirect to login. This covers both the page-load
  // hydration path (GET /api/v1/auth/me returning 401) and the
  // mid-session case (any later API call returning 401). The
  // login endpoint itself opts out via `skipAuthRedirect: true`
  // so that bad credentials produce an `ApiError(INVALID_CREDENTIALS)`
  // rather than a redirect.
  if (response.status === 401 && !options.skipAuthRedirect) {
    redirectToLogin();
    // Throw a synthetic ApiError so callers (notably the
    // page-load AuthHydrator) can run their catch block and
    // update the AuthContext to 'unauthenticated' before the
    // browser navigates. Without this, the caller would hang
    // on a never-resolving promise and the context would stay
    // at 'unknown' / its old value until the page reloads.
    //
    // The redirect is already in flight by the time the throw
    // reaches the caller. Throwing here is the lightest way to
    // signal "do not continue" while still allowing synchronous
    // cleanup code (catch blocks) to run.
    throw new ApiError('Session expired or invalid', 401, 'INVALID_CREDENTIALS', null);
  }

  if (!response.ok) {
    // Try to parse the body as an RFC 7807 ProblemDetail. If
    // parsing fails (e.g. an HTML error page from a proxy), fall
    // back to a generic ApiError with status-only.
    let parsed: ProblemDetail = {};
    try {
      parsed = (await response.json()) as ProblemDetail;
    } catch {
      // body wasn't JSON; leave parsed as {}
    }
    const code = isProblemDetail(parsed) && typeof parsed.code === 'string'
      ? parsed.code
      : null;
    const detail = isProblemDetail(parsed) && typeof parsed.detail === 'string'
      ? parsed.detail
      : response.statusText;
    const details = isProblemDetail(parsed) ? parsed.details ?? null : null;
    throw new ApiError(
      detail || `HTTP ${response.status}`,
      response.status,
      code,
      details,
    );
  }

  // Success: 200/201/etc. with a JSON body.
  if (response.status === 200 || response.status === 201) {
    return (await response.json()) as T;
  }

  // Other 2xx (e.g. 202 Accepted, 203) — still try to parse as
  // JSON. If the body isn't JSON, fall through to returning
  // undefined rather than throwing.
  try {
    return (await response.json()) as T;
  } catch {
    return undefined as T;
  }
}

// Convenience helpers — these exist so feature code reads as
// `api.get('/applications')` rather than `apiFetch('/applications', { method: 'GET' })`.
export const api = {
  get: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'PUT', body }),
  delete: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'DELETE' }),
};
