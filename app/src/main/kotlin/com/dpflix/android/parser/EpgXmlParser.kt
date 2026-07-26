package com.dpflix.android.parser

import com.dpflix.android.model.EpgProgram
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Parseur EPG XMLTV (§4.6, §5.4 du cahier des charges), commun aux deux sources :
 * consomme le contenu pointé par `M3uParseResult.detectedEpgUrl` (M3U, étape 3b),
 * `XtreamLiveChannelsData.detectedEpgUrl` (Xtream, étape 3c), ou un EPG manuel
 * (URL/fichier local, §5.4).
 *
 * Comme `M3uParser` (étape 3b), une fonction pure : le téléchargement de l'URL EPG
 * ou la lecture du fichier local importé restent la responsabilité de la couche
 * repository (étape 4), qui appelle [parse] avec les octets déjà récupérés. La
 * décompression gzip est en revanche traitée ici : elle ne nécessite aucune IO
 * réseau/disque supplémentaire (uniquement de la manipulation d'octets déjà en
 * mémoire), et beaucoup de panels exposent leur EPG en `.xml.gz` (§5.4) — la
 * traiter dans le parseur évite à la couche repository de devoir deviner le
 * format à partir de l'extension d'URL, peu fiable (`xmltv.php` chez Xtream n'a
 * pas d'extension).
 *
 * Construction du guide en `EpgLoadResult` (regroupement par playlist, priorité
 * manuel > auto-détecté > aucun, §4.6) : hors périmètre de cette sous-étape,
 * à la charge du repository (étape 4) qui appelle [parse] puis groupe les
 * [EpgProgram] obtenus par `channelTvgId`.
 *
 * Parsing tolérant, dans le même esprit que `M3uParser` / `XtreamClient` :
 * un `<programme>` individuel mal formé (horaire illisible, `channel` ou
 * `<title>` absent) est ignoré plutôt que de faire échouer tout le guide —
 * un flux XMLTV de plusieurs milliers de programmes ne doit pas être perdu
 * en entier à cause d'une entrée corrompue. Seule l'absence totale de racine
 * `<tv>` exploitable est considérée comme une erreur bloquante (contenu qui
 * n'est manifestement pas du XMLTV).
 */
object EpgXmlParser {

    private val XMLTV_DATETIME_REGEX =
        Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*([+-]\d{4}))?""")

    /**
     * Fix (2026-07-25, crash plein écran sur gros Xtream) : bornes par défaut de la fenêtre
     * de rétention ci-dessous, utilisées par les surcharges sans paramètre explicite (tests
     * notamment) — larges par prudence, la valeur qui compte en pratique est celle passée
     * explicitement par [com.dpflix.android.repository.EpgRepository.load].
     */
    private const val DEFAULT_KEEP_PAST_MILLIS = 24L * 60 * 60 * 1000
    private const val DEFAULT_KEEP_FUTURE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /**
     * @param rawBytes contenu brut déjà téléchargé (ou lu depuis un fichier local importé,
     * §5.4), compressé gzip ou non — la compression est détectée automatiquement via les
     * octets magiques (`1F 8B`), indépendamment de ce que l'URL/nom de fichier suggère.
     * @param keepFromMillis/@param keepUntilMillis fenêtre de rétention (voir la doc de
     * classe, "Fenêtre de rétention" plus bas) : tout `<programme>` entièrement hors de
     * `[keepFromMillis, keepUntilMillis]` est ignoré SANS même construire les chaînes
     * titre/description (voir [skipElementBody]), ce qui est le point qui économise la
     * mémoire (pas seulement le fait de ne pas garder l'objet final).
     * @throws IllegalArgumentException si le contenu n'est pas un document XMLTV exploitable
     * (pas de balise racine `<tv>`).
     */
    fun parse(
        rawBytes: ByteArray,
        keepFromMillis: Long = System.currentTimeMillis() - DEFAULT_KEEP_PAST_MILLIS,
        keepUntilMillis: Long = System.currentTimeMillis() + DEFAULT_KEEP_FUTURE_MILLIS
    ): List<EpgProgram> {
        val isGzip = rawBytes.size >= 2 &&
            rawBytes[0] == 0x1f.toByte() &&
            rawBytes[1] == 0x8b.toByte()

        val stream: InputStream = if (isGzip) {
            GZIPInputStream(ByteArrayInputStream(rawBytes))
        } else {
            ByteArrayInputStream(rawBytes)
        }

        return stream.use { parse(it, keepFromMillis, keepUntilMillis) }
    }

    /** Variante texte pratique (contenu déjà décodé), par exemple pour des tests. */
    fun parse(
        rawXml: String,
        keepFromMillis: Long = System.currentTimeMillis() - DEFAULT_KEEP_PAST_MILLIS,
        keepUntilMillis: Long = System.currentTimeMillis() + DEFAULT_KEEP_FUTURE_MILLIS
    ): List<EpgProgram> =
        parse(ByteArrayInputStream(rawXml.toByteArray(Charsets.UTF_8)), keepFromMillis, keepUntilMillis)

    private fun parse(input: InputStream, keepFromMillis: Long, keepUntilMillis: Long): List<EpgProgram> {
        val parser: XmlPullParser = XmlPullParserFactory.newInstance().newPullParser()
        // encoding = null : laisse le parseur détecter l'encodage déclaré dans le prologue
        // XML (ou le BOM), les flux XMLTV n'étant pas toujours en UTF-8.
        try {
            parser.setInput(input, null)
        } catch (e: Exception) {
            throw IllegalArgumentException("Contenu EPG illisible : pas un XML valide", e)
        }

        val programs = mutableListOf<EpgProgram>()
        var sawTvRoot = false
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "tv" -> sawTvRoot = true
                    "programme" -> parseProgrammeElement(parser, keepFromMillis, keepUntilMillis)?.let { programs += it }
                }
            }
            eventType = try {
                parser.next()
            } catch (e: Exception) {
                // Flux tronqué ou balise mal formée plus loin dans le document : on garde
                // ce qui a déjà été extrait plutôt que de perdre tout le guide (§6 —
                // même logique de tolérance que pour un réseau ou un serveur instable).
                break
            }
        }

        require(sawTvRoot) { "Contenu EPG illisible : balise racine <tv> absente" }

        return programs
    }

    /**
     * ## Fenêtre de rétention (fix 2026-07-25 — crash au passage en plein écran, gros Xtream)
     * Avant ce correctif, `parse` gardait TOUS les `<programme>` du guide, pour TOUTES les
     * chaînes, sans aucune limite — cohérent tant que rien n'utilisait plus que "le
     * programme en cours" (§4.6/8b). Un XMLTV réel couvre en général plusieurs jours par
     * chaîne ; sur un panel à 11 000+ chaînes ça peut représenter plusieurs centaines de
     * milliers, voire des millions de `<programme>`, chacun devenant un [EpgProgram] retenu
     * indéfiniment dans le cache mémoire d'[com.dpflix.android.repository.EpgRepository]
     * (pas de TTL, voir sa doc) — largement de quoi dépasser ce qu'un mobile bas/moyen de
     * gamme peut allouer, alors que seule une poignée de ces entrées (celles autour de
     * l'instant présent) sert jamais à quoi que ce soit : l'écran de grille EPG qui aurait
     * pu justifier de tout garder a été retiré (`README-retrait-ecran-guide-tv.md`), seuls
     * restent l'OSD "programme en cours" du lecteur et le statut de Réglages, qui n'ont
     * besoin ni du passé lointain ni de plusieurs jours à l'avance.
     *
     * `keepFromMillis`/`keepUntilMillis` bornent donc ce qui est conservé : un `<programme>`
     * entièrement avant `keepFromMillis` (déjà terminé) ou entièrement après
     * `keepUntilMillis` (trop loin dans le futur) est ignoré. Le test se fait sur les
     * horaires seuls (attributs `start`/`stop` du `<programme>`, déjà lus à ce stade) : s'il
     * ne passe pas, [skipElementBody] avance le curseur jusqu'à la fin de l'élément SANS
     * jamais lire `<title>`/`<desc>` (pas d'allocation de `String` pour ces entrées hors
     * fenêtre) — c'est ce qui borne réellement la mémoire, pas juste le fait de ne pas
     * ajouter l'`EpgProgram` final à la liste.
     *
     * Le curseur est sur le START_TAG `<programme>` en entrant ; ressort sur son END_TAG
     * correspondant dans tous les cas (élément retenu ou non), pour ne pas désynchroniser
     * le pull parser pour la suite du document.
     */
    private fun parseProgrammeElement(
        parser: XmlPullParser,
        keepFromMillis: Long,
        keepUntilMillis: Long
    ): EpgProgram? {
        val channelId = parser.getAttributeValue(null, "channel")?.takeIf { it.isNotBlank() }
        val startMillis = parser.getAttributeValue(null, "start")?.let(::parseXmltvDateTime)
        val stopMillis = parser.getAttributeValue(null, "stop")?.let(::parseXmltvDateTime)

        val inWindow = startMillis != null && stopMillis != null &&
            stopMillis > keepFromMillis && startMillis < keepUntilMillis

        if (!inWindow) {
            skipElementBody(parser)
            return null
        }

        var title: String? = null
        var description: String? = null

        var depth = 1
        var eventType = parser.next()
        while (depth > 0) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    // "title"/"desc" sont lus de façon atomique par readTextOrNull, qui
                    // consomme lui-même jusqu'à SA propre END_TAG : ne pas incrémenter
                    // `depth` dans ce cas, sous peine de désynchroniser le comptage avec
                    // les END_TAG restants du <programme> (`depth` ne doit suivre que les
                    // éléments dont la fermeture n'est pas déjà consommée ci-dessous).
                    when (parser.name) {
                        "title" -> title = readTextOrNull(parser) ?: title
                        "desc" -> description = readTextOrNull(parser) ?: description
                        else -> depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return null // document tronqué en plein <programme>
            }
            if (depth > 0) eventType = parser.next()
        }

        if (channelId == null || title == null || startMillis == null || stopMillis == null) {
            return null // entrée incomplète/mal formée : ignorée (§ tolérance ci-dessus)
        }
        if (stopMillis <= startMillis) {
            return null // horaires incohérents (contrainte de EpgProgram.init)
        }

        return EpgProgram(
            channelTvgId = channelId,
            title = title,
            description = description?.takeIf { it.isNotBlank() },
            startTimeMillis = startMillis,
            endTimeMillis = stopMillis
        )
    }

    /**
     * Avance le curseur jusqu'au END_TAG du `<programme>` courant sans jamais lire de texte
     * (contrairement à [readTextOrNull]) — utilisé pour les entrées hors fenêtre de
     * rétention, où le contenu de `<title>`/`<desc>` ne sert jamais à rien (voir la doc de
     * [parseProgrammeElement]). Le curseur est sur le premier `next()` suivant le START_TAG
     * `<programme>` en entrant (même convention que la boucle `depth` ci-dessus).
     */
    private fun skipElementBody(parser: XmlPullParser) {
        var depth = 1
        var eventType = parser.next()
        while (depth > 0 && eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
            if (depth > 0) eventType = parser.next()
        }
    }

    /**
     * Lit le texte d'un élément simple (`<title>...</title>`) ; le curseur est sur son
     * START_TAG en entrant et en ressort positionné sur son END_TAG. Ignore les
     * sous-éléments imbriqués éventuels (rares mais tolérés, ex. balises de mise en forme).
     */
    private fun readTextOrNull(parser: XmlPullParser): String? {
        var text: String? = null
        var depth = 1
        var eventType = parser.next()
        while (depth > 0) {
            when (eventType) {
                XmlPullParser.TEXT -> {
                    val chunk = parser.text
                    if (!chunk.isNullOrBlank()) {
                        text = (text.orEmpty() + chunk)
                    }
                }
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return text
            }
            if (depth > 0) eventType = parser.next()
        }
        return text?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Convertit un horodatage XMLTV (`YYYYMMDDhhmmss ±hhmm`, décalage optionnel — absent,
     * on suppose UTC comme le font la plupart des générateurs EPG mal formés) en epoch millis.
     * Volontairement pas `java.time` (minSdk 23 du projet, §2 — pas de désucrage configuré) :
     * `Calendar`/`TimeZone` uniquement.
     */
    private fun parseXmltvDateTime(raw: String): Long? {
        val match = XMLTV_DATETIME_REGEX.find(raw.trim()) ?: return null
        val groups = match.groupValues
        val year = groups[1]
        val month = groups[2]
        val day = groups[3]
        val hour = groups[4]
        val minute = groups[5]
        val second = groups[6]
        val offset = groups[7]

        val timeZone = if (offset.isBlank()) {
            TimeZone.getTimeZone("UTC")
        } else {
            // Format XMLTV "+0200" / "-0500" -> identifiant TimeZone "GMT+02:00" / "GMT-05:00".
            val sign = offset[0]
            val offsetHours = offset.substring(1, 3)
            val offsetMinutes = offset.substring(3, 5)
            TimeZone.getTimeZone("GMT$sign$offsetHours:$offsetMinutes")
        }

        return try {
            val calendar = GregorianCalendar(timeZone)
            calendar.isLenient = false
            calendar.set(
                year.toInt(),
                month.toInt() - 1, // Calendar.MONTH est indexé à partir de 0
                day.toInt(),
                hour.toInt(),
                minute.toInt(),
                second.toInt()
            )
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        } catch (e: IllegalArgumentException) {
            null // date invalide (ex. 30 février dans un flux mal généré) : entrée ignorée
        }
    }
}
