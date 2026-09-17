package com.bpl.orderapp.admin.log;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
@Component
public class WebSocketBroadcast {
    private final SimpMessagingTemplate template;
    public WebSocketBroadcast(SimpMessagingTemplate template) { this.template = template; }
    // §7.2 point 7 / §5.2: broadcast new lines to /topic/application-logs/{appId}
    public void broadcast(Long appId, String content, long lineNumber, String capturedAt) {
        template.convertAndSend("/topic/application-logs/" + appId,
            new LogLineMessage(lineNumber, content, capturedAt));
    }
    public static record LogLineMessage(long lineNumber, String content, String capturedAt) {}

    // STATUS-REDESIGN.md §2/§5: one global topic, not per-application —
    // the dashboard shows every card at once, so a single subscription
    // covering all applications is simpler than per-app subscribe
    // bookkeeping. Fired only on an actual online/offline flip, not on
    // every poll tick.
    public void broadcastStatus(Long appId, boolean online, String transitionedAt) {
        template.convertAndSend("/topic/application-status",
            new StatusMessage(appId, online, transitionedAt));
    }
    public static record StatusMessage(Long applicationId, boolean online, String transitionedAt) {}
}
