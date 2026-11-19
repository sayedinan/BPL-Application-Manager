package com.bpl.orderapp.admin.audit;

import org.springframework.data.repository.Repository;

/** Deliberately exposes save only: no update, no delete, ever. */
public interface AuditLogRepository extends Repository<AuditLog, Long> {
    AuditLog save(AuditLog entry);
}