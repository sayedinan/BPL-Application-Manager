package com.bpl.orderapp.admin.maintenance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "application_maintenance")
public class ApplicationMaintenance {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "started_by", nullable = false)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    protected ApplicationMaintenance() {
    }

    public ApplicationMaintenance(Long applicationId) {
        this.applicationId = applicationId;
    }

    /** Creates or replaces the window; started_at is when it was (re)scheduled. */
    public void schedule(Instant startsAt, Instant endsAt, String note, String startedBy) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.note = note;
        this.startedBy = startedBy;
        this.startedAt = Instant.now();
    }

    public Long getApplicationId() { return applicationId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public String getNote() { return note; }
    public String getStartedBy() { return startedBy; }
    public Instant getStartedAt() { return startedAt; }
}
