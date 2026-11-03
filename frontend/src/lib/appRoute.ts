// Application detail URLs use the application name, not the numeric id.
// Names match ^[a-zA-Z0-9_-]{1,64}$ (SPEC §3.3), so they are URL-safe, and the
// DB enforces uniqueness on LOWER(name) (V2 migration). Lowercasing both sides
// here mirrors that rule exactly, so at most one application can match.

export const appPath = (name: string): string => `/apps/${name}`;

export const findAppByName = <T extends { name: string }>(
  apps: T[],
  nameParam: string | undefined,
): T | undefined => {
  if (!nameParam) return undefined;
  const wanted = nameParam.toLowerCase();
  return apps.find((a) => a.name.toLowerCase() === wanted);
};