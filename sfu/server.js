import http from "node:http";
import { WebSocketServer } from "ws";
import * as mediasoup from "mediasoup";

const PORT = Number(process.env.SFU_PORT || 8400);
const API = process.env.DJANGO_INTERNAL_URL || "http://127.0.0.1:8100";
const ANNOUNCED_ADDRESS = process.env.SFU_ANNOUNCED_ADDRESS;
const MIN_PORT = Number(process.env.SFU_MIN_PORT || 40000);
const MAX_PORT = Number(process.env.SFU_MAX_PORT || 40199);
if (!ANNOUNCED_ADDRESS) throw new Error("SFU_ANNOUNCED_ADDRESS is required");

const workers = [];
const rooms = new Map();
let nextWorker = 0;
const mediaCodecs = [{
  kind: "audio", mimeType: "audio/opus", clockRate: 48000, channels: 2,
  parameters: { useinbandfec: 1, minptime: 10 }
}];

for (let i = 0; i < Math.max(1, Number(process.env.SFU_WORKERS || 1)); i++) {
  const worker = await mediasoup.createWorker({
    logLevel: process.env.SFU_LOG_LEVEL || "warn",
    rtcMinPort: MIN_PORT, rtcMaxPort: MAX_PORT
  });
  worker.on("died", () => { console.error("mediasoup worker died"); process.exit(1); });
  workers.push(worker);
}

async function authenticate(request, code) {
  const authorization = request.headers.authorization || "";
  if (!authorization.startsWith("Bearer ")) throw new Error("unauthorized");
  const response = await fetch(`${API}/api/v1/community/rooms/${encodeURIComponent(code)}/`, {
    headers: {
      Authorization: authorization,
      // The internal hop terminates on loopback behind the same trusted proxy boundary.
      "X-Forwarded-Proto": "https"
    }
  });
  if (!response.ok) throw new Error("unauthorized");
  const room = await response.json();
  if (!room.membership || !room.active_call) throw new Error("no_active_call");
  const accountId = String(room.membership.id);
  const joined = (room.active_call.participants || []).some(
    participant => String(participant.id) === accountId
  );
  if (!joined) throw new Error("not_in_call");
  return { account: room.membership, call: room.active_call };
}

function logEvent(event, details = {}) {
  console.log(JSON.stringify({ event, ...details, timestamp: new Date().toISOString() }));
}

async function getRoom(code) {
  let room = rooms.get(code);
  if (room) return room;
  const worker = workers[nextWorker++ % workers.length];
  const router = await worker.createRouter({ mediaCodecs });
  room = { code, router, peers: new Map() };
  rooms.set(code, room);
  return room;
}

function closePeer(room, peer) {
  if (peer.closed) return;
  peer.closed = true;
  for (const consumer of peer.consumers.values()) consumer.close();
  for (const producer of peer.producers.values()) producer.close();
  for (const transport of peer.transports.values()) transport.close();
  if (room.peers.get(peer.id) === peer) room.peers.delete(peer.id);
  if (!room.peers.size) {
    room.router.close();
    rooms.delete(room.code);
  }
}

function reply(socket, id, data, error) {
  if (socket.readyState === 1)
    socket.send(JSON.stringify(error ? { id, error } : { id, data }));
}
function broadcast(room, except, event) {
  const text = JSON.stringify(event);
  for (const peer of room.peers.values())
    if (peer !== except && peer.socket.readyState === 1) peer.socket.send(text);
}

const server = http.createServer((request, response) => {
  if (request.url === "/health") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ status: "ok", workers: workers.length, rooms: rooms.size }));
  } else { response.writeHead(404); response.end(); }
});
const websocket = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });
server.on("upgrade", async (request, socket, head) => {
  try {
    const url = new URL(request.url, "http://localhost");
    if (url.pathname !== "/sfu/") throw new Error("not_found");
    const code = (url.searchParams.get("room") || "").toUpperCase();
    if (!/^[A-Z0-9]{6}$/.test(code)) throw new Error("invalid_room");
    const identity = await authenticate(request, code);
    websocket.handleUpgrade(request, socket, head, ws =>
      websocket.emit("connection", ws, { code, identity }));
  } catch (error) {
    logEvent("sfu_auth_rejected", {
      room: new URL(request.url, "http://localhost").searchParams.get("room") || "",
      reason: error instanceof Error ? error.message : "unknown"
    });
    socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
    socket.destroy();
  }
});

websocket.on("connection", async (socket, context) => {
  const room = await getRoom(context.code);
  const id = String(context.identity.account.id);
  const old = room.peers.get(id);
  const peer = {
    id, socket, closed: false,
    transports: new Map(), producers: new Map(), consumers: new Map()
  };
  room.peers.set(id, peer);
  logEvent("sfu_peer_connected", { room: context.code, account_id: id });
  if (old) { old.socket.close(4001, "replaced"); closePeer(room, old); }
  socket.on("close", (code, reason) => {
    logEvent("sfu_peer_disconnected", {
      room: context.code,
      account_id: id,
      code,
      reason: reason.toString()
    });
    closePeer(room, peer);
  });
  socket.on("error", error => logEvent("sfu_peer_error", {
    room: context.code,
    account_id: id,
    reason: error instanceof Error ? error.message : "unknown"
  }));
  socket.on("message", async raw => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { return; }
    const { id: requestId, action, data = {} } = message;
    try {
      if (action === "routerCapabilities")
        return reply(socket, requestId, room.router.rtpCapabilities);
      if (action === "listProducers") {
        const values = [];
        for (const other of room.peers.values()) if (other !== peer)
          for (const producer of other.producers.values())
            values.push({ producerId: producer.id, accountId: other.id, kind: producer.kind });
        return reply(socket, requestId, values);
      }
      if (action === "createTransport") {
        const transport = await room.router.createWebRtcTransport({
          listenInfos: [
            { protocol: "udp", ip: "0.0.0.0", announcedAddress: ANNOUNCED_ADDRESS },
            { protocol: "tcp", ip: "0.0.0.0", announcedAddress: ANNOUNCED_ADDRESS }
          ],
          enableUdp: true, enableTcp: true, preferUdp: true,
          initialAvailableOutgoingBitrate: 300000
        });
        peer.transports.set(transport.id, transport);
        transport.on("dtlsstatechange", state => { if (state === "closed") transport.close(); });
        return reply(socket, requestId, {
          id: transport.id, iceParameters: transport.iceParameters,
          iceCandidates: transport.iceCandidates, dtlsParameters: transport.dtlsParameters,
          sctpParameters: transport.sctpParameters
        });
      }
      const transport = peer.transports.get(data.transportId);
      if (action === "connectTransport") {
        if (!transport) throw new Error("transport_not_found");
        await transport.connect({ dtlsParameters: data.dtlsParameters });
        return reply(socket, requestId, {});
      }
      if (action === "produce") {
        if (!transport) throw new Error("transport_not_found");
        const producer = await transport.produce({
          kind: data.kind, rtpParameters: data.rtpParameters,
          appData: { accountId: peer.id }
        });
        peer.producers.set(producer.id, producer);
        producer.on("transportclose", () => peer.producers.delete(producer.id));
        broadcast(room, peer, { event: "newProducer",
          data: { producerId: producer.id, accountId: peer.id, kind: producer.kind } });
        return reply(socket, requestId, { id: producer.id });
      }
      if (action === "consume") {
        if (!transport) throw new Error("transport_not_found");
        if (!room.router.canConsume({ producerId: data.producerId,
                                     rtpCapabilities: data.rtpCapabilities }))
          throw new Error("cannot_consume");
        let producerAccountId = "";
        for (const owner of room.peers.values())
          if (owner.producers.has(data.producerId)) { producerAccountId = owner.id; break; }
        const consumer = await transport.consume({
          producerId: data.producerId, rtpCapabilities: data.rtpCapabilities, paused: true,
          appData: { accountId: producerAccountId }
        });
        peer.consumers.set(consumer.id, consumer);
        consumer.on("transportclose", () => peer.consumers.delete(consumer.id));
        consumer.on("producerclose", () => {
          peer.consumers.delete(consumer.id);
          if (socket.readyState === 1) socket.send(JSON.stringify({
            event: "producerClosed", data: { consumerId: consumer.id, producerId: data.producerId }
          }));
        });
        return reply(socket, requestId, {
          id: consumer.id, producerId: consumer.producerId, kind: consumer.kind,
          rtpParameters: consumer.rtpParameters,
          accountId: consumer.appData.accountId || ""
        });
      }
      if (action === "resumeConsumer") {
        const consumer = peer.consumers.get(data.consumerId);
        if (!consumer) throw new Error("consumer_not_found");
        await consumer.resume();return reply(socket, requestId, {});
      }
      throw new Error("unknown_action");
    } catch (error) { reply(socket, requestId, null, String(error.message || error)); }
  });
});
server.listen(PORT, "127.0.0.1", () => console.log(`SFU listening on ${PORT}`));

let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`SFU shutting down after ${signal}`);
  websocket.close();
  for (const room of rooms.values()) {
    for (const peer of room.peers.values()) peer.socket.terminate();
    room.router.close();
  }
  rooms.clear();
  for (const worker of workers) worker.close();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 10000).unref();
}
process.once("SIGTERM", () => shutdown("SIGTERM"));
process.once("SIGINT", () => shutdown("SIGINT"));
