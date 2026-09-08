
import { useEffect, useRef, useState } from 'react';

import { Client } from '@stomp/stompjs';

export function LogsBox({ appId, role }: { appId: number; role?: string }) {

  const [lines, setLines] = useState<string[]>([]);

  const [autoFollow, setAutoFollow] = useState(true);

  const clientRef = useRef<Client | null>(null);

  useEffect(() => {

    // Derive ws(s)://<current-host>/ws from the page's own origin instead of

    // hardcoding localhost, so this works both in dev and on the deployed

    // server. The HttpOnly session cookie rides along automatically on the

    // WebSocket handshake since it's same-origin - no manual Cookie header

    // needed (and browsers ignore JS-set Cookie headers anyway).

    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';

    const brokerURL = `${wsProtocol}//${window.location.host}/ws`;

    const client = new Client({ brokerURL, debug: () => {} });

    client.onConnect = () => client.subscribe(`/topic/application-logs/${appId}`, msg => {

      try { const d = JSON.parse(msg.body); setLines(prev => [...prev, `${d.lineNumber}: ${d.content}`]); } catch { setLines(prev => [...prev, msg.body]); }

    });

    client.activate();

    clientRef.current = client;

    return () => { client.deactivate(); };

  }, [appId]);

  // Switch selection -> unsubscribe old / subscribe new handled by effect cleanup + new activate (§5.3)

  const reconnectDelays = [1000,2000,4000,8000,16000,30000];

  // §5.3 reconnect: exponential backoff 1/2/4/8/16/30s, max 10 attempts, then banner

  const [reconnectAttempt, setReconnectAttempt] = useState(0);

  useEffect(() => {

    if (!clientRef.current || !clientRef.current.connected) {

      const delay = reconnectDelays[Math.min(reconnectAttempt, reconnectDelays.length - 1)];

      const timer = setTimeout(() => { setReconnectAttempt(a => a + 1); clientRef.current?.activate(); }, delay);

      return () => clearTimeout(timer);

    }

  }, [reconnectAttempt]);

  const showBanner = reconnectAttempt >= 10;

  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => { if (autoFollow && scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight; }, [lines, autoFollow]);

  const handleScroll = () => { if (scrollRef.current) { if (scrollRef.current.scrollTop < scrollRef.current.scrollHeight - 50) setAutoFollow(false); } };

  return <div ref={scrollRef} onScroll={handleScroll} style={{overflowY:'auto', height:400}}>

    <select><option>Application Log</option>{(role==="SYS_ADMIN"||role==="ADMIN")&&<option>Audit Log (Admin+ only)</option>}</select>

    <pre>{lines.join('\n')}</pre>{!autoFollow && <button onClick={()=>{setAutoFollow(true); if(scrollRef.current)scrollRef.current.scrollTop=scrollRef.current.scrollHeight}}>↓ Jump to latest</button>}{showBanner && <div role="alert">WebSocket disconnected — reconnect failed after 10 attempts.</div>}</div>;

}

