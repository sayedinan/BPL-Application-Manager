import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from '@/api/client';
import { API } from '@/api/endpoints';
import { onLiveStreamState, subscribeHealth } from '@/lib/liveStream';
import type { HealthLatest } from './types';

/** One reading, reduced to the numbers the live graphs draw. null = no value for that check. */
export interface HealthSample {
  t: number;
  cpu: number | null;
  memory: number | null;
  disk: number | null;
  responseMs: number | null;
}

const MAX_SAMPLES = 60;
const POLL_WHEN_OFFLINE_MS = 10000; // no live connection: refresh like before
const POLL_WHEN_LIVE_MS = 30000; // live connection: only a safety net

function toSample(health: HealthLatest): HealthSample {
  // A failed check keeps the last good snapshot on the server, so its
  // resource numbers would repeat. Only count them for a successful check.
  const succeeded = health.reachable === true && health.status != null && health.status !== 'DOWN';
  const resources = health.snapshot?.resources;
  return {
    t: health.checkedAt ? new Date(health.checkedAt).getTime() : Date.now(),
    cpu: succeeded ? (resources?.cpu?.systemPercent ?? resources?.cpu?.processPercent ?? null) : null,
    memory: succeeded ? (resources?.memory?.usedPercent ?? null) : null,
    disk: succeeded ? (resources?.disk?.usedPercent ?? null) : null,
    responseMs: health.responseMs ?? null,
  };
}

/**
 * Live health of one application: the latest reading, the last 60 readings
 * for the graphs, and whether the live connection is up.
 *
 * Readings arrive over the WebSocket the moment each check finishes. A first
 * fetch gives the starting value, and a poll covers the gaps: every 10 s
 * while the live connection is down, every 30 s while it is up. The graphs
 * start filling when the page is opened.
 */
export function useHealthLive(applicationId: number): {
  latest: HealthLatest | null;
  samples: HealthSample[];
  live: boolean;
  loading: boolean;
  error: string | null;
} {
  const [latest, setLatest] = useState<HealthLatest | null>(null);
  const [samples, setSamples] = useState<HealthSample[]>([]);
  const [live, setLive] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const liveRef = useRef(false);
  const lastCheckedRef = useRef<string | null>(null);
  const lastFetchRef = useRef(0);

  const apply = useCallback((health: HealthLatest) => {
    setLatest(health);
    if (health.checkedAt && health.checkedAt !== lastCheckedRef.current) {
      lastCheckedRef.current = health.checkedAt;
      setSamples((previous) => [...previous, toSample(health)].slice(-MAX_SAMPLES));
    }
  }, []);

  useEffect(() => {
    liveRef.current = live;
  }, [live]);

  useEffect(() => onLiveStreamState(setLive), []);

  useEffect(() => {
    setLatest(null);
    setSamples([]);
    setError(null);
    setLoading(true);
    lastCheckedRef.current = null;

    let cancelled = false;
    async function fetchLatest() {
      lastFetchRef.current = Date.now();
      try {
        const health = await api.get<HealthLatest>(API.APPLICATIONS.HEALTH(applicationId));
        if (cancelled) return;
        apply(health);
        setError(null);
      } catch (err) {
        if (!cancelled) setError(err instanceof ApiError ? err.message : 'Failed to load health data.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    void fetchLatest();
    const stopStream = subscribeHealth(applicationId, (health) => {
      if (cancelled) return;
      apply(health);
      setError(null);
      setLoading(false);
    });
    const timer = setInterval(() => {
      const due = liveRef.current ? POLL_WHEN_LIVE_MS : POLL_WHEN_OFFLINE_MS;
      if (Date.now() - lastFetchRef.current >= due - 500) void fetchLatest();
    }, 5000);

    return () => {
      cancelled = true;
      clearInterval(timer);
      stopStream();
    };
  }, [applicationId, apply]);

  return { latest, samples, live, loading, error };
}
