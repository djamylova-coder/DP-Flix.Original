package com.dpflix.android.network

import com.dpflix.android.model.Channel
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
        // Fix (2026-07-25) : certains panels (ex. constatés depuis 11000 Chelles) mettent
        // jusqu'à une minute à établir la connexion — parfois lors de CHAQUE tentative de
        // la cascade User-Agent (executeGet peut donc légitimement prendre plusieurs
        // minutes bout en bout pour un seul appel `player_api.php`). Les anciens délais
        // (20s/45s/20s) faisaient donc échouer prématurément des panels par ailleurs
        // valides, juste lents à répondre. Nouveaux délais généreux, chacun dépassant
        // 2 minutes : mieux vaut laisser l'utilisateur attendre (avec un indicateur de
        // chargement côté UI) que déclarer un panel valide injoignable.
        .connectTimeout(150, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(150, TimeUnit.SECONDS)
        // callTimeout = budget total (connexion + écriture + lecture + éventuelles
        // redirections) pour un appel donné ; volontairement encore plus large que chacun
        // des délais individuels ci-dessus pour ne jamais couper une requête par ce
        // timeout global avant que les délais spécifiques n'aient eu leur chance.
        .callTimeout(240, TimeUnit.SECONDS)
        // Un panel lent à connecter l'est souvent de façon intermittente (serveur
        // mutualisé/surchargé) : retenter automatiquement la connexion TCP au lieu
        // d'abandonner sur le premier échec augmente les chances d'aboutir sans même
        // solliciter la cascade de User-Agent.
        .retryOnConnectionFailure(true)
        // Force HTTP/1.1 (2026-07-25) : beaucoup de panels Xtream tournent derrière un
        // reverse-proxy ou un serveur PHP/nginx ancien/mal configuré dont la négociation
        // HTTP/2 échoue silencieusement ou produit des réponses tronquées, alors que le
        // même serveur répond normalement en HTTP/1.1 (le protocole que parlent
        // naturellement ces panels). Option la plus permissive : ne pas risquer une
        // négociation HTTP/2 qu'un panel bricolé gère mal.
        .protocols(listOf(Protocol.HTTP_1_1))
        // Specs de connexion les plus permissives possible pour TLS (2026-07-25) : en
        // plus du TrustManager/HostnameVerifier permissifs ci-dessous (qui gèrent la
        // confiance du certificat), COMPATIBLE_TLS accepte des versions TLS et suites de
        // chiffrement plus anciennes que MODERN_TLS (le défaut OkHttp) — nécessaire pour
        // les panels tournant sur de vieilles piles OpenSSL. CLEARTEXT reste nécessaire
        // pour les panels en simple http://.
        .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .followRedirects(true)
        .followSslRedirects(true)
        // TLS permissif (2026-07-22, étendu 2026-07-25) : mêmes raisons que
        // IptvHttpDataSourceFactory — certains panels servent un certificat auto-signé/
        // invalide sur player_api.php lui-même, ce qui ferait échouer l'authentification
        // avant même d'arriver à la lecture du flux vidéo. Voir PermissiveTls pour le
        // compromis assumé et la prise en charge des vieilles versions TLS (1.0/1.1).
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
            val outcome = executeGet(
                playerApiUrl(credentials, action = "get_live_categories"),
                continueOnEmptyArray = true
            )
        ) {
            is GetOutcome.NetworkError -> return@withContext XtreamResult.NetworkError(outcome.message)
            is GetOutcome.HttpError -> return@withContext XtreamResult.ServerError(httpErrorMessage(outcome.code))
            is GetOutcome.Body -> parseCategories(outcome.text)
        }

        var (channels, rawStreamCount) = when (
            val outcome = executeGet(
                playerApiUrl(credentials, action = "get_live_streams"),
                continueOnEmptyArray = true
            )
        ) {
            is GetOutcome.NetworkError -> return@withContext XtreamResult.NetworkError(outcome.message)
            is GetOutcome.HttpError -> return@withContext XtreamResult.ServerError(httpErrorMessage(outcome.code))
            is GetOutcome.Body -> parseLiveStreams(outcome.text, credentials, playlistId, categoryNames)
                ?: return@withContext XtreamResult.ServerError(unparsableStreamsMessage(outcome.text))
        }

        // Fix (2026-07-25) : repli par catégorie pour les gros panels. Certains panels
        // Xtream (vus sur des comptes à très nombreuses chaînes) répondent volontairement
        // "[]" à `get_live_streams` SANS `category_id` — même après la cascade de
        // User-Agent et le fix "catalogue restreint" ci-dessus — pour éviter de servir
        // des dizaines de milliers d'entrées en un seul appel non filtré, mais répondent
        // normalement dès qu'on précise une catégorie. Sans ce repli, ces panels
        // affichaient "0 chaîne" alors que le compte est parfaitement valide et chargé.
        // Déclenché UNIQUEMENT si l'appel global n'a strictement rien renvoyé (pas de
        // fausse activation sur un compte réellement vide sans catégories) ; requêtes
        // séquentielles catégorie par catégorie, acceptables ici car ce chemin ne
        // s'exécute que quand le chemin rapide a déjà échoué.
        if (channels.isEmpty() && rawStreamCount == 0 && categoryNames.isNotEmpty()) {
            val perCategoryChannels = mutableListOf<Channel>()
            var perCategoryRawCount = 0
            for (categoryId in categoryNames.keys) {
                val categoryOutcome = executeGet(
                    playerApiUrl(credentials, action = "get_live_streams", categoryId = categoryId),
                    continueOnEmptyArray = true
                )
                val (categoryChannels, categoryRawCount) = when (categoryOutcome) {
                    is GetOutcome.Body -> parseLiveStreams(categoryOutcome.text, credentials, playlistId, categoryNames)
                        ?: continue // Catégorie illisible isolément : ignorée, pas fatale pour les autres.
                    else -> continue // Erreur réseau/HTTP isolée à cette catégorie : idem.
                }
                perCategoryChannels += categoryChannels
                perCategoryRawCount += categoryRawCount
            }
            if (perCategoryChannels.isNotEmpty()) {
                channels = perCategoryChannels
                rawStreamCount = perCategoryRawCount
            }
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
        // Fix (2026-07-23) : encodePathSegment (Uri.encode), pas encode (URLEncoder) - ici
        // username/password sont inseres dans le CHEMIN de l'URL (/live/{user}/{pass}/...),
        // pas une query string. URLEncoder.encode encode un espace en "+", valide seulement
        // en query string (application/x-www-form-urlencoded) - dans un segment de chemin,
        // ce "+" reste un caractere litteral pour la plupart des serveurs, jamais decode en
        // espace : un identifiant Xtream contenant un espace ou certains caracteres
        // speciaux produisait donc une URL de flux invalide (chaine injouable), alors que
        // playerApiUrl/buildEpgUrl (query strings) n'etaient eux pas concernes.
        return "${baseUrl(credentials.serverUrl)}/live/${encodePathSegment(credentials.username)}/${encodePathSegment(credentials.password)}/$streamId.$ext"
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
        action: String? = null,
        categoryId: String? = null
    ): String {
        val builder = StringBuilder(baseUrl(credentials.serverUrl))
            .append("/player_api.php")
            .append("?username=").append(encode(credentials.username))
            .append("&password=").append(encode(credentials.password))
        if (action != null) {
            builder.append("&action=").append(action)
        }
        if (categoryId != null) {
            builder.append("&category_id=").append(encode(categoryId))
        }
        return builder.toString()
    }

    /**
     * Point d'entrée réseau unique de la classe : ajoute un fallback de schéma
     * (2026-07-25) par-dessus la cascade de User-Agent de [executeGetWithUserAgentCascade].
     *
     * Beaucoup d'utilisateurs collent une adresse sans schéma (§`baseUrl`, qui suppose
     * alors `http://` par défaut) alors que le panel n'accepte en réalité QUE du https
     * (reverse-proxy, panel derrière Cloudflare...) — et inversement, certains panels
     * fournis en https:// par le revendeur ne répondent en fait qu'en clair sur le même
     * port. Dans les deux cas, la première tentative échoue au niveau connexion/TLS
     * (jamais un simple code d'erreur HTTP, qui lui indique un serveur bien joignable) :
     * on retente alors une fois avec le schéma opposé avant d'abandonner.
     */
    private fun executeGet(url: String, continueOnEmptyArray: Boolean = false): GetOutcome {
        val firstAttempt = executeGetWithUserAgentCascade(url, continueOnEmptyArray)
        if (firstAttempt !is GetOutcome.NetworkError) return firstAttempt

        val alternateUrl = swapScheme(url) ?: return firstAttempt
        val secondAttempt = executeGetWithUserAgentCascade(alternateUrl, continueOnEmptyArray)
        // Ne garde le 2e essai que s'il apporte une vraie réponse serveur (Body ou même
        // HttpError, qui prouve au moins que ce schéma-là joint le serveur) ; sinon on
        // remonte l'erreur du tout premier essai, plus représentative de la cause réelle.
        return if (secondAttempt is GetOutcome.NetworkError) firstAttempt else secondAttempt
    }

    /** `http://` -> `https://` ou l'inverse ; `null` si l'URL n'a pas l'un de ces deux schémas. */
    private fun swapScheme(url: String): String? = when {
        url.startsWith("https://", ignoreCase = true) -> "http://" + url.removePrefix("https://")
        url.startsWith("http://", ignoreCase = true) -> "https://" + url.removePrefix("http://")
        else -> null
    }

    private fun executeGetWithUserAgentCascade(url: String, continueOnEmptyArray: Boolean = false): GetOutcome = try {
        // Cascade de User-Agent (2026-07-22, voir NetworkConstants.USER_AGENT_FALLBACKS) :
        // remplace l'ancien header unique forcé "IPTVSmartersPlayer" — on essaie d'abord
        // sans en-tête personnalisé, puis les signatures connues, jusqu'à obtenir une
        // réponse exploitable.
        // Fix (2026-07-25) : `isSuccessful` seul ne suffit PAS comme critère d'arrêt pour
        // get_live_categories/get_live_streams (continueOnEmptyArray = true côté appelant).
        // Beaucoup de panels Xtream (fréquent chez les revendeurs) ne bloquent pas un
        // User-Agent non reconnu par un code d'erreur franc : ils répondent 200 avec un
        // tableau JSON vide ("[]"), un "catalogue restreint" plutôt qu'un vrai refus. Sans
        // ce fix, la cascade s'arrêtait dès ce premier 200 "poli" et n'essayait jamais
        // IPTVSmartersPlayer/VLC/TiviMate, qui auraient débloqué le vrai catalogue. On
        // continue donc la cascade tant que le corps est un tableau JSON vide ; si TOUTES
        // les tentatives renvoient [], lastOutcome contient quand même ce dernier [] — un
        // compte réellement sans chaînes/catégories reste géré normalement en aval
        // (voir parseLiveStreams/parseCategories).
        var lastOutcome: GetOutcome = GetOutcome.NetworkError("Aucune tentative effectuée")
        for (userAgent in NetworkConstants.USER_AGENT_FALLBACKS) {
            val requestBuilder = Request.Builder().url(url).get()
            if (userAgent != null) requestBuilder.header("User-Agent", userAgent)
            val outcome = httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    GetOutcome.HttpError(response.code)
                } else {
                    val body = response.body
                    val bytes = body?.bytes() ?: ByteArray(0)
                    val declaredCharset = RobustTextDecoder.charsetFromContentType(body?.contentType()?.toString())
                    GetOutcome.Body(RobustTextDecoder.decode(bytes, declaredCharset))
                }
            }
            lastOutcome = outcome
            if (outcome is GetOutcome.Body) {
                if (continueOnEmptyArray && isEmptyJsonArray(outcome.text)) {
                    continue
                }
                break
            }
        }
        lastOutcome
    } catch (e: IOException) {
        GetOutcome.NetworkError(e.message ?: "Erreur réseau")
    } catch (e: IllegalArgumentException) {
        // URL malformée (adresse serveur invalide saisie par l'utilisateur, §4.2).
        GetOutcome.NetworkError(e.message ?: "Adresse de serveur invalide")
    }

    /**
     * Détecte un corps `"[]"` (éventuellement entouré d'espaces) : le cas "catalogue
     * restreint" décrit ci-dessus. Un corps non-tableau (objet d'erreur, HTML...) ou un
     * tableau non vide renvoie `false` — on ne veut continuer la cascade QUE sur ce cas
     * précis, pas masquer d'autres formes de réponse qui doivent être traitées ailleurs
     * (voir parseLiveStreams pour la gestion de "{}"/vide/"null").
     */
    private fun isEmptyJsonArray(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        return try {
            JSONArray(trimmed).length() == 0
        } catch (e: JSONException) {
            false
        }
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

    // Fix (robustesse "n'importe quel panel") : beaucoup d'utilisateurs collent une
    // adresse de serveur sans schéma ("monpanel.com:8080") ou en copiant carrément un
    // lien complet déjà fourni par leur revendeur (avec /player_api.php, /get.php ou une
    // query string en trop) — sans schéma, OkHttp lève IllegalArgumentException avant
    // même la première requête ("expected scheme") ; avec un chemin/une query en trop,
    // playerApiUrl produirait une URL invalide (double player_api.php, ?username=...
    // dupliqué). On normalise donc une bonne fois ici, seul point de passage commun à
    // authenticate/fetchLiveChannels/buildStreamUrl/buildEpgUrl.
    private fun baseUrl(server: String): String {
        var normalized = server.trim().trimEnd('/')
        // Schéma manquant : http:// par défaut (le cas très largement majoritaire pour
        // ces panels ; l'utilisateur reste libre de saisir explicitement https://).
        if (!normalized.contains("://")) {
            normalized = "http://$normalized"
        }
        // Retire un chemin d'API ou une query string collés par erreur avec l'hôte,
        // pour ne garder que schéma+hôte+port.
        normalized = normalized.substringBefore("?")
        normalized = Regex("""(?i)/(player_api|get)\.php.*$""").replace(normalized, "")
        return normalized.trimEnd('/')
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Encodage correct pour un segment de CHEMIN d'URL (contrairement à [encode], prévu
     *  pour une query string) - voir la doc de [buildStreamUrl] pour le bug que ça corrige.
     *  `android.net.Uri.encode` encode un espace en "%20", jamais en "+". */
    private fun encodePathSegment(value: String): String = android.net.Uri.encode(value)

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
