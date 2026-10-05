import { Client, type StompSubscription } from '@stomp/stompjs';
import type { HealthLatest } from '@/health/types';

/**
 * The one live (WebSocket / STOMP) connection of this browser tab.
 *
 * PresenceConnection starts it when the user logs in and stops it on logout,
 * so there is exactly one connection per tab whatever page is open (the server
 * allows 10 per user). Any component can then follow what it needs without
 * opening a connection of its own:
 *   - subscribeStatus: an application going Online/Offline
 *   - subscribeHealth: a fresh health reading of an application
 * Each topic is joined once however many components watch it, and left when
 * the last one stops. After a dropped connection the library reconnects and
 * every watched topic is joined again. Watching before the connection is up is
 * fine: the topic is joined as soon as it is.
 *
 * Both topics are per application. The server only lets a viewer join the
 * topics of applications they may see.
 */

/** Sent when an application flips Online/Offline. */
export interface StatusEvent {
  applicationId: number;
  online: boolean;
  transitionedAt: string;
}

type Handler = (payload: unknown) => void;
type StateListener = (connected: boolean) => void;

const handlers = new Map<string, Set<Handler>>();
const subscriptions = new Map<string, StompSubscription>();
const stateListeners = new Set<StateListener>();
let client: Client | null = null;
let connected = false;

function setConnected(value: boolean): void {
  connected = value;
  stateListeners.forEach((listener) => listener(value));
}

function joinTopic(topic: string): void {
  if (!client || !client.connected || subscriptions.has(topic)) return;
  subscriptions.set(
    topic,
    client.subscribe(topic, (message) => {
      let payload: unknown;
      try {
        payload = JSON.parse(message.body);
      } catch {
        return; // malformed frame: the next one or the safety-net poll fixes it
      }
      handlers.get(topic)?.forEach((handler) => handler(payload));
    }),
  );
}

/** Opens the connection (does nothing if it is already open). Called when the user logs in. */
export function startLiveStream(): void {
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
    handlers.forEach((_, topic) => joinTopic(topic));
  };
  created.onWebSocketClose = () => {
    subscriptions.clear();
    setConnected(false);
  };
  client = created;
  created.activate();
}

/** Closes the connection. Called on logout. */
export function stopLiveStream(): void {
  if (!client) return;
  const closing = client;
  client = null;
  subscriptions.clear();
  setConnected(false);
  void closing.deactivate();
}

function watch(topic: string, handler: Handler): () => void {
  let set = handlers.get(topic);
  if (!set) {
    set = new Set();
    handlers.set(topic, set);
  }
  set.add(handler);
  joinTopic(topic); // does nothing until connected; onConnect joins it then

  return () => {
    const current = handlers.get(topic);
    if (!current) return;
    current.delete(handler);
    if (current.size > 0) return;
    handlers.delete(topic);
    try {
      subscriptions.get(topic)?.unsubscribe();
    } catch {
      // connection already gone; nothing to leave
    }
    subscriptions.delete(topic);
  };
}

/** Calls handler every time the application goes Online or Offline. Returns the function that stops it. */
export function subscribeStatus(applicationId: number, handler: (event: StatusEvent) => void): () => void {
  return watch(`/topic/application-status/${applicationId}`, (payload) => handler(payload as StatusEvent));
}

/** Calls handler with every new health reading of the application. Returns the function that stops it. */
export function subscribeHealth(applicationId: number, handler: (health: HealthLatest) => void): () => void {
  return watch(`/topic/application-health/${applicationId}`, (payload) => handler(payload as HealthLatest));
}

/** Tells the listener whether the live connection is up, now and on every change. */
export function onLiveStreamState(listener: StateListener): () => void {
  stateListeners.add(listener);
  listener(connected);
  return () => {
    stateListeners.delete(listener);
  };
}
