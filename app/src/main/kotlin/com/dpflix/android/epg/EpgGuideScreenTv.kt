package com.dpflix.android.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.model.Channel
import com.dpflix.android.model.EpgProgram
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guide TV — écran Android TV (§4.6) : équivalent D-pad d'[EpgGuideScreen] (mobile),
 * **même [EpgGuideViewModel]/[EpgGuideUiState] réutilisés tels quels** (même principe que
 * [com.dpflix.android.home.HomeScreenTv] pour l'accueil — voir sa doc), seule la
 * disposition change (`androidx.tv.material3` / `androidx.tv.foundation`).
 *
 * Squelette de grille posé à l'étape 9b1 (voir l'historique de ce fichier). Cette étape
 * 9c y ajoute la navigation temporelle et le détail de programme, en miroir de la version
 * mobile — voir la doc d'[EpgGuideScreen] pour le détail des principes, non répétés ici.
 *
 * ## Cellules de programme : `Button` (tv-material3) plutôt qu'un `Box` cliquable manuel
 * Contrairement au mini-lecteur de l'accueil TV (`Box` focusable/cliquable manuel, voir
 * la doc de [com.dpflix.android.home.HomeScreenTv]), une cellule de programme est un
 * simple bloc titre + horaires sans contenu vidéo embarqué : un `Button` `tv-material3`
 * suffit, avec le même bénéfice qu'ailleurs dans l'app (ex. `ChannelCardTv`) — focus/clic
 * D-pad natifs, `TvLazyRow` fait automatiquement défiler la ligne pour garder la cellule
 * focus visible.
 *
 * ## `AlertDialog` réutilisé tel quel pour le détail programme
 * Comme dans `SettingsScreenTv` (voir sa doc sur `Switch`/`RadioButton`/`AlertDialog` :
 * "n'ont pas d'équivalent dans `androidx.tv.material3`, réutilisés tels quels... restent
 * focusables/cliquables au D-pad comme n'importe quel composant Compose standard").
 *
 * ## "Aucun guide TV disponible" + zapping depuis la grille (étape 9d)
 * Même traitement que la version mobile (voir la doc d'[EpgGuideScreen]) : libellé
 * standard [EPG_UNAVAILABLE_LABEL] harmonisé avec Réglages au-dessus de la raison
 * technique précise, et nom de chaîne désormais un [Button] `tv-material3` (comme
 * [EpgProgramCellTv]) qui navigue directement vers la lecture plein écran
 * ([onNavigateToPlayerFullscreen]).
 *
 * ## Positionnement initial sur "maintenant" : hors périmètre côté TV
 * Contrairement au mobile ([EpgGuideScreen], qui saute directement sur le programme en
 * cours à l'ouverture d'une ligne car rien d'autre ne l'y amène), le D-pad amène déjà
 * l'utilisateur cellule par cellule et `TvLazyRow` garde la cellule focus visible pendant
 * la navigation (voir la doc de [com.dpflix.android.home.HomeScreenTv] sur ce
 * comportement) : un saut automatique supplémentaire entrerait en conflit avec le focus
 * initial standard de l'écran (posé sur "Retour", pas sur une cellule de programme — voir
 * plus bas), non retenu ici pour cette raison.
 */
@Composable
fun EpgGuideScreenTv(
    appRepository: AppRepository,
    onBack: () -> Unit,
    onNavigateToPlayerFullscreen: (channelId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: EpgGuideViewModel = viewModel(
        factory = remember { EpgGuideViewModelFactory(appRepository) }
    )
    val uiState by viewModel.uiState.collectAsState()

    val backFocusRequester = remember { FocusRequester() }

    MaterialTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onBack,
                        modifier = Modifier.focusRequester(backFocusRequester)
                    ) {
                        Text("Retour")
                    }
                    Text(
                        text = "Guide TV",
                        color = DpFlixColors.OnBackground,
                        fontSize = 28.sp,
                        modifier = Modifier.padding(start = 24.dp)
                    )
                }

                EpgDayNavigationBarTv(
                    dayStartMillis = uiState.selectedDayStartMillis,
                    onPreviousDay = viewModel::goToPreviousDay,
                    onNextDay = viewModel::goToNextDay,
                    onToday = viewModel::goToToday
                )

                when {
                    !uiState.hasActivePlaylist -> EpgGuideEmptyStateTv(text = "Aucune playlist active.")
                    uiState.rows.isEmpty() -> EpgGuideEmptyStateTv(text = "Aucune chaîne dans cette playlist pour le moment.")
                    else -> {
                        uiState.epgUnavailableReason?.let { reason ->
                            EpgUnavailableNoticeTv(reason = reason)
                        }
                        EpgGuideRowsTv(
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
            ProgramDetailDialogTv(selected = selected, onDismiss = viewModel::dismissProgramDetail)
        }
    }

    LaunchedEffect(Unit) {
        backFocusRequester.requestFocus()
    }
}

/** Équivalent TV d'[EpgGuideScreen]`.EpgDayNavigationBar` — mêmes boutons (jour
 *  précédent/suivant/aujourd'hui), en `Button` `tv-material3` pour le focus/clic D-pad. */
@Composable
private fun EpgDayNavigationBarTv(
    dayStartMillis: Long,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onPreviousDay) {
            Text("◀ Jour précédent")
        }
        Text(
            text = formatDayLabelTv(dayStartMillis),
            color = DpFlixColors.OnBackground,
            fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Button(onClick = onNextDay) {
            Text("Jour suivant ▶")
        }
        if (!isToday(dayStartMillis)) {
            Button(onClick = onToday, modifier = Modifier.padding(start = 24.dp)) {
                Text("Aujourd'hui")
            }
        }
    }
}

/**
 * Message harmonisé "Aucun guide TV disponible" (étape 9d, voir [EPG_UNAVAILABLE_LABEL])
 * — équivalent TV d'[EpgUnavailableNotice] (mobile, `EpgGuideScreen.kt`).
 */
@Composable
private fun EpgUnavailableNoticeTv(reason: String) {
    Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp)) {
        Text(text = EPG_UNAVAILABLE_LABEL, color = DpFlixColors.OnBackground, fontSize = 16.sp)
        Text(text = reason, color = DpFlixColors.OnBackgroundMuted, fontSize = 14.sp)
    }
}

@Composable
private fun EpgGuideRowsTv(
    rows: List<EpgGuideRow>,
    isToday: Boolean,
    onProgramClick: (channelName: String, program: EpgProgram) -> Unit,
    onChannelClick: (channel: Channel) -> Unit
) {
    TvLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(rows, key = { it.channel.id }) { row ->
            EpgGuideRowItemTv(
                row = row,
                isToday = isToday,
                onProgramClick = onProgramClick,
                onChannelClick = { onChannelClick(row.channel) }
            )
        }
    }
}

@Composable
private fun EpgGuideRowItemTv(
    row: EpgGuideRow,
    isToday: Boolean,
    onProgramClick: (channelName: String, program: EpgProgram) -> Unit,
    onChannelClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onChannelClick,
            modifier = Modifier.width(160.dp)
        ) {
            Text(
                text = row.channel.name,
                color = DpFlixColors.OnBackground,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (row.programs.isEmpty()) {
            Text(
                text = "—",
                color = DpFlixColors.OnBackgroundMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
        } else {
            TvLazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(start = 12.dp)
            ) {
                items(row.programs, key = { it.startTimeMillis }) { program ->
                    val isNow = isToday && program.isCurrentlyAiring(System.currentTimeMillis())
                    EpgProgramCellTv(
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
private fun EpgProgramCellTv(program: EpgProgram, isNow: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .let { if (isNow) it.border(2.dp, DpFlixColors.Red, RoundedCornerShape(8.dp)) else it }
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${formatEpgTimeTv(program.startTimeMillis)} – ${formatEpgTimeTv(program.endTimeMillis)}",
                    color = DpFlixColors.OnBackgroundMuted,
                    fontSize = 12.sp
                )
                if (isNow) {
                    Text(text = " · En cours", color = DpFlixColors.Red, fontSize = 12.sp)
                }
            }
            Text(
                text = program.title,
                color = DpFlixColors.OnBackground,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Équivalent TV d'[EpgGuideScreen]`.ProgramDetailDialog` — voir sa doc, même contenu
 *  (chaîne, titre, horaires, "En cours" le cas échéant, description). `AlertDialog` +
 *  `M3Text` réutilisés tels quels (`androidx.compose.material3`, alias `M3Text` pour ne
 *  pas entrer en conflit avec `androidx.tv.material3.Text` déjà importé sous `Text` dans
 *  ce fichier) — même pattern que `SettingsScreenTv` (voir la doc de la classe). */
@Composable
private fun ProgramDetailDialogTv(selected: SelectedEpgProgram, onDismiss: () -> Unit) {
    val program = selected.program
    val isNow = program.isCurrentlyAiring(System.currentTimeMillis())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                M3Text(text = selected.channelName, color = DpFlixColors.OnBackgroundMuted)
                M3Text(text = program.title)
            }
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    M3Text(text = "${formatEpgTimeTv(program.startTimeMillis)} – ${formatEpgTimeTv(program.endTimeMillis)}")
                    if (isNow) {
                        M3Text(text = " · En cours", color = DpFlixColors.Red)
                    }
                }
                program.description?.let { description ->
                    Spacer(modifier = Modifier.height(8.dp))
                    M3Text(text = description)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                M3Text(text = "Fermer", color = DpFlixColors.Red)
            }
        }
    )
}

@Composable
private fun EpgGuideEmptyStateTv(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = DpFlixColors.OnBackgroundMuted,
            fontSize = 18.sp,
            modifier = Modifier.padding(32.dp)
        )
    }
}

/** Voir `formatEpgTime` (mobile, `EpgGuideScreen.kt`) — dupliqué pour la même raison que
 *  le reste de l'écran (pas de partage de Composable entre les deux points d'entrée). */
private fun formatEpgTimeTv(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** Voir `formatDayLabel` (mobile, `EpgGuideScreen.kt`) — même logique, dupliquée pour la
 *  même raison. */
private fun formatDayLabelTv(dayStartMillis: Long): String {
    val diffDays = ((dayStartMillis - startOfDay(System.currentTimeMillis())) / DAY_MILLIS_TV).toInt()
    return when (diffDays) {
        0 -> "Aujourd'hui"
        1 -> "Demain"
        -1 -> "Hier"
        else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(dayStartMillis))
    }
}

private fun isToday(dayStartMillis: Long): Boolean =
    dayStartMillis == startOfDay(System.currentTimeMillis())

private const val DAY_MILLIS_TV = 24 * 60 * 60 * 1000L
