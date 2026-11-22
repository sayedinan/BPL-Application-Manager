package com.bpl.orderapp.admin.websocket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "websocket_subscriptions")
public class WebSocketSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "subscription_id", nullable = false, length = 128)
    private String subscriptionId;

    // An application id as text, or "audit-log".
    @Column(name = "topic_key", nullable = false, length = 128)
    private String topicKey;

    @Column(name = "subscribed_at", nullable = false)
    private Instant subscribedAt;

    protected WebSocketSubscription() {
    }

    public WebSocketSubscription(String sessionId, String subscriptionId, String topicKey) {
        this.sessionId = sessionId;
        this.subscriptionId = subscriptionId;
        this.topicKey = topicKey;
        this.subscribedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getSubscriptionId() { return subscriptionId; }
    public String getTopicKey() { return topicKey; }
    public Instant getSubscribedAt() { return subscribedAt; }
}
