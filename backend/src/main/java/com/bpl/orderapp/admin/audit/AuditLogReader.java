package com.bpl.orderapp.admin.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the audit log. Kept apart from AuditLogRepository on purpose:
 * that interface exposes save only, and this class can only ever read.
 */
@Component
public class AuditLogReader {

    private final EntityManager em;

    public AuditLogReader(EntityManager em) {
        this.em = em;
    }

    public record Page(List<AuditLog> items, long total) {
    }

    /** Newest first. Each filter is applied only when its value is non-null. */
    @Transactional(readOnly = true)
    public Page find(Instant from, Instant to, Long applicationId, int page, int size) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<AuditLog> select = cb.createQuery(AuditLog.class);
        Root<AuditLog> selectRoot = select.from(AuditLog.class);
        select.select(selectRoot)
            .where(filters(cb, selectRoot, from, to, applicationId))
            .orderBy(cb.desc(selectRoot.get("timestamp")));
        long offset = Math.min((long) page * size, Integer.MAX_VALUE);
        List<AuditLog> items = em.createQuery(select)
            .setFirstResult((int) offset)
            .setMaxResults(size)
            .getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<AuditLog> countRoot = count.from(AuditLog.class);
        count.select(cb.count(countRoot))
            .where(filters(cb, countRoot, from, to, applicationId));
        long total = em.createQuery(count).getSingleResult();

        return new Page(items, total);
    }

    /** When the application was last started, if ever. */
    @Transactional(readOnly = true)
    public Optional<Instant> lastStartedAt(Long applicationId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Instant> query = cb.createQuery(Instant.class);
        Root<AuditLog> root = query.from(AuditLog.class);
        query.select(root.<Instant>get("timestamp"))
            .where(
                cb.equal(root.get("targetApplicationId"), applicationId),
                cb.equal(root.get("actionType"), "START_APPLICATION"))
            .orderBy(cb.desc(root.get("timestamp")));
        return em.createQuery(query).setMaxResults(1).getResultList().stream().findFirst();
    }

    private static Predicate[] filters(CriteriaBuilder cb, Root<AuditLog> root,
                                       Instant from, Instant to, Long applicationId) {
        List<Predicate> predicates = new ArrayList<>();
        if (from != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("timestamp"), from));
        }
        if (to != null) {
            predicates.add(cb.lessThanOrEqualTo(root.<Instant>get("timestamp"), to));
        }
        if (applicationId != null) {
            predicates.add(cb.equal(root.get("targetApplicationId"), applicationId));
        }
        return predicates.toArray(new Predicate[0]);
    }
}
