// Typed path constants for the SPEC §4.3 endpoints.
//
// Using constants (rather than string literals scattered through
// the codebase) means a typo fails at TypeScript compile time
// rather than at runtime as a 404 from the API. The `as const`
// keeps each value as a literal type, so `api.get(API.AUTH.ME)`
// is also narrower than `api.get('/auth/me')` if the consumer
// cares to pattern-match.

export const API = {
  AUTH: {
    LOGIN: '/auth/login',
    LOGOUT: '/auth/logout',
    ME: '/auth/me',
    CHANGE_PASSWORD: '/auth/change-password',
  },
  APPLICATIONS: {
    LIST: '/applications',
    DETAIL: (id: number | string) => `/applications/${id}`,
    TEST_CONNECTION: '/applications/test-connection',
    CREATE: '/applications',
    UPDATE: (id: number | string) => `/applications/${id}`,
    DELETE: (id: number | string) => `/applications/${id}`,
    START: (id: number | string) => `/applications/${id}/start`,
    STOP: (id: number | string) => `/applications/${id}/stop`,
    LOGS: (id: number | string) => `/applications/${id}/logs`,
  },
  USERS: {
    LIST: '/users',
    CREATE: '/users',
    UPDATE: (id: number | string) => `/users/${id}`,
    DELETE: (id: number | string) => `/users/${id}`,
    RESET_PASSWORD: (id: number | string) => `/users/${id}/reset-password`,
  },
  AUDIT_LOGS: '/audit-logs',
} as const;
