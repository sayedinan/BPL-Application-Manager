/**
 * Spring {@code @Configuration} classes — CORS, Jasypt, async executors,
 * Bucket4j rate-limit setup, and any other infrastructure beans that are
 * not specific to a single feature.
 *
 * <p>Feature-specific configuration lives inside its feature package
 * (e.g. WebSocket broker config is in {@code com.bpl.orderapp.admin.websocket}).
 * This package is reserved for the truly cross-cutting infrastructure.
 */
package com.bpl.orderapp.admin.config;
