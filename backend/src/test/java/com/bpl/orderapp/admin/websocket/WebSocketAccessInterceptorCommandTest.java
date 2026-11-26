package com.bpl.orderapp.admin.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bpl.orderapp.admin.audit.AuditWriter;
import com.bpl.orderapp.admin.user.UserApplicationAssignmentRepository;
import com.bpl.orderapp.admin.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.session.FindByIndexNameSessionRepository;

/** Only the STOMP commands the app needs get through; everything else is refused. */
@SuppressWarnings("unchecked")
class WebSocketAccessInterceptorCommandTest {

    private final WebSocketConnectionRepository connections = mock(WebSocketConnectionRepository.class);

    private final WebSocketAccessInterceptor interceptor = new WebSocketAccessInterceptor(
        mock(FindByIndexNameSessionRepository.class), mock(AuditWriter.class),
        mock(ApplicationEventPublisher.class), mock(UserRepository.class),
        mock(UserApplicationAssignmentRepository.class), connections,
        mock(WebSocketSubscriptionRepository.class));

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("s1");
        accessor.setUser(() -> "alice");
        if (destination != null) accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class,
        names = {"MESSAGE", "BEGIN", "COMMIT", "ABORT", "CONNECTED", "RECEIPT", "ERROR"})
    void otherCommandsAreRefused(StompCommand command) {
        assertThatThrownBy(() -> interceptor.preSend(frame(command, "/topic/audit-log"), null))
            .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void sendIsOnlyAllowedToTheApplicationPrefix() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/topic/audit-log"), null))
            .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/apptopic"), null))
            .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void theStompCommandIsTreatedAsConnect() {
        interceptor.preSend(frame(StompCommand.STOMP, null), null);

        verify(connections).save(any());
    }

    @Test
    void acknowledgementsAndHeartbeatsPass() {
        for (StompCommand command : new StompCommand[] {StompCommand.ACK, StompCommand.NACK}) {
            Message<byte[]> message = frame(command, null);
            assertThat(interceptor.preSend(message, null)).isSameAs(message);
        }
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());
        assertThat(heartbeat.getMessageType()).isEqualTo(SimpMessageType.HEARTBEAT);
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }
}
