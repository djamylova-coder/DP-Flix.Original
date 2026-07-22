package com.dpflix.android.model

/**
 * Un programme dans le guide EPG (format XMLTV), rattaché à une chaîne via `channelTvgId`
 * qui doit correspondre au `tvgId` de [Channel] (§4.6).
 *
 * Les horaires sont stockés en epoch millis UTC (déjà convertis lors du parsing XMLTV,
 * qui fournit des dates avec offset, ex: `20260720193000 +0200`) pour rester simples
 * à comparer et à afficher sans dépendance de fuseau supplémentaire à ce stade.
 */
data class EpgProgram(
    val channelTvgId: String,
    val title: String,
    val description: String? = null,
    val startTimeMillis: Long,
    val endTimeMillis: Long
) {
    init {
        require(endTimeMillis > startTimeMillis) { "endTimeMillis doit être après startTimeMillis" }
    }

    fun isCurrentlyAiring(nowMillis: Long): Boolean =
        nowMillis in startTimeMillis until endTimeMillis
}

/**
 * Résultat du chargement EPG pour une playlist donnée : soit un guide exploitable,
 * soit une raison d'absence — permet à l'UI d'afficher "Aucun guide TV disponible"
 * (§4.6) sans bloquer le reste de l'app.
 */
sealed class EpgLoadResult {
    data class Success(val programsByChannel: Map<String, List<EpgProgram>>) : EpgLoadResult()
    data class Unavailable(val reason: String) : EpgLoadResult()
}
