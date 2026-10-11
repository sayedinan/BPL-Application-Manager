/**
 * Application health monitoring (HEALTH-MONITORING.md) — polls each
 * application's own health endpoint and turns the response into
 * online/offline transitions, audit rows, notifications and the data
 * behind the dashboard health card.
 *
 * <p>Applications answer in the standard contract ({@code HEALTH_CONTRACT.md}),
 * which is parsed into a {@link HealthSnapshot}.
 */
package com.bpl.orderapp.admin.health;
