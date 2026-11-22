package com.bpl.orderapp.admin.websocket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WebSocketConnectionRepository extends JpaRepository<WebSocketConnection, Long> {

    /** True while the user has at least one live connection (the "online" dot). */
    boolean existsByUsername(String username);

    /** Returns how many rows were removed; 0 means the session was already cleaned up. */
    @Modifying
    @Transactional
    @Query("delete from WebSocketConnection c where c.sessionId = :sessionId")
    int deleteAllBySession(@Param("sessionId") String sessionId);
}
