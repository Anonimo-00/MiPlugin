// Verificacion en vivo de los dos proveedores contra los sitios reales.
//
// Reproduce exactamente la logica de los proveedores Kotlin:
//   - SoloLatino: sesion Laravel -> POST /api/player-url (X-SL-Render) ->
//     reproductor player.pelisserieshoy.com -> POST /s.php (a=1, a=click, a=2)
//     -> proxy /p.php?url=...&sig=...
//   - FlixLatam: ficha -> /vidurl/<id>/ -> dataLink -> AES-256-CBC (clave
//     SHA-256(challenge+salt)) -> enlaces de los reproductores
//
// Uso: node tools/verify.mjs [soloLatino|flixlatam]

import crypto from "node:crypto";

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const ok = (msg) => console.log(`  \u2713 ${msg}`);
const fail = (msg) => {
  console.error(`  \u2717 ${msg}`);
  process.exitCode = 1;
};

const sha256 = (text) => crypto.createHash("sha256").update(text, "utf8").digest("hex");

const fnv1a = (text) => {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return (h >>> 0).toString(16);
};

const decodePercent = (value) =>
  value.replace(/%([0-9A-Fa-f]{2})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)));

const jarOf = (res) =>
  (res.headers.getSetCookie ? res.headers.getSetCookie() : [])
    .map((c) => c.split(";")[0].trim())
    .filter((c) => c.includes("="));

// ---------------------------------------------------------------- SoloLatino

async function verifySoloLatino() {
  console.log("\n== SoloLatino (sololatino.net) ==");
  const BASE = "https://sololatino.net";
  const PLAYER = "https://player.pelisserieshoy.com";
  const pageUrl = `${BASE}/pelicula/un-buen-abogado`;

  // 1. Catalogo
  const catalog = await fetch(`${BASE}/peliculas`, { headers: { "User-Agent": UA } });
  const catalogHtml = await catalog.text();
  const cards = catalogHtml.match(/<div class="card">/g) || [];
  if (cards.length > 0) ok(`catalogo: ${cards.length} tarjetas en /peliculas`);
  else fail("catalogo: no se encontraron tarjetas");

  // 2. Busqueda
  const search = await fetch(`${BASE}/buscar?q=batman`, { headers: { "User-Agent": UA } });
  const searchHtml = await search.text();
  const found = searchHtml.match(/<div class="card">/g) || [];
  if (found.length > 0) ok(`busqueda: ${found.length} resultados para "batman"`);
  else fail("busqueda: sin resultados");

  // 3. Sesion Laravel
  const session = await fetch(`${BASE}/sanctum/csrf-cookie`, { headers: { "User-Agent": UA } });
  const jar = jarOf(session);
  const xsrf = decodePercent(
    (jar.find((c) => c.startsWith("XSRF-TOKEN=")) || "").split("=").slice(1).join("=")
  );
  if (xsrf) ok(`sesion: XSRF-TOKEN de ${xsrf.length} caracteres`);
  else fail("sesion: no se obtuvo XSRF-TOKEN");

  // 4. Tokens de servidor en la ficha
  const page = await fetch(pageUrl, { headers: { "User-Agent": UA, Cookie: jar.join("; ") } });
  const html = await page.text();
  const tokens = [...new Set([...html.matchAll(/data-player-token="([^"]+)"/g)].map((m) => m[1]))];
  if (tokens.length > 0) ok(`ficha: ${tokens.length} botones de servidor`);
  else fail("ficha: no se encontraron botones de servidor");

  // 5. POST /api/player-url con el escudo X-SL-Render
  const token = tokens[0];
  const shield = sha256(`${token}:${Math.floor(Date.now() / 60000)}:sololatino_render_shield_2026`).slice(0, 16);
  const playerRes = await fetch(`${BASE}/api/player-url`, {
    method: "POST",
    headers: {
      "User-Agent": UA,
      Accept: "application/json",
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": xsrf,
      "X-SL-Render": shield,
      Cookie: jar.join("; "),
      Referer: pageUrl,
      Origin: BASE,
    },
    body: JSON.stringify({ t: token }),
  });
  const player = await playerRes.json();
  if (player.url) ok(`api/player-url: ${player.type} -> ${player.url.slice(0, 70)}`);
  else fail(`api/player-url: ${playerRes.status} ${JSON.stringify(player)}`);

  // 6. Reproductor: _t, _qc y el token firmado repartido en 4 trozos
  const playerPage = await fetch(player.url, {
    headers: { "User-Agent": UA, Referer: pageUrl, Accept: "text/html,*/*;q=0.8" },
  });
  const playerHtml = await playerPage.text();
  const sessionToken = playerHtml.match(/_t\s*=\s*'([^']+)'/)?.[1];
  const contentId = playerHtml.match(/_qc\s*=\s*"([^"]*)"/)?.[1];
  const shards = playerHtml.match(/_qk\s*=\s*\[([^\]]*)\]/)?.[1]
    ?.split(",")
    .map((s) => s.trim().replace(/^["']|["']$/g, ""));
  if (!sessionToken || !contentId || !shards || shards.length < 4) {
    fail("reproductor: no se pudo leer _t / _qc / _qk");
    return;
  }
  const signed = shards[1] + shards[3] + shards[0] + shards[2];
  const proof = fnv1a(`${signed}|clk|${contentId}`);
  ok(`reproductor: token de sesion y token firmado reconstruido (${signed.slice(0, 24)}...)`);

  const post = async (params) => {
    const r = await fetch(`${PLAYER}/s.php`, {
      method: "POST",
      headers: {
        "User-Agent": UA,
        Referer: player.url,
        Origin: PLAYER,
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
      },
      body: new URLSearchParams(params).toString(),
    });
    return { status: r.status, body: await r.text() };
  };

  const common = { tok: sessionToken, rt: signed, tk: proof, ao: BASE };
  const listRes = await post({ a: "1", ...common });
  let list;
  try {
    list = JSON.parse(listRes.body);
  } catch {
    fail(`s.php a=1: ${listRes.status} ${listRes.body.slice(0, 160)}`);
    return;
  }
  if (!list.s || !list.s.length) {
    fail(`s.php a=1: sin servidores (${listRes.body.slice(0, 160)})`);
    return;
  }
  ok(`s.php a=1: ${list.s.length} servidores -> ${list.s.map((s) => s[0]).join(", ")}`);

  const click = await post({ a: "click", ts: "1", ...common });
  if (click.body.includes('"ok":true')) ok("s.php a=click: reproduccion marcada");
  else fail(`s.php a=click: ${click.body.slice(0, 160)}`);

  let playable = 0;
  for (const [label, id] of list.s) {
    const res = await post({ a: "2", v: id, ...common });
    let data;
    try {
      data = JSON.parse(res.body);
    } catch {
      continue;
    }
    if (!data.u || !data.u.startsWith("http")) continue;
    const proxy = `${PLAYER}/p.php?url=${encodeURIComponent(data.u)}&sig=${encodeURIComponent(data.sig || "")}`;
    const playlist = await fetch(proxy, { headers: { "User-Agent": UA } });
    const text = await playlist.text();
    if (text.startsWith("#EXTM3U")) {
      playable++;
      const quality = text.match(/RESOLUTION=(\d+x\d+)/)?.[1] || "?";
      ok(`s.php a=2 [${label}]: playlist HLS valida (${quality}), ${text.split("\n").length} lineas`);
    } else {
      fail(`s.php a=2 [${label}]: la respuesta no es una playlist HLS (${playlist.status})`);
    }
  }
  if (playable === 0) fail("no se resolvio ningun enlace reproducible");
}

// ----------------------------------------------------------------- FlixLatam

async function verifyFlixLatam() {
  console.log("\n== FlixLatam (flixlatam.com) ==");
  const BASE = "https://flixlatam.com";
  const pageUrl = `${BASE}/pelicula/angel-y-el-pistolero-ev2BxB`;

  const catalog = await fetch(`${BASE}/peliculas`, { headers: { "User-Agent": UA } });
  const catalogHtml = await catalog.text();
  const cards = catalogHtml.match(/<article class="item">/g) || [];
  if (cards.length > 0) ok(`catalogo: ${cards.length} tarjetas en /peliculas`);
  else fail("catalogo: no se encontraron tarjetas");

  const search = await fetch(`${BASE}/search?s=batman`, { headers: { "User-Agent": UA } });
  const searchHtml = await search.text();
  const found = searchHtml.match(/<article class="item">/g) || [];
  if (found.length > 0) ok(`busqueda: ${found.length} resultados para "batman"`);
  else fail("busqueda: sin resultados");

  const page = await fetch(pageUrl, { headers: { "User-Agent": UA } });
  const html = await page.text();
  const vidurl = html.match(/\/vidurl\/[^"']+/)?.[0];
  if (!vidurl) {
    fail("ficha: no se encontro el iframe /vidurl/");
    return;
  }
  ok(`ficha: reproductor ${vidurl}`);

  const playerRes = await fetch(`${BASE}${vidurl}`, { headers: { "User-Agent": UA, Referer: pageUrl } });
  const playerHtml = await playerRes.text();
  const dataRaw = playerHtml.match(/let dataLink\s*=\s*(\[[\s\S]*?\]);/)?.[1];
  const challenge = playerHtml.match(/LINK_CHALLENGE\s*=\s*'([^']+)'/)?.[1];
  const salt = playerHtml.match(/LINK_SALT\s*=\s*'([^']+)'/)?.[1];
  if (!dataRaw || !challenge || !salt) {
    fail("reproductor: faltan dataLink / LINK_CHALLENGE / LINK_SALT");
    return;
  }
  const key = crypto.createHash("sha256").update(challenge + salt, "utf8").digest();
  ok("reproductor: dataLink y clave AES derivados");

  const files = JSON.parse(dataRaw);
  let decrypted = 0;
  for (const file of files) {
    for (const server of file.sortedEmbeds || []) {
      const raw = Buffer.from(server.link, "base64");
      const iv = raw.subarray(0, 16);
      const ct = raw.subarray(16);
      const decipher = crypto.createDecipheriv("aes-256-cbc", key, iv);
      const plain = Buffer.concat([decipher.update(ct), decipher.final()]).toString("utf8").replace(/`/g, "").trim();
      if (!plain.startsWith("http")) continue;
      decrypted++;
      const res = await fetch(plain, { headers: { "User-Agent": UA, Referer: pageUrl } }).catch(() => null);
      const status = res ? res.status : "sin respuesta";
      console.log(`  \u00b7 ${file.video_language} / ${server.servername}: ${plain} (${status})`);
    }
  }
  if (decrypted > 0) ok(`${decrypted} enlaces descifrados`);
  else fail("no se descifro ningun enlace");
}

// --------------------------------------------------------------------- main

const which = process.argv[2] || "ambos";
if (which === "soloLatino" || which === "ambos") await verifySoloLatino();
if (which === "flixlatam" || which === "ambos") await verifyFlixLatam();
console.log(process.exitCode ? "\nFALLOS" : "\nTODO CORRECTO");
