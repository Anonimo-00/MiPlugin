// Verificacion en vivo de los dos proveedores contra los sitios reales.
//
// Reproduce la logica de los proveedores Kotlin:
//   - SoloLatino: sesion Laravel -> POST /api/player-url (X-SL-Render) ->
//     reproductor player.pelisserieshoy.com -> POST /s.php (a=1, a=click, a=2)
//     en todos los idiomas -> proxy /p.php?url=...&sig=...
//     y el segundo boton: embed69.org protegido con Anubis (proof of work).
//   - FlixLatam: ficha -> /vidurl/<id>/ -> dataLink -> AES-256-CBC (clave
//     SHA-256(challenge+salt)) -> enlaces de los reproductores.
//
// Uso: node tools/verify.mjs [soloLatino|flixlatam]

import crypto from "node:crypto";

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

let failed = 0;
const ok = (msg) => console.log(`  \u2713 ${msg}`);
const fail = (msg) => {
  console.error(`  \u2717 ${msg}`);
  failed++;
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

// ------------------------------------------------------------------ comun

function fixHost(url) {
  return url
    .replace("https://morencius.com", "https://vidhidepro.com")
    .replace("https://minochinos.com", "https://vidhidepro.com")
    .replace("https://mivalyo.com", "https://vidhidepro.com")
    .replace("https://dinisglows.com", "https://vidhidepro.com")
    .replace("https://dhtpre.com", "https://vidhidepro.com")
    .replace("https://hglink.to", "https://streamwish.to")
    .replace("https://swdyu.com", "https://streamwish.to")
    .replace("https://cybervynx.com", "https://streamwish.to")
    .replace("https://dumbalag.com", "https://streamwish.to")
    .replace("https://filemoon.link", "https://filemoon.sx")
    .replace("https://sblona.com", "https://watchsb.com")
    .replace("https://lulu.st", "https://lulustream.com")
    .replace("https://uqload.io", "https://uqload.com")
    .replace("https://do7go.com", "https://dood.la");
}

function decryptLink(encrypted, key) {
  const raw = Buffer.from(encrypted, "base64");
  const decipher = crypto.createDecipheriv("aes-256-cbc", key, raw.subarray(0, 16));
  return Buffer.concat([decipher.update(raw.subarray(16)), decipher.final()])
    .toString("utf8")
    .replace(/`/g, "")
    .trim();
}

/** Proof of work de Anubis: SHA-256(datos + nonce) con los primeros bits a cero. */
function solveAnubis(randomData, difficulty) {
  const fullBytes = Math.floor(difficulty / 2);
  const halfByte = difficulty % 2 !== 0;
  for (let nonce = 0; ; nonce++) {
    const digest = crypto.createHash("sha256").update(randomData + nonce, "utf8").digest();
    let valid = true;
    for (let i = 0; i < fullBytes; i++) if (digest[i] !== 0) { valid = false; break; }
    if (valid && halfByte && (digest[fullBytes] >> 4) !== 0) valid = false;
    if (valid) return { hash: digest.toString("hex"), nonce };
  }
}

/** Descarga una pagina de EMBED69 pasando antes el reto de Anubis si lo hay. */
async function fetchEmbed69(url, referer) {
  const cookies = new Map();
  const cookieHeader = () => [...cookies.entries()].map(([k, v]) => `${k}=${v}`).join("; ");
  const absorb = (res) => {
    for (const c of res.headers.getSetCookie ? res.headers.getSetCookie() : []) {
      const [pair] = c.split(";");
      const i = pair.indexOf("=");
      if (i > 0) cookies.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
    }
  };

  const headers = { "User-Agent": UA, Referer: referer };
  const first = await fetch(url, { headers });
  absorb(first);
  let html = await first.text();

  const challengeMatch = html.match(/<script id="anubis_challenge"[^>]*>([\s\S]*?)<\/script>/);
  if (challengeMatch) {
    const challenge = JSON.parse(challengeMatch[1]).challenge;
    const started = Date.now();
    const { hash, nonce } = solveAnubis(challenge.randomData, challenge.difficulty);
    const passUrl =
      `https://embed69.org/.within.website/x/cmd/anubis/api/pass-challenge` +
      `?id=${challenge.id}&response=${hash}&nonce=${nonce}` +
      `&redir=${encodeURIComponent(new URL(url).pathname)}&elapsedTime=${Date.now() - started}`;
    const pass = await fetch(passUrl, { headers: { ...headers, Cookie: cookieHeader() }, redirect: "manual" });
    absorb(pass);
    const second = await fetch(url, { headers: { ...headers, Cookie: cookieHeader() } });
    html = await second.text();
    return { html, anubis: true };
  }
  return { html, anubis: false };
}

/** Lee y descifra los servidores de un reproductor EMBED69. */
async function embed69Links(url, referer) {
  const { html, anubis } = await fetchEmbed69(url, referer);
  const rawData = html.match(/let dataLink\s*=\s*(\[[\s\S]*?\]);/)?.[1];
  const challenge = html.match(/LINK_CHALLENGE\s*=\s*'([^']+)'/)?.[1];
  const salt = html.match(/LINK_SALT\s*=\s*'([^']+)'/)?.[1];
  if (!rawData || !challenge || !salt) return { links: [], anubis };
  const key = crypto.createHash("sha256").update(challenge + salt, "utf8").digest();
  const links = [];
  for (const file of JSON.parse(rawData)) {
    for (const server of file.sortedEmbeds || []) {
      const plain = decryptLink(server.link, key);
      if (!plain.startsWith("http")) continue;
      links.push({ name: `${file.video_language}/${server.servername}`, url: fixHost(plain) });
    }
  }
  return { links, anubis };
}

// -------------------------------------------------------------- SoloLatino

async function verifySoloLatino() {
  console.log("\n== SoloLatino (sololatino.net) ==");
  const BASE = "https://sololatino.net";
  const PLAYER = "https://player.pelisserieshoy.com";
  const pageUrl = `${BASE}/pelicula/un-buen-abogado`;

  const catalog = await fetch(`${BASE}/peliculas`, { headers: { "User-Agent": UA } });
  const catalogHtml = await catalog.text();
  const cards = catalogHtml.match(/<div class="card">/g) || [];
  cards.length > 0 ? ok(`catalogo: ${cards.length} tarjetas en /peliculas`) : fail("catalogo: sin tarjetas");

  const search = await fetch(`${BASE}/buscar?q=batman`, { headers: { "User-Agent": UA } });
  const found = (await search.text()).match(/<div class="card">/g) || [];
  found.length > 0 ? ok(`busqueda: ${found.length} resultados para "batman"`) : fail("busqueda: sin resultados");

  const session = await fetch(`${BASE}/sanctum/csrf-cookie`, { headers: { "User-Agent": UA } });
  const jar = jarOf(session);
  const xsrf = decodePercent(
    (jar.find((c) => c.startsWith("XSRF-TOKEN=")) || "").split("=").slice(1).join("=")
  );
  xsrf ? ok(`sesion: XSRF-TOKEN de ${xsrf.length} caracteres`) : fail("sesion: sin XSRF-TOKEN");

  const page = await fetch(pageUrl, { headers: { "User-Agent": UA, Cookie: jar.join("; ") } });
  const html = await page.text();
  const tokens = [...new Set([...html.matchAll(/data-player-token="([^"]+)"/g)].map((m) => m[1]))];
  tokens.length > 0 ? ok(`ficha: ${tokens.length} botones de servidor`) : fail("ficha: sin botones de servidor");

  const hls = [];
  const embeds = [];
  for (const token of tokens) {
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
    if (!player.url) {
      fail(`api/player-url: ${playerRes.status} ${JSON.stringify(player)}`);
      continue;
    }
    ok(`api/player-url: ${player.type} -> ${player.url.slice(0, 60)}`);

    // --- reproductor player.pelisserieshoy.com ---
    if (player.url.includes("pelisserieshoy")) {
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
        continue;
      }
      const signed = shards[1] + shards[3] + shards[0] + shards[2];
      const proof = fnv1a(`${signed}|clk|${contentId}`);
      ok("reproductor: token firmado reconstruido");

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
        return await r.text();
      };
      const common = { tok: sessionToken, rt: signed, tk: proof, ao: BASE };
      const list = JSON.parse(await post({ a: "1", ...common }));
      const languages = Object.keys(list.langs_s || {});
      ok(`s.php a=1: ${list.s?.length || 0} servidores, idiomas: ${languages.join(", ")}`);
      await post({ a: "click", ts: "1", ...common });

      for (const [lang, servers] of Object.entries(list.langs_s || { LAT: list.s })) {
        for (const [label, id] of servers) {
          const raw = await post({ a: "2", v: id, ...common });
          let data;
          try { data = JSON.parse(raw); } catch { continue; }
          if (!data.u || !data.u.startsWith("http")) continue;
          const proxy = `${PLAYER}/p.php?url=${encodeURIComponent(data.u)}&sig=${encodeURIComponent(data.sig || "")}`;
          const playlist = await fetch(proxy, { headers: { "User-Agent": UA } });
          const text = await playlist.text();
          if (text.startsWith("#EXTM3U")) {
            const quality = text.match(/RESOLUTION=(\d+x\d+)/)?.[1] || "?";
            hls.push({ lang, label, quality });
            ok(`s.php a=2 [${lang}/${label}]: playlist HLS valida (${quality})`);
          } else {
            fail(`s.php a=2 [${lang}/${label}]: no es una playlist HLS (${playlist.status})`);
          }
        }
      }
    }

    // --- reproductor embed69.org (protegido con Anubis) ---
    if (player.url.includes("embed69")) {
      const { links, anubis } = await embed69Links(player.url, pageUrl);
      if (anubis) ok("embed69: reto de Anubis resuelto");
      if (links.length === 0) fail("embed69: sin servidores");
      for (const link of links) {
        const res = await fetch(link.url, { headers: { "User-Agent": UA, Referer: player.url } }).catch(() => null);
        const status = res ? res.status : "sin respuesta";
        embeds.push(link);
        console.log(`  \u00b7 embed69 ${link.name}: ${link.url} (${status})`);
      }
      ok(`embed69: ${links.length} enlaces descifrados`);
    }
  }

  const uniqueHls = new Set(hls.map((h) => `${h.lang}/${h.label}`)).size;
  ok(`total: ${uniqueHls} fuentes HLS (pelisserieshoy) + ${embeds.length} fuentes embebidas (embed69)`);
  if (uniqueHls + embeds.length < 2) fail("no se han obtenido fuentes varias");
}

// --------------------------------------------------------------- FlixLatam

async function verifyFlixLatam() {
  console.log("\n== FlixLatam (flixlatam.com) ==");
  const BASE = "https://flixlatam.com";
  const pageUrl = `${BASE}/pelicula/angel-y-el-pistolero-ev2BxB`;

  const catalog = await fetch(`${BASE}/peliculas`, { headers: { "User-Agent": UA } });
  const cards = (await catalog.text()).match(/<article class="item">/g) || [];
  cards.length > 0 ? ok(`catalogo: ${cards.length} tarjetas en /peliculas`) : fail("catalogo: sin tarjetas");

  const search = await fetch(`${BASE}/search?s=batman`, { headers: { "User-Agent": UA } });
  const found = (await search.text()).match(/<article class="item">/g) || [];
  found.length > 0 ? ok(`busqueda: ${found.length} resultados para "batman"`) : fail("busqueda: sin resultados");

  const page = await fetch(pageUrl, { headers: { "User-Agent": UA } });
  const html = await page.text();
  const vidurl = html.match(/\/vidurl\/[^"']+/)?.[0];
  if (!vidurl) {
    fail("ficha: no se encontro el iframe /vidurl/");
    return;
  }
  ok(`ficha: reproductor ${vidurl}`);

  const { links, anubis } = await embed69Links(`${BASE}${vidurl}`, pageUrl);
  if (anubis) ok("reproductor: reto de Anubis resuelto");
  if (links.length === 0) {
    fail("reproductor: sin servidores");
    return;
  }
  for (const link of links) {
    const res = await fetch(link.url, { headers: { "User-Agent": UA, Referer: `${BASE}${vidurl}` } }).catch(() => null);
    console.log(`  \u00b7 ${link.name}: ${link.url} (${res ? res.status : "sin respuesta"})`);
  }
  ok(`${links.length} enlaces descifrados (${[...new Set(links.map((l) => l.name.split("/")[0]))].join(", ")})`);
  if (links.length < 2) fail("se esperaban varias fuentes");
}

// --------------------------------------------------------------- PelisPlusHD

async function verifyPelisPlusHD() {
  console.log("\n== PelisPlusHD (pelisplushd.la) ==");
  const BASE = "https://pelisplushd.la";
  const get = async (u) => {
    const r = await fetch(u, { headers: { "User-Agent": UA }, redirect: "follow" });
    return { status: r.status, url: r.url, t: await r.text() };
  };

  const catalog = await get(`${BASE}/peliculas`);
  const cards = (catalog.t.match(/class="Posters-link"/g) || []).length;
  cards > 0 ? ok(`catalogo: ${cards} tarjetas en /peliculas`) : fail("catalogo: sin tarjetas");

  const search = await get(`${BASE}/search?s=batman`);
  const found = (search.t.match(/class="Posters-link"/g) || []).length;
  found > 0 ? ok(`busqueda: ${found} resultados para "batman"`) : fail("busqueda: sin resultados");

  const detailUrl = (catalog.t.match(/href="(\/pelicula\/[^"]+)"/) || [])[1];
  if (!detailUrl) {
    fail("ficha: no se encontro url de pelicula");
    return;
  }
  const detail = await get(BASE + detailUrl);
  const title = (detail.t.match(/<h1[^>]*>([\s\S]{0,120}?)<\/h1>/) || [])[1]?.replace(/<[^>]+>/g, " ").trim();
  const year = (detail.t.match(/Fecha de estreno:<\/span>\s*(\d{4})/) || [])[1];
  const poster = (detail.t.match(/<img[^>]*src="(\/poster\/[^"]+)"/) || [])[1];
  title ? ok(`ficha ${detailUrl}: "${title}" (${year}, poster ${poster ? "si" : "no"})`) : fail("ficha: sin titulo");

  // Los reproductores van en li[data-url] (pelicula) o en #link_url span[url] (episodio)
  const embedUrls = [
    ...new Set(
      [...detail.t.matchAll(/(?:data-url="([^"]+)"|<span lid="\d+" url="([^"]+)")/g)]
        .map((m) => m[1] || m[2])
        .filter((u) => u && u.startsWith("http"))
    ),
  ];
  const servers = [...new Set([...detail.t.matchAll(/<li role="presentation"[^>]*>\s*<a[^>]*>([^<]+)</g)].map((m) => m[1].trim()))];
  if (embedUrls.length === 0) {
    fail("reproductor: no se encontraron URLs de servidor");
  } else {
    ok(`reproductor: ${embedUrls.length} servidores (${servers.join(", ") || "?"})`);
    for (const url of embedUrls) {
      const res = await fetch(url, { headers: { "User-Agent": UA, Referer: detail.url } }).catch(() => null);
      console.log(`  \u00b7 ${url} (${res ? res.status : "sin respuesta"})`);
    }
  }

  // Episodios de una serie
  const series = await get(`${BASE}/series`);
  const serieUrl = (series.t.match(/href="(\/serie\/[^"]+)"/) || [])[1];
  if (serieUrl) {
    const serie = await get(BASE + serieUrl);
    const episodes = [...new Set([...serie.t.matchAll(/href="(\/serie\/[^"]*\/temporada\/[^"]*\/capitulo\/[^"]*)"/g)].map((m) => m[1]))];
    episodes.length > 0 ? ok(`serie ${serieUrl}: ${episodes.length} episodios (${episodes[0]})`) : fail("serie: sin episodios");
  } else {
    fail("serie: no se encontro url de serie");
  }
}

// --------------------------------------------------------------------- main

const which = process.argv[2] || "ambos";
if (which === "soloLatino" || which === "ambos") await verifySoloLatino();
if (which === "flixlatam" || which === "ambos") await verifyFlixLatam();
if (which === "pelisplushd" || which === "ambos") await verifyPelisPlusHD();
console.log(failed ? "\nFALLOS" : "\nTODO CORRECTO");
process.exitCode = failed ? 1 : 0;
