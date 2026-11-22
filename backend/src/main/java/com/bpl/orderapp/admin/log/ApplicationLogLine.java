package com.bpl.orderapp.admin.log;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "application_log_lines")
public class ApplicationLogLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    // Monotonic per application; (application_id, line_number) is unique.
    @Column(name = "line_number", nullable = false)
    private long lineNumber;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected ApplicationLogLine() {
    }

    public ApplicationLogLine(Long applicationId, long lineNumber, String content, Instant capturedAt) {
        this.applicationId = applicationId;
        this.lineNumber = lineNumber;
        this.content = content;
        this.capturedAt = capturedAt;
    }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public long getLineNumber() { return lineNumber; }
    public String getContent() { return content; }
    public Instant getCapturedAt() { return capturedAt; }
}
