package com.anonimo00.shared

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Utilidades de busqueda compartidas por todos los proveedores.
 *
 * Los catalogos latinos guardan el titulo local ("Buscando a Nemo") pero no
 * buscan por el titulo original ("Finding Nemo"), y al reves. Esto hace que
 * buscar un nombre alternativo no devuelva nada. Este objeto arregla eso:
 *
 *  1. Normaliza acentos, puntuacion y articulos para comparar títulos.
 *  2. Prueba variantes de la consulta cuando la busqueda directa da pocos
 *     resultados.
 *  3. Usa TMDB (la misma key publica que trae CloudStream) para obtener los
 *     títulos alternativos en español y el original, y busca tambien por ellos.
 *  4. Guarda en memoria los alias que va viendo, de modo que una busqueda
 *     posterior por cualquier nombre alternativo encuentre el resultado.
 *  5. Fusiona, quita duplicados y ordena por relevancia.
 */
object SearchHelper {
    /** TMDB key publica incluida en el propio codigo de CloudStream. */
    private const val TMDB_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val TMDB_URL = "https://api.themoviedb.org/3/search/multi"

    /** No merece la pena gastar peticiones extra en consultas cortas. */
    private const val MIN_QUERY_LENGTH = 4

    /** Por debajo de esto se intentan variantes y titulos alternativos. */
    private const val MIN_RESULTS = 4

    /** Maximo de resultados que se devuelven al final. */
    private const val MAX_RESULTS = 60

    private val ARTICLES = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas",
        "the", "a", "an", "de", "del", "du", "des",
    )
    private val COMBINING = Regex("[\\u0300-\\u036f]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val YEAR = Regex("""\b(?:18|19|20)\d{2}\b""")
    private val ALIAS_SPLIT = Regex(
        """\s*(?:\(|\)|\[|\]|,|;|/|\||\s+aka\s+|\s+o\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    // ------------------------------------------------------------ normalizacion

    /** Minusculas, sin acentos, sin puntuacion. */
    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val plain = decomposed.replace(COMBINING, "")
        return plain.replace(NON_ALNUM, " ").trim().replace(Regex("\\s+"), " ")
    }

    /** Normalizada y sin articulos ("El camino" -> "camino"). */
    fun key(input: String): String {
        val words = normalize(input).split(' ').filter { it.isNotBlank() }
        val withoutArticles = words.filterNot { it in ARTICLES }.joinToString(" ")
        return withoutArticles.ifBlank { words.joinToString(" ") }
    }

    /** Posibles nombres alternativos que aparecen dentro de un titulo. */
    fun aliasCandidates(title: String): List<String> {
        val cleaned = YEAR.replace(title, " ").trim()
        return ALIAS_SPLIT.split(cleaned)
            .map { it.trim() }
            .filter { it.length >= 3 && !it.all { char -> char.isDigit() } }
            .distinct()
    }

    /** Variantes baratas de la consulta (sin peticiones de red). */
    fun queryVariants(query: String): List<String> {
        val raw = query.trim()
        if (raw.length < MIN_QUERY_LENGTH) return listOf(raw)
        val out = linkedSetOf(raw)
        val plain = normalize(raw)
        if (plain.length >= MIN_QUERY_LENGTH) out += plain
        val noArticles = key(raw)
        if (noArticles.length >= MIN_QUERY_LENGTH) out += noArticles
        // Si el usuario escribio varios nombres separados, probar cada parte.
        if (raw.contains(" aka ", true) || raw.contains(" / ") || raw.contains(" - ")) {
            aliasCandidates(raw).forEach { candidate ->
                if (candidate.length >= MIN_QUERY_LENGTH) out += candidate
            }
        }
        return out.toList()
    }

    // ------------------------------------------------------------ relevancia

    private fun matchScore(queryKey: String, candidateKey: String): Int {
        if (queryKey.isBlank() || candidateKey.isBlank()) return 0
        if (candidateKey == queryKey) return 1000
        if (candidateKey.startsWith(queryKey)) return 800
        if (candidateKey.contains(queryKey)) return 600
        val queryWords = queryKey.split(' ').toSet()
        val candidateWords = candidateKey.split(' ').toSet()
        val common = queryWords.intersect(candidateWords).size
        if (common == 0) return 0
        val coverage = common * 100 / queryWords.size
        return 200 + coverage + if (queryWords.all { it in candidateWords }) 50 else 0
    }

    /**
     * Junta todas las listas, quita duplicados por URL y ordena por relevancia
     * teniendo en cuenta TODAS las consultas que se usaron (la original y sus
     * alternativas), para que un resultado encontrado por un titulo alternativo
     * no quede al final de la lista.
     */
    fun <T : SearchResponse> rank(queries: List<String>, items: List<T>): List<T> {
        val keys = queries.map { key(it) }.filter { it.isNotBlank() }.ifEmpty { listOf("") }
        val seen = HashSet<String>()
        return items
            .filter { item ->
                val urlKey = item.url.trim().trimEnd('/').lowercase(Locale.ROOT)
                urlKey.isNotBlank() && seen.add(urlKey)
            }
            .map { item -> item to keys.maxOf { q -> matchScore(q, key(item.name)) } }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }
                .thenBy { it.first.name.lowercase(Locale.ROOT) })
            .map { it.first }
            .take(MAX_RESULTS)
    }

    // ------------------------------------------------------------ indice de alias

    private val aliasIndex = ConcurrentHashMap<String, ConcurrentHashMap<String, SearchResponse>>()

    private fun bucket(provider: String): ConcurrentHashMap<String, SearchResponse> =
        aliasIndex.getOrPut(provider) { ConcurrentHashMap() }

    /** Recuerda que `names` son nombres alternativos de `response`. */
    fun remember(provider: String, response: SearchResponse, vararg names: String?) {
        val map = bucket(provider)
        for (name in names) {
            if (name.isNullOrBlank()) continue
            val k = key(name)
            if (k.length >= 3) map.putIfAbsent(k, response)
        }
    }

    /** Recuerda el titulo y los alias que se puedan extraer de el. */
    fun rememberTitle(provider: String, response: SearchResponse) {
        remember(provider, response, response.name)
        aliasCandidates(response.name).forEach { remember(provider, response, it) }
    }

    /** Busca en el indice local de alias acumulados durante la sesion. */
    fun lookup(provider: String, query: String): List<SearchResponse> {
        val map = bucket(provider)
        if (map.isEmpty()) return emptyList()
        val q = key(query)
        if (q.length < 3) return emptyList()
        return map.entries
            .filter { (alias, _) -> alias == q || alias.contains(q) || q.contains(alias) }
            .map { it.value }
            .distinctBy { it.url.trim().trimEnd('/').lowercase(Locale.ROOT) }
    }

    // ------------------------------------------------------------ TMDB

    /**
     * Titulos alternativos de TMDB: el localizado en español y el original.
     * Se pide `es-ES` para que "Finding Nemo" devuelva "Buscando a Nemo".
     */
    private suspend fun tmdbTitles(query: String): List<String> {
        return try {
            val response = app.get(
                TMDB_URL,
                params = mapOf(
                    "api_key" to TMDB_KEY,
                    "query" to query,
                    "language" to "es-ES",
                ),
                cacheTime = 60 * 24,
            )
            val results = JSONObject(response.text).optJSONArray("results") ?: return emptyList()
            val out = linkedSetOf<String>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val media = item.optString("media_type")
                if (media != "movie" && media != "tv") continue
                for (field in arrayOf("title", "name", "original_title", "original_name")) {
                    val value = item.optString(field).trim()
                    if (value.length >= MIN_QUERY_LENGTH) out += value
                }
            }
            out.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ busqueda

    /**
     * Busqueda inteligente. `siteSearch` es la busqueda original del proveedor;
     * se llama con la consulta del usuario y, si hace falta, con variantes y
     * titulos alternativos.
     */
    suspend fun smartSearch(
        provider: String,
        query: String,
        siteSearch: suspend (String) -> List<SearchResponse>,
    ): List<SearchResponse> {
        val original = query.trim()
        if (original.length < 2) return emptyList()

        val queries = mutableListOf(original)
        val lists = mutableListOf<List<SearchResponse>>()

        suspend fun safeSearch(term: String): List<SearchResponse> = try {
            siteSearch(term)
        } catch (_: Exception) {
            emptyList()
        }

        val direct = safeSearch(original)
        if (direct.isNotEmpty()) lists += direct

        // Solo gastamos peticiones extra si la busqueda directa fue pobre Y
        // ninguno de sus resultados coincide claramente con la consulta.
        val bestDirectScore = direct.maxOfOrNull { matchScore(key(original), key(it.name)) } ?: 0
        if (direct.size < MIN_RESULTS && bestDirectScore < 600 && original.length >= MIN_QUERY_LENGTH) {
            for (variant in queryVariants(original).drop(1)) {
                val more = safeSearch(variant)
                if (more.isNotEmpty()) {
                    queries += variant
                    lists += more
                }
                if (lists.sumOf { it.size } >= MIN_RESULTS) break
            }

            if (lists.sumOf { it.size } < MIN_RESULTS) {
                for (title in tmdbTitles(original).take(4)) {
                    if (key(title) == key(original)) continue
                    val more = safeSearch(title)
                    if (more.isNotEmpty()) {
                        queries += title
                        lists += more
                    }
                    if (lists.sumOf { it.size } >= MIN_RESULTS) break
                }
            }
        }

        // Resultados recordados en sesiones anteriores de esta misma app.
        val local = lookup(provider, original)
        if (local.isNotEmpty()) lists += local

        val merged = rank(queries, lists.flatten())
        merged.forEach { rememberTitle(provider, it) }
        return merged
    }

    /**
     * Variante rapida para `quickSearch`: solo la busqueda directa y el indice
     * local, sin peticiones a TMDB (la app llama mientras se escribe).
     */
    suspend fun quickSearch(
        provider: String,
        query: String,
        siteSearch: suspend (String) -> List<SearchResponse>,
    ): List<SearchResponse> {
        val original = query.trim()
        if (original.length < 2) return emptyList()
        val direct = try { siteSearch(original) } catch (_: Exception) { emptyList() }
        val local = lookup(provider, original)
        val merged = rank(listOf(original), direct + local)
        merged.forEach { rememberTitle(provider, it) }
        return merged
    }

    /**
     * Registra los alias que vengan en los datos del sitio (por ejemplo el
     * `original_title` de la API de gnula y cinecalidad).
     */
    fun rememberAliases(provider: String, response: SearchResponse, vararg aliases: String?) {
        remember(provider, response, *aliases)
    }
}
