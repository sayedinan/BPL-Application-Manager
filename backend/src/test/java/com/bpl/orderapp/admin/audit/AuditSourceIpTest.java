package com.bpl.orderapp.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bpl.orderapp.admin.application.ApplicationRepository;
import com.bpl.orderapp.admin.ratelimit.ClientAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

/** The audit log must not record an address that the client chose. */
class AuditSourceIpTest {

    private final AuditLogRepository repo = mock(AuditLogRepository.class);

    private String recordedIp(boolean trustForwardedFor) {
        AuditWriter writer = new AuditWriter(repo, mock(ApplicationRepository.class),
            new ClientAddress(trustForwardedFor));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "10.9.9.9, 203.0.113.5");

        writer.write("LOGIN", "alice", "ADMIN", null, null, null, Map.of(), "SUCCESS", request);

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(saved.capture());
        return saved.getValue().getSourceIp();
    }

    @Test
    void theForwardedHeaderIsIgnoredWhenThereIsNoTrustedProxy() {
        assertThat(recordedIp(false)).isEqualTo("198.51.100.7");
    }

    @Test
    void behindATrustedProxyTheHopTheProxyAddedIsRecorded() {
        assertThat(recordedIp(true)).isEqualTo("203.0.113.5");
    }
}
