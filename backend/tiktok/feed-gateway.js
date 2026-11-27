// Isolated read-only public guest-feed gateway. Node >=22, no npm deps.
// Bind only 127.0.0.1; a TLS reverse proxy with per-IP rate limiting must
// terminate public connections. Never expose upstream internal endpoints.
"use strict";
const http = require("node:http");

const HOST = "127.0.0.1";
const PORT = Number(process.env.EZTIK_GATEWAY_PORT || "39118");
const UPSTREAM = "http://127.0.0.1:39117/tiktok/trending?count=16";
const MAX_BODY = 4_000_000;
const VIDEO_HOSTS = [
  "tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com",
  "byteoversea.com", "ibytedtos.com", "musical.ly"
];

function allowedVideoUrl(raw) {
  if (typeof raw !== "string" || raw.length > 4096) return false;
  try {
    const uri = new URL(raw);
    const host = uri.hostname.toLowerCase();
    return uri.protocol === "https:" && !uri.username && !uri.password &&
      (!uri.port || uri.port === "443") &&
      VIDEO_HOSTS.some(h => host === h || host.endsWith("." + h));
  } catch { return false; }
}

function videoCandidates(video) {
  if (!video || typeof video !== "object") return [];
  const out = [];
  for (const bitrate of (Array.isArray(video.bitrateInfo) ? video.bitrateInfo : []).slice(0, 6)) {
    const addr = bitrate?.PlayAddr || bitrate?.playAddr;
    for (const u of (addr?.UrlList || addr?.urlList || []).slice(0, 4)) out.push(u);
  }
  const play = video.playAddr || video.play_addr;
  if (typeof play === "string") out.push(play);
  else if (play && typeof play === "object") {
    for (const u of (play.urlList || play.UrlList || []).slice(0, 4)) out.push(u);
  }
  return [...new Set(out)].filter(allowedVideoUrl).sort((a,b) => {
    const score = s => new URL(s).pathname.includes("/aweme/v1/play/") ? 0
      : s.includes("webapp-prime") ? 2 : 1;
    return score(a) - score(b);
  });
}

function normalize(body) {
  const items = body?.itemList || body?.item_list;
  if (!Array.isArray(items)) throw new Error("Upstream did not return itemList");
  const seen = new Set();
  const result = [];
  for (const item of items.slice(0, 50)) {
    const id = String(item?.id || "");
    if (!/^\d{8,30}$/.test(id) || seen.has(id)) continue;
    if (Array.isArray(item.imagePost?.images) && item.imagePost.images.length) continue;
    const url = videoCandidates(item.video)[0];
    if (!url) continue;
    seen.add(id);
    result.push({id, desc: String(item.desc || "TikTok").slice(0, 160),
      video: {playAddr: url}});
    if (result.length === 16) break;
  }
  if (!result.length) throw new Error("Upstream has no valid video URLs");
  return {itemList: result};
}

async function getFeed() {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(UPSTREAM, {
      signal: controller.signal,
      headers: {"Accept": "application/json"},
      redirect: "error"
    });
    if (!response.ok) throw new Error("Upstream HTTP " + response.status);
    const declared = Number(response.headers.get("content-length") || "0");
    if (declared > MAX_BODY) throw new Error("Oversized upstream response");
    const chunks = [];
    let count = 0;
    for await (const chunk of response.body) {
      count += chunk.length;
      if (count > MAX_BODY) throw new Error("Oversized upstream response");
      chunks.push(chunk);
    }
    return normalize(JSON.parse(Buffer.concat(chunks).toString("utf8")));
  } finally { clearTimeout(timeout); }
}

function handler() {
  let cached = null;
  let cachedAt = 0;
  let inflight = null;
  let lastAttempt = 0;
  return async (req, res) => {
    const send = (status, obj) => {
      res.writeHead(status, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff"
      });
      res.end(JSON.stringify(obj));
    };
    if (req.url === "/health" && req.method === "GET") {
      return send(200, {status:"ok", service:"eztik-feed-gateway"});
    }
    if (req.url !== "/v1/guest-feed" || req.method !== "GET") {
      return send(404, {error:"not_found"});
    }
    const now = Date.now();
    if (cached && now - cachedAt < 5000) return send(200, cached);
    if (!inflight && now - lastAttempt < 1500) return send(503,{error:"retry_later"});
    if (!inflight) {
      lastAttempt = now;
      inflight = getFeed().then(data => {
        cached = data;
        cachedAt = Date.now();
        return data;
      }).finally(() => {inflight = null;});
    }
    try { send(200, await inflight); }
    catch { send(502, {error:"upstream_unavailable"}); }
  };
}

if (require.main === module) {
  if (!Number.isInteger(PORT) || PORT < 1024 || PORT > 65535) {
    throw new Error("Invalid local gateway port");
  }
  const server = http.createServer(handler());
  server.requestTimeout = 25000;
  server.headersTimeout = 10000;
  server.listen(PORT, HOST, () => {
    console.log("ezTIK feed gateway on http://" + HOST + ":" + PORT);
  });
}
module.exports = {allowedVideoUrl, videoCandidates, normalize, handler};
