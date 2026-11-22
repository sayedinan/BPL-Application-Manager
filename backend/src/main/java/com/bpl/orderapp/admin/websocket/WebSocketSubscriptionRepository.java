package com.bpl.orderapp.admin.websocket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface WebSocketSubscriptionRepository extends JpaRepository<WebSocketSubscription, Long> {

    long countByTopicKey(String topicKey);

    @Query("select s.topicKey from WebSocketSubscription s "
         + "where s.sessionId = :sessionId and s.subscriptionId = :subscriptionId")
    List<String> findTopicKeys(@Param("sessionId") String sessionId,
                               @Param("subscriptionId") String subscriptionId);

    @Query("select s.topicKey from WebSocketSubscription s where s.sessionId = :sessionId")
    List<String> findTopicKeysBySession(@Param("sessionId") String sessionId);

    @Modifying
    @Transactional
    @Query("delete from WebSocketSubscription s "
         + "where s.sessionId = :sessionId and s.subscriptionId = :subscriptionId")
    int deleteAllBySubscription(@Param("sessionId") String sessionId,
                                @Param("subscriptionId") String subscriptionId);

    @Modifying
    @Transactional
    @Query("delete from WebSocketSubscription s where s.sessionId = :sessionId")
    int deleteAllBySession(@Param("sessionId") String sessionId);
}
