/** Mirrors the server's rule, to hide actions that would be refused. */
export function canManageUser(viewerRole: string | undefined, targetRole: string): boolean {
  return viewerRole === 'SYS_ADMIN' || (viewerRole === 'ADMIN' && targetRole === 'USER');
}
