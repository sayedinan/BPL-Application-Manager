/**
 * Application health monitoring (HEALTH-MONITORING.md) — polls each
 * application's own health endpoint and turns the response into
 * online/offline transitions, audit rows, notifications and the data
 * behind the dashboard health card.
 *
 * <p>Applications answer in one of two formats: the standard contract
 * ({@code HEALTH_CONTRACT.md}) or Spring Boot Actuator health JSON.
 * Both are normalized into a {@link HealthSnapshot} at the edge, so
 * nothing downstream needs to know which format an application speaks.
 */
package com.bpl.orderapp.admin.health;
