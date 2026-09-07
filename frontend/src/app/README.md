# `app/` — application shell

Top-level wiring that wraps every page:

- `App.tsx` — root component. Mounts the React Query client and the router.
- `Router.tsx` — route table. Will define the SPEC §9.2 routes once the
  feature pages exist.
- `RootLayout.tsx` — top tab bar, application context, layout chrome
  (SPEC §9.3).
- `providers/` — provider components that need to wrap the tree (React
  Query, STOMP client, auth context, etc.).

The `mustChangePassword` redirect lock (SPEC §8.1) is enforced here, in a
route guard that runs before any non-`/change-password` page can render.
