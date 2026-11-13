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

// ------------------------------------------------------- API FastAPI (comun)

const UNPACK = (packed) => {
  const m = packed.match(/eval\(function\(p,a,c,k,e,(?:d|r)\)\{[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)\)/);
  if (!m) return null;
  const [, p, a, c, kTable] = m;
  const radix = Number(a);
  const count = Number(c);
  const table = kTable.split("|");
  const alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
  const toBase = (n, r) => {
    let s = "";
    while (n > 0) { s = alphabet[n % r] + s; n = Math.floor(n / r); }
    return s || "0";
  };
  let out = p;
  for (let i = count - 1; i >= 0; i--) {
    const token = toBase(i, radix);
    if (!table[i]) continue;
    out = out.replace(new RegExp("\\b" + token + "\\b", "g"), table[i]);
  }
  return out;
};

/** Resuelve un reproductor vimeos al m3u8. */
async function vimeosM3u8(embedUrl, referer) {
  const r = await fetch(embedUrl, { headers: { "User-Agent": UA, Referer: referer } });
  const t = await r.text();
  const unpacked = UNPACK(t);
  if (!unpacked) return null;
  const files = [...unpacked.matchAll(/file\s*:\s*"(https?:\/\/[^"]+)"/g)].map((m) => m[1]);
  return files.find((f) => f.includes(".m3u8")) || null;
}

/** Comprueba que una playlist m3u8 responde con contenido. */
async function checkPlaylist(url) {
  const r = await fetch(url, { headers: { "User-Agent": UA } });
  const t = await r.text();
  return { ok: t.startsWith("#EXTM3U"), status: r.status, quality: t.match(/RESOLUTION=(\d+x\d+)/)?.[1] || "?" };
}

async function verifyFastApi(name, site, api, imageBase, typeOf) {
  console.log(`\n== ${name} (${site}) ==`);
  const get = async (u, h = {}) => {
    const r = await fetch(u, { headers: { "User-Agent": UA, Accept: "application/json", ...h } });
    return { s: r.status, t: await r.text() };
  };

  const list = await get(`${api}/listing/movies?page=1&orderBy=date&order=desc&postType=movies&postsPerPage=6`);
  const listJson = JSON.parse(list.t);
  const posts = listJson.data?.posts || [];
  posts.length > 0 ? ok(`catalogo: ${posts.length} entradas (total ${listJson.data.pagination?.total})`) : fail("catalogo: sin entradas");

  const search = await get(`${api}/search?q=matrix&postType=any&postsPerPage=6`);
  const searchJson = JSON.parse(search.t);
  const found = searchJson.data?.posts || [];
  found.length > 0 ? ok(`busqueda: ${found.length} resultados para "matrix"`) : fail("busqueda: sin resultados");

  const post = posts[0];
  const slug = post.slug;
  const single = await get(`${api}/single/movies?slug=${slug}&postType=movies`);
  const singleJson = JSON.parse(single.t);
  const detail = singleJson.data;
  detail?.title ? ok(`ficha: "${detail.title}" (${detail.release_date?.slice(0, 4)}, poster ${detail.images?.poster ? "si" : "no"})`) : fail("ficha: sin datos");

  // poster
  const posterUrl = imageBase + (detail.images?.poster || "");
  const pr = await fetch(posterUrl, { headers: { "User-Agent": UA } });
  pr.status === 200 ? ok(`poster: ${posterUrl.slice(0, 80)} -> ${pr.status} ${pr.headers.get("content-type")}`) : fail(`poster: ${posterUrl.slice(0, 80)} -> ${pr.status}`);

  // reproductor
  const player = await get(`${api}/player?postId=${detail._id}&demo=0`);
  const embeds = JSON.parse(player.t).data?.embeds || [];
  if (embeds.length === 0) {
    fail("reproductor: sin embeds");
  } else {
    ok(`reproductor: ${embeds.length} embeds (${embeds.map((e) => e.server + "/" + e.lang).join(", ")})`);
    for (const e of embeds) {
      if (!e.url?.includes("vimeos.net")) {
        console.log(`  \u00b7 ${e.server}: ${e.url} (otro reproductor)`);
        continue;
      }
      const m3u8 = await vimeosM3u8(e.url, site);
      if (!m3u8) { fail(`  vimeos: no se pudo desempaquetar ${e.url}`); continue; }
      const chk = await checkPlaylist(m3u8);
      chk.ok ? ok(`  vimeos: playlist valida (${chk.quality}) ${m3u8.slice(0, 70)}`) : fail(`  vimeos: la playlist no es valida (${chk.status})`);
    }
  }

  // episodios de una serie
  if (typeOf === "tvshows") {
    const shows = await get(`${api}/listing/tvshows?page=1&postType=tvshows&postsPerPage=2`);
    const show = JSON.parse(shows.t).data.posts[0];
    const eps = await get(`${api}/single/episodes/list?_id=${show._id}&season=1&page=1&postsPerPage=10`);
    const epsJson = JSON.parse(eps.t);
    const epsPosts = epsJson.data?.posts || [];
    epsPosts.length > 0 ? ok(`serie "${show.title}": ${epsPosts.length} episodios (${epsJson.data.seasons?.join(",")})`) : fail("serie: sin episodios");
  }
}

// --------------------------------------------------------------- AnimeOnline

async function verifyAnimeOnline() {
  console.log("\n== AnimeOnline (animeonline.one) ==");
  const get = async (u, h = {}) => {
    const r = await fetch(u, { headers: { "User-Agent": UA, ...h } });
    return { s: r.status, url: r.url, t: await r.text() };
  };
  const home = await get("https://animeonline.one/");
  const cards = (home.t.match(/class="flw-item/g) || []).length;
  cards > 0 ? ok(`catalogo: ${cards} tarjetas`) : fail("catalogo: sin tarjetas");

  const search = await get("https://animeonline.one/?s=naruto");
  const found = (search.t.match(/class="flw-item/g) || []).length;
  found > 0 ? ok(`busqueda: ${found} resultados para "naruto"`) : fail("busqueda: sin resultados");

  const animeUrl = (home.t.match(/href="(https:\/\/animeonline\.one\/anime\/[^"]+)"/) || [])[1];
  const an = await get(animeUrl);
  const h1 = (an.t.match(/<h1[^>]*>([\s\S]{0,120}?)<\/h1>/) || [])[1]?.replace(/<[^>]+>/g, " ").trim();
  h1 ? ok(`ficha: "${h1}"`) : fail("ficha: sin titulo");
  const eps = [...new Set([...an.t.matchAll(/href="(https:\/\/animeonline\.one\/[^"]*-episode-\d+)\/"/g)].map((m) => m[1]))];
  eps.length > 0 ? ok(`episodios: ${eps.length} (${eps[0]})`) : fail("episodios: ninguno");

  const ep = await get(eps[0]);
  const hashes = [...ep.t.matchAll(/class="btn"[^>]*data-hash="([^"]+)"/g)].map((m) => m[1]);
  if (hashes.length === 0) {
    fail("reproductor: sin servidores");
  } else {
    ok(`reproductor: ${hashes.length} servidores`);
    for (const h of hashes) {
      const url = Buffer.from(h, "base64").toString("utf8");
      const res = await fetch(url, { headers: { "User-Agent": UA, Referer: eps[0] } }).catch(() => null);
      const m3u8 = res ? [...new Set([...res.text().matchAll(/sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]/g)].map((m) => m[1]))] : [];
      console.log(`  \u00b7 ${url} (${res ? res.status : "sin respuesta"})${m3u8.length ? ` m3u8=${m3u8[0].slice(0, 70)}` : ""}`);
      if (m3u8.length) {
        const chk = await checkPlaylist(m3u8[0]);
        chk.ok ? ok(`     playlist valida (${chk.quality})`) : fail(`     playlist no valida (${chk.status})`);
      }
    }
  }
}

// ---------------------------------------------------------------- Hackstore

async function verifyHackstore() {
  console.log("\n== Hackstore (hackstore.fo) ==");
  const get = async (u, h = {}) => {
    const r = await fetch(u, { headers: { "User-Agent": UA, ...h } });
    return { s: r.status, url: r.url, t: await r.text() };
  };
  const cat = await get("https://www.hackstore.fo/peliculas");
  const cards = (cat.t.match(/class="movie-thumbnail"/g) || []).length;
  cards > 0 ? ok(`catalogo: ${cards} tarjetas`) : fail("catalogo: sin tarjetas");

  const search = await get("https://www.hackstore.fo/?s=matrix");
  const found = (search.t.match(/class="movie-thumbnail"/g) || []).length;
  found > 0 ? ok(`busqueda: ${found} resultados para "matrix"`) : fail("busqueda: sin resultados");

  const movieUrl = (cat.t.match(/href="(https:\/\/www\.hackstore\.fo\/peliculas\/[^"]+)\/"/) || [])[1];
  const mv = await get(movieUrl);
  const h1 = (mv.t.match(/<h1[^>]*>([\s\S]{0,120}?)<\/h1>/) || [])[1]?.replace(/<[^>]+>/g, " ").trim();
  h1 ? ok(`ficha: "${h1}"`) : fail("ficha: sin titulo");

  const playr = [...mv.t.matchAll(/<a[^>]*class="playr"[^>]*data-href="([^"]+)"/g)].map((m) => m[1]);
  if (playr.length === 0) {
    fail("reproductor: sin servidores");
    return;
  }
  ok(`reproductor: ${playr.length} servidores`);
  const play = await get("https://www.hackstore.fo" + playr[0].replace(/&amp;/g, "&"), { Referer: movieUrl });
  const embed = play.t.match(/window\.location\.href\s*=\s*'([^']+)'/)?.[1];
  embed ? ok(`  play.php -> ${embed}`) : fail("  play.php: no se encontro la url del reproductor");
  if (embed?.includes("vimeos.net")) {
    const m3u8 = await vimeosM3u8(embed, movieUrl);
    if (m3u8) {
      const chk = await checkPlaylist(m3u8);
      chk.ok ? ok(`  vimeos: playlist valida (${chk.quality})`) : fail("  vimeos: playlist no valida");
    } else fail("  vimeos: no se pudo desempaquetar");
  }
}

// ------------------------------------------------------------ Ver.Gnula.One

async function verifyVerGnula() {
  console.log("\n== Gnula .one (ver.gnula.one) ==");
  const get = async (u, h = {}) => {
    const r = await fetch(u, { headers: { "User-Agent": UA, ...h } });
    return { s: r.status, url: r.url, t: await r.text() };
  };
  const cat = await get("https://ver.gnula.one/peliculas/");
  const cards = (cat.t.match(/gn-card gn-card--grid/g) || []).length;
  cards > 0 ? ok(`catalogo: ${cards} tarjetas`) : fail("catalogo: sin tarjetas");

  const sug = await get("https://ver.gnula.one/wp-json/gnula/v1/suggest?q=matrix");
  let n = 0;
  try { n = JSON.parse(sug.t).length; } catch {}
  n > 0 ? ok(`busqueda (endpoint de sugerencias): ${n} resultados para "matrix"`) : fail("busqueda: sin resultados");

  const movieUrl = (cat.t.match(/href="(https:\/\/ver\.gnula\.one\/movies\/[^"]+)\/"/) || [])[1];
  const mv = await get(movieUrl);
  const srv = [...mv.t.matchAll(/<a[^>]*class="gn-srv"[^>]*href="([^"]+)"/g)].map((m) => m[1]);
  if (srv.length === 0) {
    fail("reproductor: sin servidores");
    return;
  }
  ok(`reproductor: ${srv.length} servidores`);
  for (const href of srv.slice(0, 3)) {
    const res = await get(href.replace(/&#038;/g, "&").replace(/&amp;/g, "&"), { Referer: movieUrl });
    const iframe = res.t.match(/<iframe[^>]*src="([^"]+)"/)?.[1];
    iframe ? ok(`  ${href.slice(0, 60)} -> ${iframe.slice(0, 70)}`) : fail(`  ${href.slice(0, 60)} -> sin iframe`);
  }
}

// ------------------------------------------------------ VerPeliculasUltra

async function verifyVerPeliculasUltra() {
  console.log("\n== VerPeliculasUltra (verpeliculasultra.com) ==");
  const get = async (u, h = {}) => {
    const r = await fetch(u, { headers: { "User-Agent": UA, ...h }, redirect: "follow" });
    return { s: r.status, url: r.url, t: await r.text() };
  };
  const cat = await get("https://verpeliculasultra.com/lastnews/");
  const cards = (cat.t.match(/class="shortf"/g) || []).length;
  cards > 0 ? ok(`catalogo: ${cards} tarjetas`) : fail("catalogo: sin tarjetas");

  const search = await get("https://verpeliculasultra.com/index.php?do=search&subaction=search&story=dune");
  const found = (search.t.match(/class="shortf"/g) || []).length;
  found > 0 ? ok(`busqueda: ${found} resultados para "dune"`) : fail("busqueda: sin resultados");

  const movieUrl = (cat.t.match(/<div class="shortf-img">\s*<a href="([^"]+)"/) || [])[1];
  const mv = await get(movieUrl, { Referer: "https://verpeliculasultra.com/" });
  const h1 = (mv.t.match(/<h1[^>]*>([\s\S]{0,160}?)<\/h1>/) || [])[1]?.replace(/<[^>]+>/g, " ").trim();
  h1 ? ok(`ficha: "${h1}"`) : fail("ficha: sin titulo");

  const servers = [...new Set([...mv.t.matchAll(/<div class="[^"]*play-btn-cont"[^>]*data-src="([^"]+)"/g)].map((m) => m[1]))];
  if (servers.length === 0) {
    fail("reproductor: sin servidores");
    return;
  }
  ok(`reproductor: ${servers.length} servidores`);
  const hg = servers.find((s) => s.includes("hgcloud"));
  if (!hg) {
    console.log("  (sin familia hgcloud)");
    return;
  }
  const id = hg.split("/e/")[1];
  for (const domain of ["audinifer.com", "hanerix.com", "vibuxer.com"]) {
    const p = await get(`https://${domain}/e/${id}`, { Referer: "https://verpeliculasultra.com/" });
    const unpacked = UNPACK(p.t);
    if (!unpacked) continue;
    const links = {};
    for (const m of unpacked.matchAll(/"(hls\d)":"([^"]*)"/g)) links[m[1]] = m[2];
    const pick = links.hls4 || links.hls3 || links.hls2;
    if (!pick) continue;
    const full = pick.startsWith("http") ? pick : `https://${domain}${pick}`;
    const chk = await checkPlaylist(full);
    chk.ok ? ok(`  hgcloud -> ${domain}: playlist valida (${chk.quality})`) : fail(`  hgcloud -> ${domain}: playlist no valida (${chk.status})`);
    break;
  }
}

// -------------------------------------------------------------- FlixCorn

async function verifyFlixCorn() {
  console.log("\n== FlixCorn (flixcorn.net) ==");
  const get = async (u, h = {}) => {
    const r = await fetch(u, { headers: { "User-Agent": UA, ...h }, redirect: "follow" });
    return { s: r.status, url: r.url, t: await r.text() };
  };
  const cat = await get("https://www.flixcorn.net/tendencias");
  const cards = (cat.t.match(/media-card/g) || []).length;
  cards > 0 ? ok(`catalogo: ${cards} tarjetas`) : fail("catalogo: sin tarjetas");

  const search = await get("https://www.flixcorn.net/search?q=lupin");
  const found = (search.t.match(/media-card/g) || []).length;
  found > 0 ? ok(`busqueda: ${found} resultados para "lupin"`) : fail("busqueda: sin resultados");

  const serieUrl = (cat.t.match(/href="(\/serie\/[^"]+\.html)"/) || [])[1];
  const se = await get("https://www.flixcorn.net" + serieUrl);
  const title = (se.t.match(/<h1[^>]*>([\s\S]{0,120}?)<\/h1>/) || [])[1]?.replace(/<[^>]+>/g, " ").trim();
  title ? ok(`serie: "${title}" (${serieUrl})`) : fail("serie: sin titulo");
  const seasons = [...new Set([...se.t.matchAll(/href="(\/serie\/[^"]*\/temporada-\d+\.html)"/g)].map((m) => m[1]))];
  seasons.length > 0 ? ok(`temporadas: ${seasons.length} (${seasons[0]})`) : fail("temporadas: ninguna");

  const seasonPage = await get("https://www.flixcorn.net" + (seasons[0] || serieUrl));
  const epUrl = (seasonPage.t.match(/href="(\/ver\/[^"]+capitulo-\d+\.html)"/) || [])[1];
  if (!epUrl) { fail("episodio: no encontrado"); return; }
  const ep = await get("https://www.flixcorn.net" + epUrl);
  const rows = [...ep.t.matchAll(/<div class="cap-server-row">([\s\S]*?)<\/div>\s*<\/div>/g)];
  const seen = new Set();
  const servers = rows
    .map((m) => ({
      name: m[1].match(/class="cap-server-name">([^<]*)</)?.[1],
      ext: m[1].match(/href="(\/external\/[^"]+)"/)?.[1],
    }))
    .filter((s) => s.ext && !seen.has(s.ext) && seen.add(s.ext));
  ok(`episodio ${epUrl}: ${servers.length} servidores`);
  for (const m of servers.slice(0, 4)) {
    const name = m.name;
    const ext = m.ext;
    if (!ext) continue;
    const ex = await get("https://www.flixcorn.net" + ext, { "X-Requested-With": "XMLHttpRequest", Referer: "https://www.flixcorn.net" + epUrl });
    let json = null;
    try { json = JSON.parse(ex.t); } catch {}
    if (json?.url) {
      console.log(`  \u00b7 ${name}: ${json.url}`);
      if (json.url.includes("vidara")) {
        const fileCode = json.url.split("/e/").pop().split("?")[0];
        const st = await fetch("https://vidaraa.cc/api/stream", {
          method: "POST",
          headers: { "User-Agent": UA, "Content-Type": "application/json", Referer: json.url },
          body: JSON.stringify({ filecode: fileCode, device: "web", codecs: [] }),
        });
        const sj = await st.json().catch(() => null);
        const m3u8 = sj?.streaming_url;
        if (m3u8) {
          const chk = await checkPlaylist(m3u8);
          chk.ok ? ok(`     vidara: playlist valida (${chk.quality})`) : fail(`     vidara: playlist no valida (${chk.status})`);
        } else fail("     vidara: sin streaming_url");
      }
    } else {
      console.log(`  \u00b7 ${name}: ${ex.t.slice(0, 60)}`);
    }
  }
}

// ---------------------------------------------------------------- Latanime

async function verifyLatanime() {
  console.log("\n== Latanime (latanime.org) ==");
  const get = async (u, h = {}) => {
    const r = await fetch(u, { headers: { "User-Agent": UA, ...h }, redirect: "follow" });
    return { s: r.status, url: r.url, t: await r.text() };
  };
  const cat = await get("https://latanime.org/animes");
  const cards = (cat.t.match(/div class="series"/g) || []).length;
  cards > 0 ? ok(`catalogo: ${cards} tarjetas`) : fail("catalogo: sin tarjetas");

  const search = await get("https://latanime.org/buscar?q=black");
  const found = (search.t.match(/div class="series"/g) || []).length;
  found > 0 ? ok(`busqueda: ${found} resultados para "black"`) : fail("busqueda: sin resultados");

  const animeUrl = (cat.t.match(/href="(https:\/\/latanime\.org\/anime\/[^"]+)"/) || [])[1];
  const an = await get(animeUrl);
  const title = (an.t.match(/<h2[^>]*>([\s\S]{0,120}?)<\/h2>/) || [])[1]?.replace(/<[^>]+>/g, " ").trim();
  title ? ok(`anime: "${title}"`) : fail("anime: sin titulo");
  const eps = [...new Set([...an.t.matchAll(/href="(https:\/\/latanime\.org\/ver\/[^"]+)"/g)].map((m) => m[1]))];
  eps.length > 0 ? ok(`episodios: ${eps.length} (${eps[0]})`) : fail("episodios: ninguno");

  if (eps.length) {
    const ep = await get(eps[0]);
    const players = [...ep.t.matchAll(/class="play-video[^"]*"[^>]*data-player="([^"]+)"/g)].map((m) => m[1]);
    if (players.length === 0) {
      fail("reproductor: sin servidores");
    } else {
      ok(`reproductor: ${players.length} servidores`);
      for (const p of players.slice(0, 5)) console.log(`  \u00b7 ${Buffer.from(p, "base64").toString("utf8")}`);
    }
  }
}

// --------------------------------------------------------------------- main

const which = process.argv[2] || "ambos";
if (which === "soloLatino" || which === "ambos") await verifySoloLatino();
if (which === "flixlatam" || which === "ambos") await verifyFlixLatam();
if (which === "pelisplushd" || which === "ambos") await verifyPelisPlusHD();
if (which === "gnula" || which === "ambos")
  await verifyFastApi("Gnula", "https://gnula.la", "https://gnula.la/wp-api/v1", "https://gnula.la/wp-content/uploads", "tvshows");
if (which === "cinecalidad" || which === "ambos")
  await verifyFastApi("Cinecalidad", "https://cinecalidad.org", "https://cinecalidad.org/wp-api/v1", "https://cinecalidad.org/wp-content/uploads", "tvshows");
if (which === "animeonline" || which === "ambos") await verifyAnimeOnline();
if (which === "hackstore" || which === "ambos") await verifyHackstore();
if (which === "gnulaone" || which === "ambos") await verifyVerGnula();
if (which === "verpeliculasultra" || which === "ambos") await verifyVerPeliculasUltra();
if (which === "flixcorn" || which === "ambos") await verifyFlixCorn();
if (which === "latanime" || which === "ambos") await verifyLatanime();
console.log(failed ? "\nFALLOS" : "\nTODO CORRECTO");
process.exitCode = failed ? 1 : 0;
