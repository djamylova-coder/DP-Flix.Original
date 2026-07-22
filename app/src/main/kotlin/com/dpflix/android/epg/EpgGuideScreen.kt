package com.dpflix.android.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpflix.android.model.Channel
import com.dpflix.android.model.EpgProgram
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import com.dpflix.android.ui.theme.DpFlixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran Guide TV mobile (§4.6 du cahier des charges). Accessible depuis l'accueil
 * ([com.dpflix.android.home.HomeScreen]), même fond de marque que le reste de l'app
 * ([DpFlixBackground]).
 *
 * Squelette de grille posé à l'étape 9b1 (une ligne par chaîne, défilement horizontal
 * indépendant par ligne — voir l'historique de ce fichier). Cette étape 9c y ajoute :
 * - un **bandeau de navigation temporelle** (jour précédent/suivant, retour à
 *   aujourd'hui) juste sous le titre — voir [EpgDayNavigationBar] ;
 * - un **marqueur "en cours"** sur la cellule du programme en train d'être diffusé
 *   (bordure de marque), visible uniquement le jour courant ;
 * - un **détail de programme** au clic sur une cellule (titre, horaires, description),
 *   affiché en boîte de dialogue — voir [ProgramDetailDialog] ;
 * - un **positionnement initial sur "maintenant"** de chaque ligne (plutôt que de forcer
 *   l'utilisateur à faire défiler depuis le début de la journée), uniquement pour le jour
 *   courant.
 *
 * ## "Aucun guide TV disponible" (étape 9d)
 * Le libellé standard [EPG_UNAVAILABLE_LABEL] (harmonisé avec Réglages, voir sa doc) est
 * désormais affiché en tête, avec la raison technique précise
 * (`EpgGuideUiState.epgUnavailableReason`, `EpgLoadResult.Unavailable.reason`) juste en
 * dessous — plus seulement la raison brute seule comme en 9b1/9c.
 *
 * ## Zapper depuis la grille (étape 9d)
 * Le nom de chaîne, à gauche de chaque ligne, devient cliquable et navigue directement
 * vers la lecture plein écran de cette chaîne ([onNavigateToPlayerFullscreen]) — pas
 * d'étape intermédiaire de mini-aperçu comme sur l'accueil (§4.4) : contrairement à
 * l'accueil, cet écran n'embarque pas de mini-lecteur, un aperçu supplémentaire ici
 * n'aurait pas de sens. Les cellules de programme gardent leur propre clic (détail,
 * inchangé) — seul le nom de chaîne zappe.
 *
 * ## Bandeau d'heures synchronisé entre lignes : hors périmètre de cette sous-étape
 * Chaque ligne garde son propre défilement horizontal indépendant (comme en 9b1) plutôt
 * qu'un positionnement pixel par heure partagé entre toutes les lignes : la navigation
 * temporelle par **jour** (ce que demande explicitement le §4.6/l'étape 9c) ne dépend pas
 * de cette synchronisation fine, qui reste un raffinement visuel possible d'une étape
 * ultérieure si le besoin se confirme à l'usage.
 */
@Composable
fun EpgGuideScreen(
    appRepository: AppRepository,
    onBack: () -> Unit,
    onNavigateToPlayerFullscreen: (channelId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: EpgGuideViewModel = viewModel(
        factory = remember { EpgGuideViewModelFactory(appRepository) }
    )
    val uiState by viewModel.uiState.collectAsState()

    DpFlixTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                            tint = DpFlixColors.OnBackground
                        )
                    }
                    Text(
                        text = "Guide TV",
                        color = DpFlixColors.OnBackground,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                EpgDayNavigationBar(
                    dayStartMillis = uiState.selectedDayStartMillis,
                    onPreviousDay = viewModel::goToPreviousDay,
                    onNextDay = viewModel::goToNextDay,
                    onToday = viewModel::goToToday
                )

                when {
                    !uiState.hasActivePlaylist -> EpgGuideEmptyState(text = "Aucune playlist active.")
                    uiState.rows.isEmpty() -> EpgGuideEmptyState(text = "Aucune chaîne dans cette playlist pour le moment.")
                    else -> {
                        uiState.epgUnavailableReason?.let { reason ->
                            EpgUnavailableNotice(reason = reason)
                        }
                        EpgGuideRows(
                            rows = uiState.rows,
                            isToday = isToday(uiState.selectedDayStartMillis),
                            onProgramClick = { channelName, program -> viewModel.selectProgram(channelName, program) },
                            onChannelClick = { channel -> onNavigateToPlayerFullscreen(channel.id) }
                        )
                    }
                }
            }
        }

        uiState.selectedProgram?.let { selected ->
            ProgramDetailDialog(selected = selected, onDismiss = viewModel::dismissProgramDetail)
        }
    }
}

/**
 * Bandeau jour précédent/suivant + libellé du jour affiché (§4.6 "navigation
 * temporelle"). Bouton "Aujourd'hui" affiché uniquement quand on n'est pas déjà sur le
 * jour courant, pour ne pas encombrer l'écran en usage normal (la grille s'ouvre déjà sur
 * aujourd'hui par défaut, voir `EpgGuideUiState.selectedDayStartMillis`).
 */
@Composable
private fun EpgDayNavigationBar(
    dayStartMillis: Long,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousDay) {
            Icon(
                imageVector = Icons.Filled.ChevronLeft,
                contentDescription = "Jour précédent",
                tint = DpFlixColors.OnBackground
            )
        }
        Text(
            text = formatDayLabel(dayStartMillis),
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        IconButton(onClick = onNextDay) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Jour suivant",
                tint = DpFlixColors.OnBackground
            )
        }
        if (!isToday(dayStartMillis)) {
            TextButton(onClick = onToday) {
                Text("Aujourd'hui", color = DpFlixColors.Red)
            }
        }
    }
}

/**
 * Message harmonisé "Aucun guide TV disponible" (étape 9d, voir [EPG_UNAVAILABLE_LABEL])
 * : libellé standard en gras + raison technique précise en sous-texte, même structure que
 * `EpgStatusSetting` (Réglages, `SettingsScreen.kt`).
 */
@Composable
private fun EpgUnavailableNotice(reason: String) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            text = EPG_UNAVAILABLE_LABEL,
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = reason,
            color = DpFlixColors.OnBackgroundMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun EpgGuideRows(
    rows: List<EpgGuideRow>,
    isToday: Boolean,
    onProgramClick: (channelName: String, program: EpgProgram) -> Unit,
    onChannelClick: (channel: Channel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(rows, key = { it.channel.id }) { row ->
            EpgGuideRowItem(
                row = row,
                isToday = isToday,
                onProgramClick = onProgramClick,
                onChannelClick = { onChannelClick(row.channel) }
            )
        }
    }
}

@Composable
private fun EpgGuideRowItem(
    row: EpgGuideRow,
    isToday: Boolean,
    onProgramClick: (channelName: String, program: EpgProgram) -> Unit,
    onChannelClick: () -> Unit
) {
    // Positionnement initial sur le programme en cours (jour courant uniquement) plutôt
    // que sur le début de la liste : évite à l'utilisateur de devoir faire défiler chaque
    // ligne manuellement jusqu'à "maintenant" à l'ouverture de l'écran. Recalculé
    // uniquement quand la chaîne ou le jour change (clé `remember`) — un simple
    // rafraîchissement de données à jour/chaîne inchangés ne doit pas re-sauter la
    // position de lecture de l'utilisateur.
    val initialIndex = remember(row.channel.id, isToday, row.programs) {
        if (!isToday) 0 else initialProgramIndex(row.programs)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.channel.name,
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(110.dp)
                .clickable(onClick = onChannelClick)
        )
        if (row.programs.isEmpty()) {
            Text(
                text = "—",
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp)
            )
        } else {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                items(row.programs, key = { it.startTimeMillis }) { program ->
                    val isNow = isToday && program.isCurrentlyAiring(System.currentTimeMillis())
                    EpgProgramCell(
                        program = program,
                        isNow = isNow,
                        onClick = { onProgramClick(row.channel.name, program) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgProgramCell(program: EpgProgram, isNow: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DpFlixColors.Surface)
            .let { if (isNow) it.border(2.dp, DpFlixColors.Red, RoundedCornerShape(8.dp)) else it }
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatEpgTime(program.startTimeMillis)} – ${formatEpgTime(program.endTimeMillis)}",
                color = DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.labelSmall
            )
            if (isNow) {
                Text(
                    text = " · En cours",
                    color = DpFlixColors.Red,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            text = program.title,
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Détail d'un programme (§4.6 "détail de programme"), ouvert au clic sur une cellule.
 * Boîte de dialogue Material3 standard, même pattern que celles déjà utilisées dans
 * Réglages (`SettingsScreen.kt`, ex. `RenamePlaylistDialog`) : un seul bouton "Fermer",
 * pas d'action à confirmer ici (écran de consultation uniquement).
 */
@Composable
private fun ProgramDetailDialog(selected: SelectedEpgProgram, onDismiss: () -> Unit) {
    val program = selected.program
    val isNow = program.isCurrentlyAiring(System.currentTimeMillis())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = selected.channelName,
                    color = DpFlixColors.OnBackgroundMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(text = program.title, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatEpgTime(program.startTimeMillis)} – ${formatEpgTime(program.endTimeMillis)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (isNow) {
                        Text(
                            text = " · En cours",
                            color = DpFlixColors.Red,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                program.description?.let { description ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = description, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fermer", color = DpFlixColors.Red, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun EpgGuideEmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = DpFlixColors.OnBackgroundMuted,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(32.dp)
        )
    }
}

/** `HH:mm` local — inchangé depuis 9b1, la navigation par jour n'a pas besoin d'un
 *  format d'heure différent. */
private fun formatEpgTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** "Aujourd'hui" / "Demain" / "Hier" pour les jours proches (les plus fréquents en
 *  pratique), sinon jour de semaine + date complète. */
private fun formatDayLabel(dayStartMillis: Long): String {
    val diffDays = ((dayStartMillis - startOfDay(System.currentTimeMillis())) / DAY_MILLIS).toInt()
    return when (diffDays) {
        0 -> "Aujourd'hui"
        1 -> "Demain"
        -1 -> "Hier"
        else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(dayStartMillis))
    }
}

private fun isToday(dayStartMillis: Long): Boolean =
    dayStartMillis == startOfDay(System.currentTimeMillis())

/** Index du programme actuellement diffusé, sinon du premier programme à venir, sinon 0
 *  (dernier programme déjà terminé, ou liste vide) — voir [EpgGuideRowItem]. */
private fun initialProgramIndex(programs: List<EpgProgram>): Int {
    val now = System.currentTimeMillis()
    val nowIndex = programs.indexOfFirst { it.isCurrentlyAiring(now) }
    if (nowIndex >= 0) return nowIndex
    val nextIndex = programs.indexOfFirst { it.startTimeMillis > now }
    return if (nextIndex >= 0) nextIndex else 0
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
