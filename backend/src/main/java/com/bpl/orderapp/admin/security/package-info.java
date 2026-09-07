/**
 * Security wiring — Spring Security filter chain, session-cookie policy
 * (SPEC §8.1), method-level {@code @PreAuthorize} configuration (SPEC §8.2),
 * and the custom {@code canAccessApplication(id)} expression used to gate
 * per-application access for the USER role.
 *
 * <p>Authentication endpoints live in {@code com.bpl.orderapp.admin.auth};
 * this package owns the filter chain, the SecurityExpressionRoot
 * extension, and the bcrypt/HttpOnly cookie configuration only.
 */
package com.bpl.orderapp.admin.security;
