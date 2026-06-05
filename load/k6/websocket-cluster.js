// WebSocket capacity scenario: open a large fleet of STOMP-over-WebSocket subscribers against the cluster
// (behind the round-robin LB), hold them open, and measure connect success, per-node spread, and broadcast
// fan-out. This stresses the dimension a single instance can't carry — 1000s of concurrent WS sessions —
// proving the cluster distributes them and keeps delivering events.
//
// Talks raw STOMP to the non-SockJS endpoint registered at addEndpoint("/ws") (no SockJS framing needed).
// Auth is optional: the WebSocketAuthInterceptor allows anonymous read-only connections, which is exactly
// what a "can it hold N subscribers + fan out broadcasts" test needs. Pass TOKEN to connect authenticated.
//
// Env knobs (all optional):
//   BASE_URL        http base of the LB (default http://localhost:8092) — ws:// is derived from it
//   CONNECTIONS     target concurrent WS connections (VUs). Default 500. Push toward 10000 on real hardware.
//   RAMP            ramp-up duration to reach CONNECTIONS (default 60s)
//   HOLD            how long to hold the fleet open at full size (default 120s)
//   TOURNAMENT_ID   if set, each VU subscribes to that tournament's topics (gets real broadcasts)
//   TABLES          number of tables to spread table/shard subscriptions over (default 1000)
//   SHARD_COUNT     app.tournament.shard-count on the server (default 16)
//   TOKEN           optional JWT; when set, sent as `Authorization: Bearer` on the STOMP CONNECT frame
//
// Example: CONNECTIONS=10000 RAMP=120s HOLD=300s BASE_URL=http://localhost:8092 k6 run load/k6/websocket-cluster.js

import ws from 'k6/ws';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const CONNECTIONS = parseInt(__ENV.CONNECTIONS || '500', 10);
const RAMP = __ENV.RAMP || '60s';
const HOLD = __ENV.HOLD || '120s';
const TOURNAMENT_ID = __ENV.TOURNAMENT_ID || '';
const TABLES = parseInt(__ENV.TABLES || '1000', 10);
const SHARD_COUNT = parseInt(__ENV.SHARD_COUNT || '16', 10);
const TOKEN = __ENV.TOKEN || '';

const NUL = String.fromCharCode(0); // STOMP frame terminator (0x00)

const wsConnectOk = new Rate('ws_connect_success');
const wsStompConnected = new Rate('ws_stomp_connected');
const wsConnectTime = new Trend('ws_connect_time', true);
const wsMessages = new Counter('ws_messages_received');
const wsErrors = new Counter('ws_errors');

function wsUrl() {
  const base = (__ENV.BASE_URL || 'http://localhost:8092').replace(/\/$/, '');
  return `${base.replace(/^http/, 'ws')}/api/ws`;
}

function connectFrame() {
  let frame = 'CONNECT\naccept-version:1.2\nheart-beat:0,0\n';
  if (TOKEN) {
    frame += `Authorization:Bearer ${TOKEN}\n`;
  }
  return `${frame}\n${NUL}`;
}

function subscribeFrame(id, destination) {
  return `SUBSCRIBE\nid:${id}\ndestination:${destination}\n\n${NUL}`;
}

// Destinations this VU subscribes to: the tournament-wide topic (every broadcast) plus one table + its shard
// topic, spread across the field so the per-table fan-out is exercised, not just one hot topic.
function destinationsFor(vu) {
  if (!TOURNAMENT_ID) {
    return [];
  }
  const tableNumber = (vu % TABLES) + 1;
  const shard = (tableNumber - 1) % SHARD_COUNT;
  return [
    `/topic/tournament/${TOURNAMENT_ID}`,
    `/topic/tournament/${TOURNAMENT_ID}/table/${tableNumber}`,
    `/topic/tournament/${TOURNAMENT_ID}/shard/${shard}`,
  ];
}

export const options = {
  scenarios: {
    ws_fleet: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: CONNECTIONS },
        { duration: HOLD, target: CONNECTIONS },
      ],
      gracefulStop: '10s',
    },
  },
  thresholds: {
    ws_connect_success: ['rate>0.95'],
    ws_stomp_connected: ['rate>0.95'],
    ws_errors: ['count<' + Math.ceil(CONNECTIONS * 0.05)],
  },
};

export default function () {
  const url = wsUrl();
  const subs = destinationsFor(__VU);
  const startedAt = Date.now();

  const res = ws.connect(url, {}, function (socket) {
    socket.on('open', function () {
      wsConnectOk.add(true);
      socket.send(connectFrame());
    });

    socket.on('message', function (data) {
      // A WS frame may carry several STOMP frames and/or heartbeat newlines; split on the NUL terminator.
      const frames = String(data).split(NUL);
      for (const raw of frames) {
        const frame = raw.replace(/^\n+/, '');
        if (frame.length === 0) {
          continue;
        }
        const command = frame.split('\n', 1)[0];
        if (command === 'CONNECTED') {
          wsStompConnected.add(true);
          wsConnectTime.add(Date.now() - startedAt);
          subs.forEach((dest, i) => socket.send(subscribeFrame(`sub-${__VU}-${i}`, dest)));
          // Hold the connection open for the configured window, then close cleanly.
          socket.setTimeout(() => socket.close(), durationMs(HOLD));
        } else if (command === 'MESSAGE') {
          wsMessages.add(1);
        } else if (command === 'ERROR') {
          wsErrors.add(1);
        }
      }
    });

    socket.on('error', function () {
      wsErrors.add(1);
    });
  });

  const ok = check(res, { 'ws handshake 101': (r) => r && r.status === 101 });
  if (!ok) {
    wsConnectOk.add(false);
    wsErrors.add(1);
  }
}

function durationMs(s) {
  const m = String(s).match(/^(\d+)(ms|s|m)?$/);
  if (!m) {
    return 120000;
  }
  const n = parseInt(m[1], 10);
  switch (m[2]) {
    case 'ms': return n;
    case 'm': return n * 60000;
    default: return n * 1000;
  }
}
