import { describe, expect, it } from 'vitest';

import { canManageUser } from '@/lib/userPermissions';

describe('canManageUser', () => {
  it('lets a Sys.Admin manage everyone', () => {
    for (const target of ['SYS_ADMIN', 'ADMIN', 'USER']) {
      expect(canManageUser('SYS_ADMIN', target)).toBe(true);
    }
  });

  it('lets an Admin manage Users only', () => {
    expect(canManageUser('ADMIN', 'USER')).toBe(true);
    expect(canManageUser('ADMIN', 'ADMIN')).toBe(false);
    expect(canManageUser('ADMIN', 'SYS_ADMIN')).toBe(false);
  });

  it('gives nobody else anything', () => {
    expect(canManageUser('USER', 'USER')).toBe(false);
    expect(canManageUser(undefined, 'USER')).toBe(false);
  });
});
