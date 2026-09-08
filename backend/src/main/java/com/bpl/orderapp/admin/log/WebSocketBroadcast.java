package com.bpl.orderapp.admin.log;
import org.springframework.messaging.simp.SimpMessagingTemplate;
public class WebSocketBroadcast {
    private final SimpMessagingTemplate template;
    public WebSocketBroadcast(SimpMessagingTemplate template) { this.template = template; }
    // §7.2 point 7 / §5.2: broadcast new lines to /topic/application-logs/{appId}
    public void broadcast(Long appId, String content, long lineNumber, String capturedAt) {
        template.convertAndSend("/topic/application-logs/" + appId,
            new LogLineMessage(lineNumber, content, capturedAt));
    }
    public static record LogLineMessage(long lineNumber, String content, String capturedAt) {}
}
