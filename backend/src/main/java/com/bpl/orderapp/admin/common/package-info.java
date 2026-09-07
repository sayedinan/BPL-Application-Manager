/**
 * Cross-cutting types shared across feature packages.
 *
 * <p>Owns the RFC 7807 {@code ProblemDetail} error envelope and the error
 * code constants from SPEC §4.2 ({@code ACCESS_DENIED},
 * {@code APPLICATION_ALREADY_RUNNING}, etc.), plus any shared DTOs and
 * exception types. Keeping these here avoids circular dependencies between
 * feature packages.
 */
package com.bpl.orderapp.admin.common;
