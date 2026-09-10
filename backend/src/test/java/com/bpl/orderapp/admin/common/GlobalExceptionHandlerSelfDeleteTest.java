package com.bpl.orderapp.admin.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.bpl.orderapp.admin.accountDeletion.SelfDeleteForbiddenException;

/**
 * Tests for the {@code SELF_DELETE_FORBIDDEN} mapping in
 * {@link GlobalExceptionHandler}. The rest of the exception
 * handlers are covered indirectly by the controller slice
 * tests; this one is its own focused test because
 * {@link SelfDeleteForbiddenException} is the only exception
 * the user-deletion service will throw, and the response
 * envelope matters (it's surfaced in the SPEC's error code
 * table).
 */
class GlobalExceptionHandlerSelfDeleteTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void selfDeleteForbidden_mapsTo403WithSELF_DELETE_FORBIDDENCode() {
        ResponseEntity<ProblemDetail> response = handler.handleSelfDeleteForbidden(
            new SelfDeleteForbiddenException(42L, 42L)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(403);
        // The SPEC §4.2 code, exact.
        assertThat(body.getProperties()).containsEntry("code", "SELF_DELETE_FORBIDDEN");
        // Generic detail — the response should NOT echo
        // the caller's or target's id.
        assertThat(body.getDetail()).doesNotContain("42");
        // The media type is RFC 7807.
        assertThat(response.getHeaders().getContentType())
            .isNotNull()
            .matches(t -> "application/problem+json".equals(t.toString()));
    }
}
