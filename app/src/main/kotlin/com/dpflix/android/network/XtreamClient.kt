package com.dpflix.android.network

import com.dpflix.android.model.Channel
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Client Xtream Codes (§4.2 Étape 2a, §4.6, §7 étape 3 du cahier des charges).
 *
 * Rôle : authentification + récupération des chaînes live via l'API `player_api.php`,
 * exposées sous forme de [Channel] — le même modèle que produit [com.dpflix.android.parser.M3uParser],
 * pour que l'accueil (§4.4) et le lecteur n'aient jamais à distinguer la provenance
 * d'une chaîne.
 *
 * Contrairement à `M3uParser` (fonction pure), ce client fait forcément de l'IO réseau :
 * l'authentification Xtream n'est pas un simple parsing de texte déjà récupéré, elle
 * nécessite d'interroger le serveur. Les fonctions sont `suspend` et s'exécutent sur
 * `Dispatchers.IO`.
 *
 * Volontairement hors périmètre à cette sous-étape (comme pour 3b) :
 * - l'usage de `includeTvChannels` (case à cocher §4.2) : ce client récupère toujours
 *   les chaînes live si on le lui demande, c'est à la couche repository (étape 4) de
 *   décider d'appeler [fetchLiveChannels] ou non selon la playlist ;
 * - la récupération de l'EPG lui-même (juste son URL, via [buildEpgUrl]) : le
 *   téléchargement + parsing XMLTV arrivent à l'étape 3d ;
 * - VOD / séries : hors périmètre du projet (§1, §4.2).
 */
class XtreamClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Défauts OkHttp (10s partout) trop courts pour certains panels Xtream : serveur
        // distant/surchargé lent à établir la connexion, ou réponse `get_live_streams`
        // volumineuse (plusieurs milliers de chaînes) lente à transférer intégralement.
        // `readTimeout` plus généreux que `connectTimeout` : une connexion qui ne
        // s'établit pas du tout après 20s a très peu de chances d'aboutir (mauvais
        // port/hôte injoignable), alors qu'une réponse volumineuse peut légitimement
        // prendre plus longtemps à arriver en totalité une fois la connexion établie.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // TLS permissif (2026-07-22) : mêmes raisons que IptvHttpDataSourceFactory —
        // certains panels servent un certificat auto-signé/invalide sur player_api.php
        // lui-même, ce qui ferait échouer l'authentification avant même d'arriver à la
        // lecture du flux vidéo. Voir PermissiveTls pour le compromis assumé.
        .sslSocketFactory(PermissiveTls.sslSocketFactory, PermissiveTls.trustManager)
        .hostnameVerifier(PermissiveTls.hostnameVerifier)
        .build()
) {

    /**
     * Authentifie les [credentials] auprès du serveur (appel de base de `player_api.php`,
     * sans paramètre `action`). Utilisé par le formulaire d'onboarding (§4.2 Étape 2a)
     * pour valider la saisie avant d'enregistrer la playlist.
     */
    suspend fun authenticate(credentials: XtreamCredentials): XtreamResult<XtreamUserInfo> =
        withContext(Dispatchers.IO) {
            when (val outcome = executeGet(playerApiUrl(credentials))) {
                is GetOutcome.NetworkError -> XtreamResult.NetworkError(outcome.message)
                is GetOutcome.HttpError -> XtreamResult.ServerError(httpErrorMessage(outcome.code))
                is GetOutcome.Body -> parseAuthBody(outcome.text)
            }
        }

    /**
     * Récupère les chaînes live du compte (`get_live_categories` + `get_live_streams`),
     * après vérification de l'authentification. Retourne les mêmes types d'erreur que
     * [authenticate] en cas d'échec, pour un traitement UI uniforme.
     *
     * @param playlistId id de la [com.dpflix.android.model.Playlist] à laquelle rattacher les chaînes produites.
     */
    suspend fun fetchLiveChannels(
        credentials: XtreamCredentials,
        playlistId: String
    ): XtreamResult<XtreamLiveChannelsData> = withContext(Dispatchers.IO) {
        val authResult = when (val outcome = executeGet(playerApiUrl(credentials))) {
            is GetOutcome.NetworkError -> return@withContext XtreamResult.NetworkError(outcome.message)
            is GetOutcome.HttpError -> return@withContext XtreamResult.ServerError(httpErrorMessage(outcome.code))
            is GetOutcome.Body -> parseAuthBody(outcome.text)
        }
        if (authResult !is XtreamResult.Success) {
            @Suppress("UNCHECKED_CAST")
            return@withContext authResult as XtreamResult<XtreamLiveChannelsData>
        }

        val categoryNames = when (
            val outcome = executeGet(playerApiUrl(credentials, action = "get_live_categories"))
        ) {
            is GetOutcome.NetworkError -> return@withContext XtreamResult.NetworkError(outcome.message)
            is GetOutcome.HttpError -> return@withContext XtreamResult.ServerError(httpErrorMessage(outcome.code))
            is GetOutcome.Body -> parseCategories(outcome.text)
        }

        val (channels, rawStreamCount) = when (
            val outcome = executeGet(playerApiUrl(credentials, action = "get_live_streams"))
        ) {
            is GetOutcome.NetworkError -> return@withContext XtreamResult.NetworkError(outcome.message)
            is GetOutcome.HttpError -> return@withContext XtreamResult.ServerError(httpErrorMessage(outcome.code))
            is GetOutcome.Body -> parseLiveStreams(outcome.text, credentials, playlistId, categoryNames)
                ?: return@withContext XtreamResult.ServerError(unparsableStreamsMessage(outcome.text))
        }

        XtreamResult.Success(
            XtreamLiveChannelsData(
                channels = channels,
                detectedEpgUrl = buildEpgUrl(credentials),
                rawStreamCount = rawStreamCount
            )
        )
    }

    /**
     * URL de flux jouable pour une chaîne live, au format standard Xtream
     * `/live/{user}/{pass}/{streamId}.{ext}`. Extension par défaut `m3u8` (HLS,
     * cohérent avec le choix ExoPlayer/Media3 du §2) ; le serveur peut annoncer une
     * autre extension via `container_extension` dans `get_live_streams`.
     */
    fun buildStreamUrl(
        credentials: XtreamCredentials,
        streamId: String,
        containerExtension: String = DEFAULT_STREAM_EXTENSION
    ): String {
        val ext = containerExtension.trim().trimStart('.').ifBlank { DEFAULT_STREAM_EXTENSION }
        return "${baseUrl(credentials.serverUrl)}/live/${encode(credentials.username)}/${encode(credentials.password)}/$streamId.$ext"
    }

    /**
     * URL de l'EPG "lié au compte" (§4.6, priorité 2 pour une playlist Xtream) :
     * route standard `xmltv.php`. Le téléchargement/parsing de cette URL arrive à
     * l'étape 3d ; ici on ne fait que la construire.
     */
    fun buildEpgUrl(credentials: XtreamCredentials): String =
        "${baseUrl(credentials.serverUrl)}/xmltv.php?username=${encode(credentials.username)}&password=${encode(credentials.password)}"

    // --- Requête HTTP ---------------------------------------------------------------

    private fun playerApiUrl(
        credentials: XtreamCredentials,
        action: String? = null
    ): String {
        val builder = StringBuilder(baseUrl(credentials.serverUrl))
            .append("/player_api.php")
            .append("?username=").append(encode(credentials.username))
            .append("&password=").append(encode(credentials.password))
        if (action != null) {
            builder.append("&action=").append(action)
        }
        return builder.toString()
    }

    private fun executeGet(url: String): GetOutcome = try {
        // Cascade de User-Agent (2026-07-22, voir NetworkConstants.USER_AGENT_FALLBACKS) :
        // remplace l'ancien header unique forcé "IPTVSmartersPlayer" — on essaie d'abord
        // sans en-tête personnalisé, puis les signatures connues, jusqu'à obtenir une
        // réponse HTTP réussie. Un panel qui filtre par User-Agent bloque en général de
        // façon franche (code d'erreur, pas un simple corps vide), donc `isSuccessful`
        // suffit comme critère pour passer à la tentative suivante.
        var lastOutcome: GetOutcome = GetOutcome.NetworkError("Aucune tentative effectuée")
        for (userAgent in NetworkConstants.USER_AGENT_FALLBACKS) {
            val requestBuilder = Request.Builder().url(url).get()
            if (userAgent != null) requestBuilder.header("User-Agent", userAgent)
            val outcome = httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    GetOutcome.HttpError(response.code)
                } else {
                    GetOutcome.Body(response.body?.string().orEmpty())
                }
            }
            if (outcome is GetOutcome.Body) return@try outcome
            lastOutcome = outcome
        }
        lastOutcome
    } catch (e: IOException) {
        GetOutcome.NetworkError(e.message ?: "Erreur réseau")
    } catch (e: IllegalArgumentException) {
        // URL malformée (adresse serveur invalide saisie par l'utilisateur, §4.2).
        GetOutcome.NetworkError(e.message ?: "Adresse de serveur invalide")
    }

    private sealed class GetOutcome {
        data class Body(val text: String) : GetOutcome()
        data class HttpError(val code: Int) : GetOutcome()
        data class NetworkError(val message: String) : GetOutcome()
    }

    private fun httpErrorMessage(code: Int) = "Le serveur a répondu avec le code $code"

    // --- Parsing JSON (tolérant : l'API Xtream mélange types string/int selon les panels) ---

    private fun parseAuthBody(body: String): XtreamResult<XtreamUserInfo> {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return XtreamResult.ServerError("Réponse du serveur illisible (JSON invalide)")
        }

        val userInfo = json.optJSONObject("user_info")
            ?: return XtreamResult.InvalidCredentials()

        if (userInfo.optIntFlexible("auth") != 1) {
            return XtreamResult.InvalidCredentials()
        }

        val status = userInfo.optString("status", "Active").ifBlank { "Active" }
        if (!status.equals("Active", ignoreCase = true)) {
            return XtreamResult.AccountInactive(status)
        }

        val expDateSeconds = userInfo.optStringOrNull("exp_date")?.toLongOrNull()

        return XtreamResult.Success(
            XtreamUserInfo(
                username = userInfo.optString("username"),
                status = status,
                expDateMillis = expDateSeconds?.times(1000L),
                isTrial = userInfo.optIntFlexible("is_trial") == 1,
                maxConnections = userInfo.optStringOrNull("max_connections")?.toIntOrNull()
            )
        )
    }

    private fun parseCategories(body: String): Map<String, String> = try {
        val array = JSONArray(body)
        buildMap {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optStringOrNull("category_id") ?: continue
                val name = obj.optStringOrNull("category_name") ?: continue
                put(id, name)
            }
        }
    } catch (e: JSONException) {
        emptyMap()
    }

    private fun parseLiveStreams(
        body: String,
        credentials: XtreamCredentials,
        playlistId: String,
        categoryNames: Map<String, String>
    ): Pair<List<Channel>, Int>? {
        val trimmed = body.trim()
        // Certains panels renvoient "{}" ou une chaîne vide quand le compte n'a
        // simplement aucune chaîne live (plutôt que "[]") : ce n'est pas une erreur.
        if (trimmed.isEmpty() || trimmed == "{}" || trimmed.equals("null", ignoreCase = true)) {
            return emptyList<Channel>() to 0
        }

        val array = try {
            JSONArray(trimmed)
        } catch (e: JSONException) {
            // Le serveur peut répondre par un objet JSON (page d'erreur/auth du panel,
            // action non reconnue...) plutôt qu'un tableau, ou par du HTML/texte brut
            // (mauvais port, reverse-proxy, etc.) : dans les deux cas, ce n'est PAS la
            // même chose qu'"aucune chaîne" et l'appelant doit pouvoir le distinguer
            // (voir [XtreamClient.fetchLiveChannels]) plutôt que de recevoir silencieusement
            // une liste vide indiscernable d'un compte réellement sans chaîne.
            return null
        }

        val channels = mutableListOf<Channel>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val streamId = obj.optStringOrNull("stream_id") ?: continue
            val containerExtension = obj.optStringOrNull("container_extension") ?: DEFAULT_STREAM_EXTENSION
            val categoryId = obj.optStringOrNull("category_id")
            val sequentialNumber = i + 1

            channels += Channel(
                // Id déterministe (plutôt qu'un UUID aléatoire comme M3uParser) : un
                // rafraîchissement Xtream doit retrouver la même chaîne pour ne pas
                // perdre la numérotation personnalisée (§5.3) ou la dernière chaîne
                // regardée (§4.3) qui lui sont associées ailleurs (étape 4).
                id = "xtream-$playlistId-$streamId",
                playlistId = playlistId,
                name = obj.optStringOrNull("name") ?: "Chaîne $streamId",
                streamUrl = buildStreamUrl(credentials, streamId, containerExtension),
                logoUrl = obj.optStringOrNull("stream_icon"),
                category = categoryId?.let { categoryNames[it] },
                tvgId = obj.optStringOrNull("epg_channel_id"),
                originalNumber = obj.optIntFlexible("num").takeIf { it > 0 } ?: sequentialNumber
            )
        }
        return channels to array.length()
    }

    /**
     * Message d'erreur affiché quand `get_live_streams` ne renvoie pas un JSON
     * exploitable : inclut un extrait de la réponse brute pour permettre à
     * l'utilisateur (ou à nous, en debug) d'identifier la vraie cause (page d'erreur
     * HTML du panel, mauvais port, action bloquée par un reverse-proxy...) plutôt que
     * de se retrouver avec un simple "0 chaînes" sans explication.
     */
    private fun unparsableStreamsMessage(rawBody: String): String {
        val snippet = rawBody.trim().take(120).ifBlank { "(réponse vide)" }
        return "Réponse du serveur illisible pour la liste des chaînes : $snippet"
    }

    private fun baseUrl(server: String): String = server.trim().trimEnd('/')

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Lit un champ pouvant être un `Int`, un `Boolean` ou une `String` selon le panel Xtream. */
    private fun JSONObject.optIntFlexible(key: String): Int {
        if (!has(key) || isNull(key)) return 0
        return when (val value = get(key)) {
            is Int -> value
            is Boolean -> if (value) 1 else 0
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
    }

    /** Lit un champ texte en normalisant les valeurs absentes/vides/`null` JSON en `null` Kotlin. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() }
    }

    private companion object {
        const val DEFAULT_STREAM_EXTENSION = "m3u8"
    }
}
