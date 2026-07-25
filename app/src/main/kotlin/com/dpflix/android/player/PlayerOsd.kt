package com.dpflix.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dpflix.android.model.Channel
import com.dpflix.android.ui.theme.DpFlixColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Calque OSD superposé à la vidéo (§4.5, étape 8). Un seul calque cohérent qui
 * s'enrichit au fil des sous-étapes plutôt qu'une collection de widgets indépendants :
 * - 8a : squelette — apparition/disparition + minuteur d'auto-masquage (géré par
 *   [PlayerScreen], pas ici) + affichage minimal (logo + nom).
 * - **8b (ce livrable)** : heure courante, écart au direct (§5.5/§6), programme en cours
 *   si l'EPG est disponible pour la chaîne (§4.6).
 * - **8c (ce livrable)** : le numéro affiché (`Channel.displayNumber`, §5.3) rejoint le nom
 *   dans le bandeau — sert aussi de zone tappable pour ouvrir le clavier numérique virtuel
 *   sur mobile (voir [onRequestNumericEntry]). L'overlay de saisie en tant que tel (numéro
 *   en cours de frappe + clavier) vit dans un calque séparé, [PlayerZapEntryOverlay] — pas
 *   ici : il doit rester visible même quand ce bandeau est masqué par le minuteur (8a),
 *   les deux calques n'ont pas le même cycle de vie.
 * - **8d1** : premier contrôle visible, lecture/pause — bouton dans le bandeau, tap mobile
 *   (voir [onTogglePlayPause]). D-pad TV (8d2).
 * - **8d3** : rendu et interaction du curseur de volume, sans branchement réel derrière
 *   (état local temporaire, voir le README de 8d3).
 * - **8d4 (ce livrable)** : branchement réel du curseur sur le volume système
 *   (`AudioManager.STREAM_MUSIC`, décision détaillée dans [PlayerScreen]) — le
 *   `remember` interne de 8d3 disparaît, remplacé par [volumeFraction]/[onVolumeChange],
 *   même schéma que [isPlaying]/[onTogglePlayPause]. Qualité manuelle suit dans les
 *   prochaines sous-étapes de 8d ; l'agencement définitif de la rangée de contrôles
 *   arrive à 8d9 — pour l'instant, tout est placé au plus simple.
 * - **8d7** : affichage de [availableQualities] (§8d6) sous forme de menu déroulant
 *   (voir [QualitySelector]) — "Auto" + une entrée par résolution disponible. Absent du
 *   bandeau si la chaîne ne propose qu'un seul débit (liste vide, même logique que le
 *   log témoin de 8d6). À cette sous-étape, la sélection restait un `remember` interne à
 *   [QualitySelector], sans effet réel sur la lecture.
 * - **8d8** : [selectedQuality]/[onQualityChange] remplacent ce `remember`
 *   interne — même schéma que [isPlaying]/[onTogglePlayPause] et
 *   [volumeFraction]/[onVolumeChange] — désormais portés par `PlayerController`
 *   (`selectedQuality`, `setQualityOverride`), qui applique réellement le plafond de
 *   résolution au décodeur (`DefaultTrackSelector`) et remet "Auto" à chaque zap (voir
 *   la doc de `PlayerController.playChannel`). Conséquence directe : la sélection
 *   survit désormais normalement au masquage de l'OSD par le minuteur (portée par
 *   `PlayerController`, pas par ce calque), contrairement à 8d7.
 * - **8d9 (ce livrable)** : agencement définitif — deux zones distinctes plutôt que tout
 *   empilé verticalement au fil des sous-étapes précédentes. **Bandeau d'info** en haut
 *   (logo+numéro+nom, heure, écart au direct, programme en cours — 8a/8b/8c, inchangé sur
 *   le fond) et **barre de contrôles** en bas (lecture/pause, volume, qualité — désormais
 *   une seule rangée horizontale au lieu de trois rangées empilées), chacune avec son
 *   propre dégradé (`Brush.verticalGradient`, direction inversée pour la barre du bas,
 *   assombrissement du bord d'écran le plus proche dans les deux cas) — même logique
 *   visuelle, juste dédoublée. Toujours une seule [AnimatedVisibility] pour les deux
 *   zones (un seul calque cohérent qui apparaît/disparaît ensemble, comme depuis 8a) :
 *   pas deux minuteurs d'auto-masquage indépendants. Mise en page uniquement — aucune
 *   logique de focus D-pad ici (l'ordre de traversée entre les contrôles de la barre du
 *   bas arrive à 8d10).
 *
 * [visible] est piloté depuis [PlayerScreen] (tap mobile / D-pad TV + minuteur) plutôt
 * que géré ici : ce composable reste un pur rendu, sans état ni logique d'entrée —
 * cohérent avec le fait qu'il sera partagé par mobile ET TV (comme [PlayerScreen] lui-même
 * depuis l'étape 5/7, contrairement aux autres écrans dédoublés en `*Tv.kt` à l'étape 7).
 * [nowMillis]/[liveEdgeOffsetSeconds] sont aussi calculés par [PlayerScreen] (voir sa doc
 * sur la boucle de rafraîchissement à 1 s) plutôt qu'ici, pour la même raison.
 *
 * [onRequestNumericEntry] : `null` tant que le zapping n'est pas disponible dans ce
 * contexte (mini-lecteur de l'accueil, `osdEnabled = false` — cet OSD n'y est de toute
 * façon jamais rendu, voir [PlayerScreen]). En plein écran, ouvre le clavier virtuel
 * mobile ([PlayerZapEntryOverlay]) — seule façon d'y saisir un numéro, faute de
 * télécommande numérique physique. Sans effet côté TV (la télécommande numérique frappe
 * directement, sans avoir besoin de taper ce bandeau) mais rien n'empêche techniquement
 * un boîtier TV tactile de s'en servir aussi.
 *
 * [isPlaying]/[onTogglePlayPause] (8d1) : [isPlaying] pilote uniquement l'icône affichée
 * (▶ vs ⏸), calculé par [PlayerScreen] à partir de `PlayerUiState` — voir sa doc pour le
 * détail du repli pendant `Buffering`/`Error`. [onTogglePlayPause] reste non nul dès que
 * ce calque est rendu (contrairement à [onRequestNumericEntry], qui dépend de la présence
 * de `appRepository`) : la lecture/pause ne dépend d'aucun contexte de zapping, elle est
 * pertinente partout où [PlayerOsd] apparaît (donc jamais dans le mini-lecteur, qui ne
 * rend de toute façon jamais ce composable — voir `osdEnabled` dans [PlayerScreen]).
 *
 * [volumeFraction]/[onVolumeChange] (8d4) : `0f..1f`, reflète et pilote le volume système
 * (`AudioManager.STREAM_MUSIC`) — voir [PlayerScreen] pour le détail de la décision et de
 * la conversion vers/depuis l'index `AudioManager` réel. Contrairement à 8d3 (curseur en
 * `remember` interne, perdu à chaque recomposition du parent), la position affichée
 * survit désormais normalement au masquage de l'OSD et au zap, portée par
 * [PlayerScreen] comme le reste de l'état de cet écran.
 *
 * [availableQualities] (8d7) : liste brute transmise telle quelle depuis
 * `PlayerController.availableQualities` (§8d6, `StateFlow` recalculé à chaque
 * `onTracksChanged`) — voir [QualitySelector] pour le rendu.
 *
 * [selectedQuality]/[onQualityChange] (8d8) : `null` = "Auto", reflète et pilote
 * `PlayerController.selectedQuality`/`setQualityOverride` — même schéma que
 * [volumeFraction]/[onVolumeChange]. Voir la doc de `PlayerController.playChannel` pour
 * la décision "remis à Auto à chaque zap" (à la différence du volume, délibérément pas
 * remis à zéro par chaîne).
 */
@Composable
fun PlayerOsd(
    channel: Channel,
    visible: Boolean,
    nowMillis: Long,
    liveEdgeOffsetSeconds: Float?,
    currentProgramTitle: String?,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    volumeFraction: Float,
    onVolumeChange: (Float) -> Unit,
    availableQualities: List<QualityOption>,
    selectedQuality: QualityOption?,
    onQualityChange: (QualityOption?) -> Unit,
    onRequestNumericEntry: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Bandeau d'info (haut) — logo/numéro/nom, heure, écart au direct, programme
            // en cours. Contenu inchangé depuis 8a/8b/8c, seul le bouton lecture/pause en
            // est retiré (rejoint la barre de contrôles du bas, voir plus loin).
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = if (onRequestNumericEntry != null) {
                            Modifier.clickable(onClick = onRequestNumericEntry)
                        } else {
                            Modifier
                        }
                    ) {
                        ChannelLogo(channel = channel)
                        Text(
                            text = channelLabel(channel),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = formatClock(nowMillis),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = liveEdgeOffsetLabel(liveEdgeOffsetSeconds),
                        color = DpFlixColors.OnBackgroundMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (currentProgramTitle != null) {
                        Text(
                            text = "· $currentProgramTitle",
                            color = DpFlixColors.OnBackgroundMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Barre de contrôles (bas) — lecture/pause, volume, qualité : une seule
            // rangée horizontale (8d9) au lieu des trois rangées empilées de 8d1-8d8.
            // Dégradé inversé (assombrit le bas de l'écran plutôt que le haut), même
            // logique visuelle que le bandeau d'info ci-dessus.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                PlayPauseButton(isPlaying = isPlaying, onClick = onTogglePlayPause)
                VolumeSlider(volumeFraction = volumeFraction, onVolumeChange = onVolumeChange)
                QualitySelector(
                    availableQualities = availableQualities,
                    selected = selectedQuality,
                    onSelect = onQualityChange
                )
                if (onOpenSettings != null) {
                    // Ouvre Réglages en incrustation par-dessus la vidéo (qui continue de
                    // jouer derrière, voir PlayerScreen) plutôt que de naviguer vers un
                    // écran séparé — ce qui arrêterait la lecture et figerait les
                    // métriques du Diagnostic (§5.5), alimentées uniquement pendant une
                    // lecture réellement active (voir PlayerMetricsBridge).
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Réglages", tint = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Curseur de volume (8d3, branché en 8d4) — voir la doc de [PlayerOsd] pour le détail de
 * [volumeFraction]/[onVolumeChange]. Composable de pur rendu depuis 8d4 (plus de
 * `remember` interne, contrairement à 8d3) : cohérent avec le reste de ce fichier.
 *
 * Placement (8d9) : rangée de contrôles du bas, entre le bouton lecture/pause et le
 * sélecteur de qualité — plus de rangée dédiée avec padding vertical propre (8d3/8d4),
 * cohérent avec le reste de la barre.
 */
@Composable
private fun VolumeSlider(volumeFraction: Float, onVolumeChange: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(imageVector = Icons.Filled.VolumeUp, contentDescription = "Volume", tint = Color.White)
        Slider(
            value = volumeFraction,
            onValueChange = onVolumeChange,
            modifier = Modifier.width(160.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = DpFlixColors.OnBackgroundMuted
            )
        )
    }
}

/**
 * Sélecteur de qualité (rendu 8d7, sélection réelle branchée 8d8) — voir la doc de
 * [PlayerOsd] pour le détail de [availableQualities]/[selected]/[onSelect].
 *
 * Ne se rend pas du tout si [availableQualities] est vide (chaîne mono-débit, ou pistes
 * pas encore annoncées par le flux juste après un zap) — même logique que le log témoin
 * de 8d6, pas de contrôle à afficher quand il n'y a rien à choisir.
 *
 * "Auto" (résolution la plus haute que l'ABR juge soutenable) apparaît toujours en tête
 * de la liste déroulante, [selected] `null` le représentant plutôt qu'un [QualityOption]
 * dédié — cohérent avec `PlayerController.setQualityOverride`, où "Auto" correspond à
 * l'absence de plafond `DefaultTrackSelector` plutôt qu'à une résolution précise.
 *
 * `expanded` (ouverture/fermeture du menu) reste un `remember` **interne**, à la
 * différence de [selected] (8d8) : pur chrome d'interaction propre à ce composable, sans
 * intérêt pour `PlayerScreen` ou `PlayerController` — rien à hoister ici, contrairement à
 * la sélection elle-même qui pilote la lecture.
 *
 * Placement (8d9) : rangée de contrôles du bas, après le curseur de volume — plus de
 * rangée dédiée avec padding vertical propre (8d7), cohérent avec le reste de la barre.
 */
@Composable
private fun QualitySelector(
    availableQualities: List<QualityOption>,
    selected: QualityOption?,
    onSelect: (QualityOption?) -> Unit
) {
    if (availableQualities.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(imageVector = Icons.Filled.HighQuality, contentDescription = "Qualité", tint = Color.White)
            Text(
                text = selected?.label ?: "Auto",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Auto") },
                leadingIcon = if (selected == null) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else null,
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            availableQualities.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = if (selected == option) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * "N° · Nom" quand un numéro affiché existe (§5.3, personnalisé en priorité, voir
 * `Channel.displayNumber`), simple nom sinon (chaîne sans numéro connu — ex. import M3U
 * sans `tvg-chno` et jamais renumérotée manuellement). Ajouté à cette sous-étape (8c) :
 * le numéro donne un repère de zapping direct et sert de zone tappable pour le clavier
 * virtuel mobile (voir [onRequestNumericEntry][PlayerOsd]).
 */
private fun channelLabel(channel: Channel): String =
    channel.displayNumber?.let { number -> "$number · ${channel.name}" } ?: channel.name

/** Pas `java.time` (minSdk 23 du projet, pas de désucrage — même contrainte que
 *  `EpgXmlParser`/`SettingsScreen.formatEpgTimestamp`). */
private fun formatClock(nowMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(nowMillis))

/**
 * `null` : le flux n'est pas (encore) reconnu comme direct par ExoPlayer, ou l'écart
 * n'est pas encore connu (voir `PlayerController.currentLiveEdgeOffsetSeconds`) — état
 * transitoire courant juste après un zapping, avant `STATE_READY`.
 * Un écart quasi nul (< 1 s, arrondi à l'affichage) est présenté comme "Direct" plutôt
 * que "0 s" : plus lisible, et cohérent avec le fait que le retard cible (§5.1/§6,
 * `PlayerSettings.liveDelaySeconds`) peut légitimement valoir 0.
 */
private fun liveEdgeOffsetLabel(offsetSeconds: Float?): String = when {
    offsetSeconds == null -> "Écart au direct : indisponible"
    offsetSeconds.roundToInt() <= 0 -> "Direct"
    else -> "Écart au direct : ${offsetSeconds} s"
}

/**
 * Bouton lecture/pause (§8d1) — tap mobile ici ; le D-pad TV (8d2) rappellera le même
 * [onClick] (`PlayerController.togglePlayPause`) depuis `PlayerScreen`, ce composable ne
 * gère que le rendu et le tap, pas la source de la commande.
 *
 * Zone tappable de 44dp (cohérente avec [ChannelLogo] dans le bandeau d'info) plutôt que
 * l'icône seule à sa taille naturelle, pour une cible tactile confortable. Style
 * volontairement minimal (icône sur fond transparent, pas de cadre). Premier élément de
 * la barre de contrôles du bas depuis 8d9 (voir [PlayerOsd]) — auparavant dans le
 * bandeau d'info du haut (8d1-8d8).
 */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Mettre en pause" else "Lecture",
            tint = Color.White
        )
    }
}

/**
 * `logoUrl` (M3U `tvg-logo` / Xtream `stream_icon`, étapes 3b-3c) était collecté et
 * persisté depuis le tout début du projet mais n'était encore rendu nulle part (l'accueil,
 * étape 6c/7c, n'affiche que le nom/numéro des chaînes) : premier vrai rendu d'image du
 * projet, d'où l'ajout de Coil (voir `libs.versions.toml`) à cette sous-étape plutôt qu'à
 * l'accueil en son temps — l'OSD en avait un besoin plus direct ("logo de la chaîne",
 * explicitement demandé au §8a), l'accueil reste hors périmètre ici (voir README).
 *
 * Repli sur l'initiale du nom de la chaîne si `logoUrl` est absent — pas de tentative de
 * détecter un échec de chargement réseau (Coil affiche alors simplement un cadre vide),
 * cohérent avec le reste du projet qui ne traite pas les logos comme une donnée critique.
 */
@Composable
private fun ChannelLogo(channel: Channel) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DpFlixColors.Surface),
        contentAlignment = Alignment.Center
    ) {
        val logoUrl = channel.logoUrl
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = channel.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = DpFlixColors.OnBackground,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
