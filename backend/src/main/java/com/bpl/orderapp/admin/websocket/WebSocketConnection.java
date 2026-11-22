package com.bpl.orderapp.admin.websocket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "websocket_connections")
public class WebSocketConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "username", nullable = false, length = 128)
    private String username;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    protected WebSocketConnection() {
    }

    public WebSocketConnection(String sessionId, String username) {
        this.sessionId = sessionId;
        this.username = username;
        this.connectedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getUsername() { return username; }
    public Instant getConnectedAt() { return connectedAt; }
}
