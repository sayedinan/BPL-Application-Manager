import { Client, type StompSubscription } from '@stomp/stompjs';
import type { HealthLatest } from './types';

/**
 * Live health readings over the app's WebSocket (STOMP).
 *
 * The backend pushes an application's latest health to
 * /topic/application-health/{id} after every check, in the same shape as
 * GET /applications/{id}/health. This module owns the one shared connection
 * for the whole page: any number of components can watch any number of
 * applications, subscriptions are counted so each topic is joined once, and
 * the connection is opened with the first watcher and closed with the last.
 * After a dropped connection the library reconnects and every watched topic
 * is joined again.
 */

type Handler = (health: HealthLatest) => void;
type StateListener = (connected: boolean) => void;

const handlers = new Map<number, Set<Handler>>();
const subscriptions = new Map<number, StompSubscription>();
const stateListeners = new Set<StateListener>();
let client: Client | null = null;
let connected = false;

function setConnected(value: boolean): void {
  connected = value;
  stateListeners.forEach((listener) => listener(value));
}

function joinTopic(applicationId: number): void {
  if (!client || !client.connected || subscriptions.has(applicationId)) return;
  subscriptions.set(
    applicationId,
    client.subscribe(`/topic/application-health/${applicationId}`, (message) => {
      let health: HealthLatest;
      try {
        health = JSON.parse(message.body) as HealthLatest;
      } catch {
        return; // malformed frame: the next one or the safety-net poll fixes it
      }
      handlers.get(applicationId)?.forEach((handler) => handler(health));
    }),
  );
}

function ensureClient(): void {
  if (client) return;
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const created = new Client({
    brokerURL: `${protocol}//${window.location.host}/ws`,
    reconnectDelay: 5000,
    debug: () => {},
  });
  created.onConnect = () => {
    subscriptions.clear(); // a new connection has no subscriptions yet
    setConnected(true);
    handlers.forEach((_, applicationId) => joinTopic(applicationId));
  };
  created.onWebSocketClose = () => {
    subscriptions.clear();
    setConnected(false);
  };
  client = created;
  created.activate();
}

/** Calls handler with every new reading of the application. Returns the function that stops it. */
export function subscribeHealth(applicationId: number, handler: Handler): () => void {
  let set = handlers.get(applicationId);
  if (!set) {
    set = new Set();
    handlers.set(applicationId, set);
  }
  set.add(handler);
  ensureClient();
  joinTopic(applicationId); // does nothing until connected; onConnect joins it then

  return () => {
    const current = handlers.get(applicationId);
    if (!current) return;
    current.delete(handler);
    if (current.size > 0) return;
    handlers.delete(applicationId);
    try {
      subscriptions.get(applicationId)?.unsubscribe();
    } catch {
      // connection already gone; nothing to leave
    }
    subscriptions.delete(applicationId);
    if (handlers.size === 0 && client) {
      void client.deactivate();
      client = null;
      setConnected(false);
    }
  };
}

/** Tells the listener whether the live connection is up, now and on every change. */
export function onHealthStreamState(listener: StateListener): () => void {
  stateListeners.add(listener);
  listener(connected);
  return () => {
    stateListeners.delete(listener);
  };
}
