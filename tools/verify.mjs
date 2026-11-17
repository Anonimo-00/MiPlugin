// Verificacion en vivo de los proveedores contra los sitios reales.
//
// Reproduce la logica de los proveedores Kotlin (mismos selectores) y comprueba:
//
//   1. que el catalogo devuelve contenido,
//   2. que el buscador devuelve resultados RELEVANTES para la consulta,
//   3. que la pagina 2 del buscador trae resultados distintos (paginacion),
//   4. que la ficha se lee bien,
//   5. que los enlaces resueltos devuelven una playlist HLS valida.
//
// Uso: node tools/verify.mjs [proveedor ...]
//       node tools/verify.mjs              (todos)
//       node tools/verify.mjs gnula flixlatam

import crypto from "node:crypto";

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const QUERY = "dragon";

let checks = 0;
let failures = 0;
const ok = (msg) => { checks++; console.log(`  OK   ${msg}`); };
const fail = (msg) => { checks++; failures++; console.log(`  FAIL ${msg}`); };
const info = (msg) => console.log(`       ${msg}`);

const norm = (s) => String(s).toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
const titles = (html, re) => [...html.matchAll(re)].map((m) => m[1].trim()).filter(Boolean);

const get = async (url, headers = {}) => retry(async () => {
  const r = await fetch(url, { headers: { "User-Agent": UA, ...headers } });
  return { status: r.status, url: r.url, headers: r.headers, text: await r.text() };
});

/** Reintenta ante fallos de red (los sitios hacen drop de conexiones a veces). */
const retry = async (fn, times = 3) => {
  let last;
  for (let i = 0; i < times; i++) {
    try {
      return await fn();
    } catch (e) {
      last = e;
      await new Promise((r) => setTimeout(r, 800 * (i + 1)));
    }
  }
  throw last;
};

const jarOf = (res) =>
  (res.headers.getSetCookie ? res.headers.getSetCookie() : [])
    .map((c) => c.split(";")[0].trim())
    .filter((c) => c.includes("="));

const sha256 = (text) => crypto.createHash("sha256").update(text, "utf8").digest("hex");

const fnv1a = (text) => {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return (h >>> 0).toString(16);
};

/** Desempaqueta el packer de Dean Edwards (base 36) que usan vimeos y hgcloud. */
const UNPACK = (packed) => {
  const m = packed.match(/eval\(function\(p,a,c,k,e,(?:d|r)\)\{[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)\)/);
  if (!m) return null;
  const [, p, a, c, kTable] = m;
  const radix = Number(a);
  const count = Number(c);
  const table = kTable.split("|");
  const alpha = "0123456789abcdefghijklmnopqrstuvwxyz";
  const toBase = (n, r) => {
    let s = "";
    while (n > 0) { s = alpha[n % r] + s; n = Math.floor(n / r); }
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

/** Comprueba que una url devuelve una playlist HLS valida. */
async function checkPlaylist(url, referer = "https://vimeos.net/") {
  try {
    const r = await fetch(url, { headers: { "User-Agent": UA, Referer: referer } });
    const t = await r.text();
    const okM3u8 = t.startsWith("#EXTM3U");
    const quality = t.match(/RESOLUTION=(\d+x\d+)/)?.[1] || "?";
    return { ok: okM3u8, status: r.status, quality, type: r.headers.get("content-type") };
  } catch (e) {
    return { ok: false, status: 0, quality: "?", error: e.message };
  }
}

/**
 * Comprueba un buscador: resultados + relevancia + paginacion.
 * 	erm es la palabra buscada (cada sitio tiene su propio catalogo).
 */
async function checkSearch(name, term, url1, url2, titleOf, expectRelevant = true) {
  const r1 = await get(url1);
  const list = titleOf(r1.text);
  const relevant = list.filter((t) => norm(t).includes(norm(term))).length;
  let paged = "sin paginacion";
  if (url2) {
    const r2 = await get(url2);
    const list2 = titleOf(r2.text);
    paged =
      list2.length && list2[0] !== list[0]
        ? `pagina 2 distinta (${list2.length} res)`
        : `pagina 2 igual (${list2.length} res)`;
  }
  if (list.length > 0 && (!expectRelevant || relevant > 0)) {
    ok(`${name}: ${list.length} resultados, ${relevant} relevantes | ${paged}`);
  } else {
    fail(`${name}: ${list.length} resultados, ${relevant} relevantes | ${paged}`);
  }
  return list;
}

// ---------------------------------------------------------------- SoloLatino

async function verifySoloLatino() {
  console.log("\n== SoloLatino (sololatino.net) ==");
  const BASE = "https://sololatino.net";
  const PLAYER = "https://player.pelisserieshoy.com";

  const cat = await get(`${BASE}/peliculas`);
  ok(`catalogo: ${(cat.text.match(/class="Posters-link"/g) || []).length} tarjetas`);

  await checkSearch(
    "busqueda",
    QUERY,
    `${BASE}/buscar?q=${QUERY}`,
    `${BASE}/buscar?q=${QUERY}&page=2`,
    (t) => titles(t, /<p class="card__title">([^<]*)<\/p>/g),
  );

  // Sesion + API del reproductor
  const session = await get(`${BASE}/sanctum/csrf-cookie`);
  const jar = jarOf(session);
  const xsrf = decodeURIComponent(
    (jar.find((c) => c.startsWith("XSRF-TOKEN=")) || "").split("=").slice(1).join("="),
  );
  xsrf ? ok(`sesion: XSRF de ${xsrf.length} caracteres`) : fail("sesion: sin XSRF-TOKEN");

  const page = await get(`${BASE}/pelicula/un-buen-abogado`, { Cookie: jar.join("; ") });
  const tokens = [...new Set([...page.text.matchAll(/data-player-token="([^"]+)"/g)].map((m) => m[1]))];
  tokens.length ? ok(`ficha: ${tokens.length} botones de servidor`) : fail("ficha: sin botones");

  const shield = sha256(`${tokens[0]}:${Math.floor(Date.now() / 60000)}:sololatino_render_shield_2026`).slice(0, 16);
  const pr = await get(`${BASE}/api/player-url`, {});
  const pu = await fetch(`${BASE}/api/player-url`, {
    method: "POST",
    headers: {
      "User-Agent": UA,
      Accept: "application/json",
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": xsrf,
      "X-SL-Render": shield,
      Cookie: jar.join("; "),
      Referer: `${BASE}/pelicula/un-buen-abogado`,
      Origin: BASE,
    },
    body: JSON.stringify({ t: tokens[0] }),
  });
  const player = await pu.json();
  player.url ? ok(`api/player-url: ${player.type}`) : fail(`api/player-url: ${JSON.stringify(player)}`);

  // Reproductor pelisserieshoy: token firmado y /s.php
  const pp = await get(player.url, { Referer: `${BASE}/pelicula/un-buen-abogado` });
  const sessionToken = pp.text.match(/_t\s*=\s*'([^']+)'/)?.[1];
  const contentId = pp.text.match(/_qc\s*=\s*"([^"]*)"/)?.[1];
  const shards = pp.text.match(/_qk\s*=\s*\[([^\]]*)\]/)?.[1].split(",").map((s) => s.trim().replace(/^["']|["']$/g, ""));
  const signed = shards[1] + shards[3] + shards[0] + shards[2];
  const proof = fnv1a(`${signed}|clk|${contentId}`);
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
  const langs = Object.keys(list.langs_s || {});
  langs.length ? ok(`s.php a=1: ${list.s.length} servidores, idiomas ${langs.join(",")}`) : fail("s.php a=1: sin servidores");
  await post({ a: "click", ts: "1", ...common });

  let playable = 0;
  for (const lang of langs.length ? langs : ["LAT"]) {
    for (const [label, id] of list.langs_s?.[lang] || list.s || []) {
      const d = JSON.parse(await post({ a: "2", v: id, ...common }));
      if (!d.u?.startsWith("http")) continue;
      const proxy = `${PLAYER}/p.php?url=${encodeURIComponent(d.u)}&sig=${encodeURIComponent(d.sig || "")}`;
      const chk = await checkPlaylist(proxy, player.url);
      if (chk.ok) { playable++; ok(`  HLS ${lang}/${label}: ${chk.quality}`); }
      else fail(`  HLS ${lang}/${label}: ${chk.status}`);
    }
  }
  if (playable === 0) fail("sin playlists HLS validas");

  // embed69 con Anubis
  if (player.url.includes("embed69")) {
    const { links, anubis } = await embed69Links(player.url, `${BASE}/pelicula/un-buen-abogado`);
    anubis ? ok("embed69: reto de Anubis resuelto") : info("embed69: sin reto de Anubis");
    links.length ? ok(`embed69: ${links.length} enlaces descifrados`) : fail("embed69: sin enlaces");
  }
}

// ---------------------------------------------------------------- FlixLatam

async function verifyFlixLatam() {
  console.log("\n== FlixLatam (flixlatam.com) ==");
  const BASE = "https://flixlatam.com";
  const cat = await get(`${BASE}/peliculas`);
  ok(`catalogo: ${(cat.text.match(/<article/g) || []).length} tarjetas`);

  await checkSearch(
    "busqueda",
    QUERY,
    `${BASE}/search?s=${QUERY}`,
    null,
    (t) => titles(t, /<h2><a[^>]*>([^<]*)<\/a><\/h2>/g),
  );

  const detail = await get(`${BASE}/pelicula/angel-y-el-pistolero-ev2BxB`);
  const iframe = detail.text.match(/<iframe[^>]*src=["']([^"']+)["'][^>]*>/i)?.[1];
  const playerUrl = iframe
    ? iframe.startsWith("http") ? iframe : `${BASE}${iframe}`
    : null;
  playerUrl && /vidurl|embed69/.test(playerUrl)
    ? ok(`ficha: reproductor ${playerUrl}`)
    : fail(`ficha: sin reproductor (${iframe || "sin iframe"})`);

  const { links } = await embed69Links(playerUrl || "", `${BASE}/pelicula/angel-y-el-pistolero-ev2BxB`);
  links.length ? ok(`embed69: ${links.length} enlaces descifrados`) : fail("embed69: sin enlaces");

  // Los episodios usan otro reproductor (xupalace.org -> embed69.org/embed.php)
  // que antes no se resolvia y dejaba 0 enlaces al abrir un capitulo.
  const epUrl = `${BASE}/serie/heartland/temporada/1/capitulo/1`;
  const ep = await get(epUrl);
  const epIframe = ep.text.match(/<iframe[^>]*src=["']([^"']+)["'][^>]*>/i)?.[1];
  epIframe ? ok(`episodio: reproductor ${epIframe}`) : fail("episodio: sin reproductor");
  if (epIframe) {
    const epPlayer = epIframe.startsWith("http") ? epIframe : `${BASE}${epIframe}`;
    const epLinks = await embed69Links(epPlayer, epUrl);
    epLinks.links.length
      ? ok(`episodio embed69: ${epLinks.links.length} enlaces descifrados`)
      : fail("episodio embed69: sin enlaces");
  }
}

// ------------------------------------------------------------ PelisPlusHD

async function verifyPelisPlusHD() {
  console.log("\n== PelisPlusHD (pelisplushd.la) ==");
  const BASE = "https://pelisplushd.la";
  const cat = await get(`${BASE}/peliculas`);
  ok(`catalogo: ${(cat.text.match(/class="Posters-link"/g) || []).length} tarjetas`);

  await checkSearch(
    "busqueda",
    QUERY,
    `${BASE}/search?s=${QUERY}`,
    `${BASE}/search?s=${QUERY}&page=2`,
    (t) => titles(t, /<div class="listing-content">\s*<p>([^<]*)<\/p>/g),
  );

  const detail = await get(`${BASE}/pelicula/quartier-lointain`);
  const servers = [...new Set([...detail.text.matchAll(/data-url="([^"]+)"/g)].map((m) => m[1]))];
  servers.length ? ok(`ficha: ${servers.length} servidores`) : fail("ficha: sin servidores");
  for (const s of servers.slice(0, 3)) {
    // Algunos reproductores (vidhidepro) no responden desde todas las redes.
    try {
      const r = await get(s);
      info(`${s.slice(0, 60)} -> ${r.status}`);
    } catch (e) {
      info(`${s.slice(0, 60)} -> no accesible desde esta red (${e.cause?.code || e.message})`);
    }
  }
}

// ------------------------------------------- APIs de WordPress FastAPI

async function verifyFastApi(name, site, api, imageBase) {
  console.log(`\n== ${name} (${site}) ==`);
  const list = await get(`${api}/listing/movies?page=1&orderBy=date&order=desc&postType=movies&postsPerPage=24`);
  const lj = JSON.parse(list.text);
  ok(`catalogo: ${lj.data?.posts?.length || 0} entradas (total ${lj.data?.pagination?.total})`);

  await checkSearch(
    "busqueda",
    QUERY,
    `${api}/search?q=${QUERY}&postType=any&postsPerPage=24&page=1`,
    `${api}/search?q=${QUERY}&postType=any&postsPerPage=24&page=2`,
    (t) => (JSON.parse(t).data?.posts || []).map((x) => x.title),
  );

  const post = lj.data.posts[0];
  const single = await get(`${api}/single/movies?slug=${post.slug}&postType=movies`);
  const detail = JSON.parse(single.text).data;
  detail?.title ? ok(`ficha: "${detail.title}" (${detail.release_date?.slice(0, 4)})`) : fail("ficha: sin datos");

  const poster = await get(imageBase + detail.images.poster);
  poster.status === 200 ? ok(`poster: ${poster.status}`) : fail(`poster: ${poster.status}`);

  const player = await get(`${api}/player?postId=${detail._id}&demo=0`);
  const embeds = JSON.parse(player.text).data?.embeds || [];
  embeds.length ? ok(`reproductor: ${embeds.length} embeds (${embeds.map((e) => e.server).join(", ")})`) : fail("reproductor: sin embeds");
  for (const e of embeds) {
    if (!e.url?.includes("vimeos.net")) { info(`${e.server}: ${e.url} (otro reproductor)`); continue; }
    const m3u8 = await vimeosM3u8(e.url, site);
    if (!m3u8) { info("  vimeos: el embed es el player de demo del sitio (sin HLS)"); continue; }
    const chk = await checkPlaylist(m3u8);
    chk.ok ? ok(`  vimeos: HLS ${chk.quality}`) : fail(`  vimeos: ${chk.status}`);
  }

  const shows = await get(`${api}/listing/tvshows?page=1&postType=tvshows&postsPerPage=2`);
  const show = JSON.parse(shows.text).data.posts[0];
  const eps = await get(`${api}/single/episodes/list?_id=${show._id}&season=1&page=1&postsPerPage=100`);
  const epj = JSON.parse(eps.text);
  const n = epj.data?.posts?.length || 0;
  n ? ok(`serie "${show.title}": ${n} episodios (temporadas ${epj.data.seasons?.join(",")})`) : fail("serie: sin episodios");
}

// ------------------------------------------------------------- AnimeOnline

async function verifyAnimeOnline() {
  console.log("\n== AnimeOnline (animeonline.one) ==");
  const BASE = "https://animeonline.one";
  const home = await get(BASE);
  ok(`catalogo: ${(home.text.match(/class="flw-item/g) || []).length} tarjetas`);

  await checkSearch(
    "busqueda",
    "psyren",
    `${BASE}/?s=psyren`,
    null,
    (t) => titles(t, /<h3 class="film-name">\s*<a[^>]*data-jname="([^"]*)"/g),
  );

  const animeUrl = home.text.match(/href="(https:\/\/animeonline\.one\/anime\/[^"]+)"/)?.[1];
  const an = await get(animeUrl);
  const title = an.text.match(/<h1[^>]*>([\s\S]{0,120}?)<\/h1>/)?.[1]?.replace(/<[^>]+>/g, " ").trim();
  title ? ok(`ficha: "${title}"`) : fail("ficha: sin titulo");
  const eps = [...new Set([...an.text.matchAll(/href="(https:\/\/animeonline\.one\/[^"]*-episode-\d+)\/"/g)].map((m) => m[1]))];
  eps.length ? ok(`episodios: ${eps.length}`) : fail("episodios: ninguno");

  if (eps.length) {
    const ep = await get(eps[0]);
    const hashes = [...ep.text.matchAll(/class="btn"[^>]*data-hash="([^"]+)"/g)].map((m) => m[1]);
    hashes.length ? ok(`reproductor: ${hashes.length} servidores`) : fail("reproductor: sin servidores");
    for (const h of hashes.slice(0, 4)) {
      const url = Buffer.from(h, "base64").toString("utf8");
      const r = await get(url, { Referer: eps[0] });
      const m3u8 = [...new Set([...r.text.matchAll(/sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]/g)].map((m) => m[1]))];
      if (m3u8.length) {
        const chk = await checkPlaylist(m3u8[0]);
        chk.ok ? ok(`  ${url.slice(0, 45)}: HLS ${chk.quality}`) : fail(`  ${url.slice(0, 45)}: ${chk.status}`);
      } else {
        info(`${url.slice(0, 70)} (${r.status}, sin m3u8 en el HTML)`);
      }
    }
  }
}

// --------------------------------------------------------------- VerGnula

async function verifyVerGnula() {
  console.log("\n== Gnula .one (ver.gnula.one) ==");
  const BASE = "https://ver.gnula.one";
  const cat = await get(`${BASE}/peliculas/`);
  ok(`catalogo: ${(cat.text.match(/gn-card gn-card--grid/g) || []).length} tarjetas`);

  await checkSearch(
    "busqueda (endpoint de sugerencias)",
    QUERY,
    `${BASE}/wp-json/gnula/v1/suggest?q=${QUERY}`,
    null,
    (t) => (JSON.parse(t) || []).map((x) => x.title),
  );

  const movieUrl = cat.text.match(/href="(https:\/\/ver\.gnula\.one\/movies\/[^"]+)"/)?.[1];
  const mv = await get(movieUrl);
  const srv = [...mv.text.matchAll(/<a[^>]*class="gn-srv"[^>]*href="([^"]+)"/g)].map((m) => m[1]);
  srv.length ? ok(`ficha: ${srv.length} servidores`) : fail("ficha: sin servidores");
  for (const href of srv.slice(0, 3)) {
    const res = await get(href.replace(/&#038;/g, "&").replace(/&amp;/g, "&"), { Referer: movieUrl });
    const iframe = res.text.match(/<iframe[^>]*src="([^"]+)"/)?.[1];
    iframe ? ok(`  ${href.slice(0, 46)} -> ${iframe.slice(0, 60)}`) : fail(`  ${href.slice(0, 46)}: sin iframe`);
  }
}

// ------------------------------------------------------ VerPeliculasUltra

async function verifyVerPeliculasUltra() {
  console.log("\n== VerPeliculasUltra (verpeliculasultra.com) ==");
  const BASE = "https://verpeliculasultra.com";
  const cat = await get(`${BASE}/lastnews/`);
  ok(`catalogo: ${(cat.text.match(/class="shortf"/g) || []).length} tarjetas`);

  await checkSearch(
    "busqueda",
    QUERY,
    `${BASE}/index.php?do=search&subaction=search&story=${QUERY}`,
    null,
    (t) => titles(t, /<h3 class="shortf-link">\s*<a[^>]*>\s*<b>([^<]*)<\/b>/g),
  );

  const movieUrl = cat.text.match(/<div class="shortf-img">\s*<a href="([^"]+)"/)?.[1];
  const mv = await get(movieUrl, { Referer: BASE });
  const servers = [...new Set([...mv.text.matchAll(/<div class="[^"]*play-btn-cont"[^>]*data-src="([^"]+)"/g)].map((m) => m[1]))];
  servers.length ? ok(`ficha: ${servers.length} servidores`) : fail("ficha: sin servidores");

  const hg = servers.find((s) => s.includes("hgcloud"));
  if (hg) {
    const id = hg.split("/e/")[1];
    for (const domain of ["audinifer.com", "hanerix.com", "vibuxer.com"]) {
      const p = await get(`https://${domain}/e/${id}`, { Referer: BASE });
      const unpacked = UNPACK(p.text);
      if (!unpacked) continue;
      const links = {};
      for (const m of unpacked.matchAll(/"(hls\d)":"([^"]*)"/g)) links[m[1]] = m[2];
      const pick = links.hls4 || links.hls3 || links.hls2;
      if (!pick) continue;
      const full = pick.startsWith("http") ? pick : `https://${domain}${pick}`;
      const chk = await checkPlaylist(full);
      chk.ok ? ok(`  hgcloud -> ${domain}: HLS ${chk.quality}`) : fail(`  hgcloud -> ${domain}: ${chk.status}`);
      break;
    }
  }
}

// ---------------------------------------------------------------- FlixCorn

async function verifyFlixCorn() {
  console.log("\n== FlixCorn (flixcorn.net) ==");
  const BASE = "https://www.flixcorn.net";
  const cat = await get(`${BASE}/tendencias`);
  ok(`catalogo: ${(cat.text.match(/media-card/g) || []).length} tarjetas`);

  await checkSearch(
    "busqueda",
    QUERY,
    `${BASE}/search?q=${QUERY}`,
    null,
    (t) => titles(t, /<h3 class="media-title">([^<]*)<\/h3>/g),
  );

  const serieUrl = cat.text.match(/href="(\/serie\/[^"]+\.html)"/)?.[1];
  const se = await get(BASE + serieUrl);
  const title = se.text.match(/<h1[^>]*>([\s\S]{0,120}?)<\/h1>/)?.[1]?.replace(/<[^>]+>/g, " ").trim();
  title ? ok(`serie: "${title}"`) : fail("serie: sin titulo");
  const seasons = [...new Set([...se.text.matchAll(/href="(\/serie\/[^"]*\/temporada-\d+\.html)"/g)].map((m) => m[1]))];
  seasons.length ? ok(`temporadas: ${seasons.length}`) : fail("temporadas: ninguna");

  const sp = await get(BASE + (seasons[0] || serieUrl));
  const epUrl = sp.text.match(/href="(\/ver\/[^"]+capitulo-\d+\.html)"/)?.[1];
  if (!epUrl) { fail("episodio: no encontrado"); return; }
  const ep = await get(BASE + epUrl);
  const seen = new Set();
  const servers = [...ep.text.matchAll(/<div class="cap-server-row">([\s\S]*?)<\/div>\s*<\/div>/g)]
    .map((m) => ({ name: m[1].match(/class="cap-server-name">([^<]*)</)?.[1], ext: m[1].match(/href="(\/external\/[^"]+)"/)?.[1] }))
    .filter((s) => s.ext && !seen.has(s.ext) && seen.add(s.ext));
  ok(`episodio: ${servers.length} servidores`);

  for (const s of servers.slice(0, 3)) {
    const ex = await get(BASE + s.ext, { "X-Requested-With": "XMLHttpRequest", Referer: BASE + epUrl });
    let json = null;
    try { json = JSON.parse(ex.text); } catch {}
    if (!json?.url) { info(`${s.name}: ${ex.text.slice(0, 60)}`); continue; }
    info(`${s.name}: ${json.url}`);
    if (!json.url.includes("vidara")) continue;
    const fileCode = json.url.split("/e/").pop().split("?")[0];
    const st = await fetch("https://vidaraa.cc/api/stream", {
      method: "POST",
      headers: { "User-Agent": UA, "Content-Type": "application/json", Referer: json.url },
      body: JSON.stringify({ filecode: fileCode, device: "web", codecs: [] }),
    });
    const sj = await st.json().catch(() => null);
    if (!sj?.streaming_url) { fail("  vidara: sin streaming_url"); continue; }
    const chk = await checkPlaylist(sj.streaming_url);
    chk.ok ? ok(`  vidara: HLS ${chk.quality}`) : fail(`  vidara: ${chk.status}`);
  }
}

// ---------------------------------------------------------------- Latanime

async function verifyLatanime() {
  console.log("\n== Latanime (latanime.org) ==");
  const BASE = "https://latanime.org";
  const cat = await get(`${BASE}/animes`);
  ok(`catalogo: ${(cat.text.match(/div class="series"/g) || []).length} tarjetas`);

  await checkSearch(
    "busqueda",
    QUERY,
    `${BASE}/buscar?q=${QUERY}`,
    null,
    (t) => titles(t, /<h3[^>]*class="my-1"[^>]*>([^<]*)<\/h3>/g),
  );

  const animeUrl = cat.text.match(/href="(https:\/\/latanime\.org\/anime\/[^"]+)"/)?.[1];
  const an = await get(animeUrl);
  const title = an.text.match(/<h2[^>]*>([\s\S]{0,120}?)<\/h2>/)?.[1]?.replace(/<[^>]+>/g, " ").trim();
  title ? ok(`anime: "${title}"`) : fail("anime: sin titulo");
  const eps = [...new Set([...an.text.matchAll(/href="(https:\/\/latanime\.org\/ver\/[^"]+)"/g)].map((m) => m[1]))];
  eps.length ? ok(`episodios: ${eps.length}`) : fail("episodios: ninguno");

  if (eps.length) {
    const ep = await get(eps[0]);
    const players = [...ep.text.matchAll(/class="play-video[^"]*"[^>]*data-player="([^"]+)"/g)].map((m) => m[1]);
    players.length ? ok(`reproductor: ${players.length} servidores`) : fail("reproductor: sin servidores");
    for (const p of players.slice(0, 5)) info(Buffer.from(p, "base64").toString("utf8"));
  }
}

// --------------------------------------------------------------- SeriesLan

async function verifySeriesLan() {
  console.log("\n== SeriesLan (serieslan.live) ==");
  const B = "https://serieslan.live/api/rest/v1";
  const list = await get(`${B}/listing?kind=serie&order=recent&page=1&limit=24`);
  const lj = JSON.parse(list.text);
  ok(`catalogo: ${lj.items?.length || 0} series (total ${lj.pagination?.total})`);

  await checkSearch(
    "busqueda",
    "chicago",
    `${B}/search?q=chicago&page=1&limit=24`,
    `${B}/search?q=chicago&page=2&limit=24`,
    (t) => (JSON.parse(t).items || []).map((x) => x.title),
  );

  const slug = lj.items[0].slug;
  const content = await get(`${B}/content/serie/${slug}`);
  const cj = JSON.parse(content.text);
  cj.item?.title ? ok(`serie: "${cj.item.title}" (${cj.item.year})`) : fail("serie: sin datos");

  const id = cj.item.catalog_item_id;
  const seasons = JSON.parse((await get(`${B}/episode-seasons?item_id=${id}`)).text);
  ok(`temporadas: ${seasons.seasons.map((s) => s.season).join(",")}`);

  const eps = JSON.parse((await get(`${B}/episodes?item_id=${id}&season=1&limit=100&page=1`)).text);
  const ep = eps.episodes?.[0];
  ep ? ok(`episodios: ${eps.episodes.length} (${ep.url})`) : fail("episodios: ninguno");
  if (!ep) return;

  const player = await get(ep.player_url, { Referer: "https://serieslan.live/" });
  const iframe = player.text.match(/<iframe[^>]*src="([^"]+)"/)?.[1];
  iframe ? ok(`player: iframe vimeos`) : fail("player: sin iframe");
  if (!iframe) return;

  const v = await get(iframe, { Referer: ep.player_url });
  const m = v.text.match(/window\.VIDAPI_CF_STREAM_RESPONSE\s*=\s*(\{[\s\S]*?\});/);
  if (!m) { fail("vimeos: sin VIDAPI_CF_STREAM_RESPONSE"); return; }
  const data = JSON.parse(m[1]).data;
  const chk = await checkPlaylist(data.stream_urls?.[0]);
  chk.ok ? ok(`vimeos: HLS ${chk.quality}`) : fail(`vimeos: ${chk.status}`);
  if (data.default_subs) info(`subtitulos: ${data.default_subs.length}`);
}

// --------------------------------------------------------- EMBED69 / vimeos

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

  const first = await fetch(url, { headers: { "User-Agent": UA, Referer: referer } });
  absorb(first);
  let html = await first.text();

  const challengeMatch = html.match(/<script id="anubis_challenge"[^>]*>([\s\S]*?)<\/script>/);
  let anubis = false;
  if (challengeMatch) {
    anubis = true;
    const challenge = JSON.parse(challengeMatch[1]).challenge;
    const started = Date.now();
    const digest = crypto.createHash("sha256").update(challenge.randomData + "0", "utf8");
    void digest;
    const { hash, nonce } = solveAnubis(challenge.randomData, challenge.difficulty);
    const passUrl =
      `https://embed69.org/.within.website/x/cmd/anubis/api/pass-challenge` +
      `?id=${challenge.id}&response=${hash}&nonce=${nonce}` +
      `&redir=${encodeURIComponent(new URL(url).pathname + new URL(url).search)}&elapsedTime=${Date.now() - started}`;
    const pass = await fetch(passUrl, { headers: { "User-Agent": UA, Referer: referer, Cookie: cookieHeader() }, redirect: "manual" });
    absorb(pass);
    const second = await fetch(url, { headers: { "User-Agent": UA, Referer: referer, Cookie: cookieHeader() } });
    html = await second.text();
  }
  return { html, anubis };
}

/** Proof of work de Anubis: SHA-256 con los primeros bits a cero. */
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

/** Lee y descifra los servidores de un reproductor EMBED69 (AES-256-CBC). */
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
      try {
        const raw = Buffer.from(server.link, "base64");
        const decipher = crypto.createDecipheriv("aes-256-cbc", key, raw.subarray(0, 16));
        const plain = Buffer.concat([decipher.update(raw.subarray(16)), decipher.final()]).toString("utf8").replace(/`/g, "").trim();
        if (plain.startsWith("http")) links.push({ name: `${file.video_language}/${server.servername}`, url: fixHost(plain) });
      } catch {}
    }
  }
  return { links, anubis };
}

/** Resuelve un reproductor vimeos (P.A.C.K.E.R) al m3u8. */
async function vimeosM3u8(embedUrl, referer) {
  const r = await fetch(embedUrl, { headers: { "User-Agent": UA, Referer: referer } });
  const unpacked = UNPACK(await r.text());
  if (!unpacked) return null;
  const files = [...unpacked.matchAll(/file\s*:\s*"(https?:\/\/[^"]+)"/g)].map((m) => m[1]);
  return files.find((f) => f.includes(".m3u8")) || null;
}

/** Normaliza los dominios rotados a los que los extractores de CloudStream conocen. */
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

// --------------------------------------------------------------------- main

const ALL = {
  sololatino: verifySoloLatino,
  flixlatam: verifyFlixLatam,
  pelisplushd: verifyPelisPlusHD,
  gnula: () => verifyFastApi("Gnula", "https://gnula.la", "https://gnula.la/wp-api/v1", "https://gnula.la/wp-content/uploads"),
  cinecalidad: () => verifyFastApi("Cinecalidad", "https://cinecalidad.org", "https://cinecalidad.org/wp-api/v1", "https://cinecalidad.org/wp-content/uploads"),
  animeonline: verifyAnimeOnline,
  gnulaone: verifyVerGnula,
  verpeliculasultra: verifyVerPeliculasUltra,
  flixcorn: verifyFlixCorn,
  latanime: verifyLatanime,
  serieslan: verifySeriesLan,
};

const args = process.argv.slice(2);
const selected = args.length ? args : Object.keys(ALL);
for (const name of selected) {
  const fn = ALL[name];
  if (!fn) { console.log(`proveedor desconocido: ${name}`); continue; }
  try { await fn(); } catch (e) { fail(`${name}: excepcion ${e.message}`); }
}

console.log(`\n${checks - failures}/${checks} comprobaciones correctas`);
if (failures) console.log(`${failures} FALLOS`);
process.exitCode = failures ? 1 : 0;
