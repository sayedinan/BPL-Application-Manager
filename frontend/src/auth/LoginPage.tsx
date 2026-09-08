// Minimal login page placeholder.
//
// This is intentionally a stub for the 401-redirect slice. The
// full login form (username/password fields, validation, error
// display, mustChangePassword routing on success) lands in a
// separate slice. The page exists now only so the
// `window.location.assign('/login')` redirect from the API
// client lands on a real route instead of a blank URL.

export function LoginPage(): JSX.Element {
  return (
    <div data-login-page="placeholder" className="p-8">
      <h1 className="text-2xl font-semibold">Sign in</h1>
      <p className="mt-2 text-sm text-gray-600">
        Login form lands in a separate slice. This page exists so
        the 401 redirect from the API client has a real target.
      </p>
    </div>
  );
}
