package com.dpflix.android.parser

import com.dpflix.android.model.Channel
import java.util.UUID

/**
 * Résultat du parsing d'une playlist M3U : les chaînes extraites, plus l'EPG
 * auto-détecté via l'attribut `url-tvg` (ou `x-tvg-url`, variante courante) de
 * l'en-tête `#EXTM3U` (§4.6 du cahier des charges).
 */
data class M3uParseResult(
    val channels: List<Channel>,
    val detectedEpgUrl: String?
)

/**
 * Parseur M3U / M3U8 (§4.2 Étape 2b, §4.6).
 *
 * Volontairement une simple fonction pure `String -> M3uParseResult` : ce parseur
 * ne fait aucune IO. Le téléchargement de l'URL de playlist ou la lecture du fichier
 * local importé (les deux options du formulaire M3U) sont la responsabilité de la
 * couche réseau/repository (étape 4), qui appellera [M3uParser.parse] avec le
 * contenu texte déjà récupéré. Ça permet de tester le parseur sans réseau ni disque.
 */
object M3uParser {

    private val HEADER_EPG_REGEX = Regex("""(?:url-tvg|x-tvg-url)="([^"]*)"""")
    private val ATTR_REGEX = Regex("""([\w-]+)="([^"]*)"""")

    /**
     * @param rawContent contenu texte brut du fichier M3U (déjà téléchargé ou lu localement).
     * @param playlistId id de la [com.dpflix.android.model.Playlist] à laquelle rattacher les chaînes produites.
     * @throws IllegalArgumentException si le contenu ne commence pas par `#EXTM3U`.
     */
    fun parse(rawContent: String, playlistId: String): M3uParseResult {
        val lines = rawContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        require(lines.isNotEmpty() && lines.first().startsWith("#EXTM3U")) {
            "Fichier M3U invalide : doit commencer par #EXTM3U"
        }

        val detectedEpgUrl = HEADER_EPG_REGEX.find(lines.first())
            ?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }

        val channels = mutableListOf<Channel>()
        var pendingAttrs: Map<String, String> = emptyMap()
        var pendingName: String? = null
        var sequentialNumber = 0

        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXTINF") -> {
                    val commaIndex = line.indexOf(',')
                    val attrsPart = if (commaIndex >= 0) line.substring(0, commaIndex) else line
                    pendingName = if (commaIndex >= 0) line.substring(commaIndex + 1).trim() else null
                    pendingAttrs = ATTR_REGEX.findAll(attrsPart)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                }
                line.startsWith("#") -> {
                    // #EXTGRP, #EXTVLCOPT, #EXTALB, etc. — ignorés à ce stade (hors périmètre §4.2/§4.6).
                }
                else -> {
                    // Ligne d'URL de flux : clôt l'entrée #EXTINF courante (ou entrée sans métadonnées).
                    sequentialNumber++
                    val attrs = pendingAttrs
                    channels += Channel(
                        id = UUID.randomUUID().toString(),
                        playlistId = playlistId,
                        name = pendingName?.takeIf { it.isNotBlank() }
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: "Chaîne $sequentialNumber",
                        streamUrl = line,
                        logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        category = attrs["group-title"]?.takeIf { it.isNotBlank() },
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        originalNumber = attrs["tvg-chno"]?.toIntOrNull() ?: sequentialNumber
                    )
                    pendingAttrs = emptyMap()
                    pendingName = null
                }
            }
        }

        return M3uParseResult(channels = channels, detectedEpgUrl = detectedEpgUrl)
    }
}
