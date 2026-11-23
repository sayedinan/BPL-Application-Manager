package com.bpl.orderapp.admin.audit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bpl.orderapp.admin.application.ApplicationRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * audit_logs.action_type has no DB CHECK any more (SQLite migration), so
 * AuditWriter is the only guard against storing an unknown action.
 */
class AuditWriterActionGuardTest {

    private final AuditLogRepository repo = mock(AuditLogRepository.class);
    private final AuditWriter writer = new AuditWriter(repo, mock(ApplicationRepository.class));

    @Test
    void unknownActionIsRejectedAndNothingIsSaved() {
        assertThrows(IllegalArgumentException.class, () ->
                writer.write("NOT_A_REAL_ACTION", "alice", "ADMIN", null, null, null, Map.of(), "SUCCESS"));
        verify(repo, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "START_APPLICATION", "STOP_APPLICATION", "CREATE_APPLICATION", "UPDATE_APPLICATION",
        "DELETE_APPLICATION", "ASSIGN_APPLICATION", "CREATE_USER", "CREATE_ADMIN", "UPDATE_USER",
        "DELETE_USER", "RESET_PASSWORD", "CHANGE_PASSWORD", "LOGIN", "LOGOUT",
        "SHELLCHECK_UNAVAILABLE", "APPLICATION_ONLINE", "APPLICATION_OFFLINE"})
    void everyKnownActionIsSaved(String action) {
        writer.write(action, "alice", "ADMIN", null, null, null, Map.of(), "SUCCESS");
        verify(repo).save(any(AuditLog.class));
    }
}
