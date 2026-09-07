// Root shell.
//
// Intentionally minimal for the scaffold step: just enough to make the
// project build and render an empty container. Real route definitions,
// role gating, and the `mustChangePassword` lock (SPEC §9.2 / §8.1) will
// be added once the auth package and the feature pages land.

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';

const queryClient = new QueryClient();

export function App(): JSX.Element {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <div data-app-shell="placeholder" />
      </BrowserRouter>
    </QueryClientProvider>
  );
}
