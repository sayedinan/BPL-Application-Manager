package com.bpl.orderapp.admin.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sms_send_log")
public class SmsSendLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable in the schema (a foreign key to applications).
    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "recipient_phone_number", nullable = false, length = 20)
    private String recipientPhoneNumber;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "is_error", nullable = false)
    private boolean error;

    @Column(name = "inserted_sms_id", length = 50)
    private String insertedSmsId;

    @Column(name = "response_message")
    private String responseMessage;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected SmsSendLog() {
    }

    public SmsSendLog(Long applicationId, String recipientPhoneNumber, String eventType,
                      boolean error, String insertedSmsId, String responseMessage) {
        this.applicationId = applicationId;
        this.recipientPhoneNumber = recipientPhoneNumber;
        this.eventType = eventType;
        this.error = error;
        this.insertedSmsId = insertedSmsId;
        this.responseMessage = responseMessage;
        this.sentAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public String getRecipientPhoneNumber() { return recipientPhoneNumber; }
    public String getEventType() { return eventType; }
    public boolean isError() { return error; }
    public String getInsertedSmsId() { return insertedSmsId; }
    public String getResponseMessage() { return responseMessage; }
    public Instant getSentAt() { return sentAt; }
}
