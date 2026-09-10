/**
 * Audit log — insert-only history of every state-changing action.
 *
 * <p>Backed by the {@code audit_logs} table (SPEC §3.1) which deliberately
 * has no foreign key to {@code applications} (application IDs are never
 * reused) and stores a plain-text name snapshot for human-readable history.
 * The locked action-type enum is defined in SPEC §3.2.
 */
package com.bpl.orderapp.admin.audit;
