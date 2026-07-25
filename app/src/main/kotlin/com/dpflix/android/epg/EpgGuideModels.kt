package com.dpflix.android.epg

import com.dpflix.android.model.Channel
import com.dpflix.android.model.EpgProgram
import java.util.Calendar

/**
 * État de l'écran Guide TV (§4.6 du cahier des charges).
 *
 * Squelette de grille posé à l'étape 9b1 (voir son historique). Cette étape 9c ajoute la
 * **navigation temporelle** ([selectedDayStartMillis], jour précédent/suivant/aujourd'hui)
 * et le **détail programme** ([selectedProgram], ouvert au clic/OK sur une cellule).
 *
 * @property hasActivePlaylist Même distinction que `HomeUiState.hasActivePlaylist` (voir
 *   sa doc) : ne devrait pas arriver en pratique (on n'accède à cet écran que depuis
 *   l'accueil, qui n'existe déjà que si une playlist est active), gardé par cohérence et
 *   par sécurité si l'utilisateur revient en arrière pendant une bascule de playlist.
 * @property rows Une ligne par chaîne de la playlist active, dans le même ordre que
 *   l'accueil (`ChannelRepository.observeByPlaylist`, tri par catégorie puis numéro).
 *   [EpgGuideRow.programs] est déjà filtré sur [selectedDayStartMillis] (voir
 *   [EpgGuideViewModel]) : chaque changement de jour recalcule les lignes, pas seulement
 *   leur affichage.
 * @property epgUnavailableReason Non nul quand le chargement EPG a échoué ou qu'aucune
 *   source n'est configurée pour la playlist active (`EpgLoadResult.Unavailable.reason`) —
 *   message précis affiché depuis 9b1, avant que 9d n'harmonise le message "Aucun guide TV
 *   disponible" avec le reste de l'app (mini-lecteur notamment).
 * @property selectedDayStartMillis Minuit (fuseau de l'appareil) du jour actuellement
 *   affiché par la grille — voir [startOfDay]. Vaut aujourd'hui par défaut à l'ouverture
 *   de l'écran.
 * @property selectedProgram Programme actuellement affiché en détail (boîte de dialogue),
 *   `null` tant qu'aucune cellule n'a été cliquée/validée.
 */
data class EpgGuideUiState(
    val hasActivePlaylist: Boolean = false,
    val rows: List<EpgGuideRow> = emptyList(),
    val epgUnavailableReason: String? = null,
    val selectedDayStartMillis: Long = startOfDay(System.currentTimeMillis()),
    val selectedProgram: SelectedEpgProgram? = null
)

/**
 * Une ligne de la grille (§4.6) : une chaîne + les programmes connus pour elle sur le
 * [EpgGuideUiState.selectedDayStartMillis] affiché. [programs] est vide aussi bien quand
 * la chaîne n'a pas de `tvgId` (pas de rattachement possible) que quand le guide ne
 * contient rien pour son `tvgId` sur ce jour précis — les deux cas restent indiscernables
 * pour l'instant (pas de distinction requise par le cahier des charges).
 */
data class EpgGuideRow(
    val channel: Channel,
    val programs: List<EpgProgram>
)

/**
 * Programme sélectionné pour affichage en détail (§4.6 "détail de programme", étape 9c).
 * Transporte le nom de la chaîne à côté du programme : [EpgProgram] ne connaît que son
 * `channelTvgId` technique (pas de nom affichable), ça évite à la boîte de dialogue de
 * devoir retrouver la chaîne correspondante pour un simple titrage.
 */
data class SelectedEpgProgram(
    val channelName: String,
    val program: EpgProgram
)

/**
 * Libellé standard "aucun guide TV" (§4.6), harmonisé à l'étape 9d avec Réglages
 * (`SettingsScreen`/`SettingsScreenTv`, `EpgStatusSetting` → `EpgStatus.NONE`), qui
 * affichait déjà ce libellé exact en gras, avec la raison technique en sous-texte.
 * Jusqu'ici (9b1/9c) [EpgGuideScreen]/[EpgGuideScreenTv] affichaient seulement la raison
 * brute d'[com.dpflix.android.model.EpgLoadResult.Unavailable.reason]
 * ([EpgGuideUiState.epgUnavailableReason], ex. "Erreur réseau" ou "Aucune source EPG
 * disponible pour cette playlist") sans ce titre, ce qui pouvait laisser croire à une
 * erreur d'affichage plutôt qu'à une absence de guide — désormais affiché avec ce libellé
 * en tête et la raison technique juste en dessous, sur les deux écrans.
 */
const val EPG_UNAVAILABLE_LABEL = "Aucun guide TV disponible"

/**
 * Minuit (fuseau de l'appareil) du jour contenant [millis]. Sert à la fois de valeur
 * initiale du jour affiché (aujourd'hui, [EpgGuideUiState.selectedDayStartMillis]) et de
 * borne de filtrage par jour dans [EpgGuideViewModel] — §4.6, "navigation temporelle",
 * étape 9c.
 *
 * `java.util.Calendar` plutôt que `java.time` : le projet cible `minSdk = 23` sans
 * désucrage de bibliothèque de base activé (voir `app/build.gradle.kts`), `java.time`
 * n'est donc pas disponible partout — cohérent avec le reste du module EPG
 * ([EpgGuideScreen]/[EpgGuideScreenTv] utilisent déjà `SimpleDateFormat`/`Date`).
 */
fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * [dayStartMillis] décalé de [deltaDays] jours (négatif pour reculer), utilisé par les
 * boutons jour précédent/suivant. [dayStartMillis] est supposé déjà être un minuit (voir
 * [startOfDay]) — `Calendar.add(DAY_OF_YEAR, ...)` conserve cet alignement sauf autour
 * d'un changement d'heure DST, écart mineur accepté ici comme ailleurs dans le module EPG
 * (pas de gestion fine des fuseaux/DST dans ce projet, voir la doc de
 * `com.dpflix.android.model.EpgProgram`).
 */
fun addDays(dayStartMillis: Long, deltaDays: Int): Long = Calendar.getInstance().apply {
    timeInMillis = dayStartMillis
    add(Calendar.DAY_OF_YEAR, deltaDays)
}.timeInMillis
