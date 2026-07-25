package com.dpflix.android.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ChannelCategory
import com.dpflix.android.player.PlayerScreen
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.ChannelLogo
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import com.dpflix.android.ui.theme.DpFlixTheme

/**
 * Écran d'accueil (§4.4 du cahier des charges, étape 6c) : remplace le placeholder de
 * l'étape 6a et fait disparaître le banc de test manuel du lecteur (étape 5a) — voir la
 * doc historique sur `DpFlixNavHost`, qui gardait vivant le cas spécial `channelId ==
 * "test"` uniquement en attendant que cet écran fournisse de vrais IDs de chaîne.
 *
 * Fond d'écran partagé avec l'onboarding (§4.4 "identique à l'onboarding") via
 * [DpFlixBackground], comme prévu dès l'étape 6b.
 *
 * ## Mini-lecteur et EPG (branché le 25 juillet 2026)
 * Le §4.4 décrit, sous la vidéo du mini-lecteur, "le nom de la chaîne + programme en
 * cours, si EPG disponible". Désormais résolu via [HomeViewModel.loadPreviewProgramTitle]
 * (même logique que l'OSD du lecteur plein écran, `PlayerScreen.currentProgramTitle`) et
 * exposé par [HomeUiState.previewProgramTitle] — `null` (donc rien affiché) si `tvgId`
 * est absent sur la chaîne ou si aucun guide EPG n'est disponible pour la playlist,
 * équivalent au cas "EPG indisponible" du cahier des charges.
 *
 * ## Bouton Guide TV retiré (25 juillet 2026)
 * L'accès au Guide TV ([com.dpflix.android.epg.EpgGuideScreen], §4.6) qui vivait ici
 * depuis l'étape 9b1 a été retiré à la demande de l'utilisateur (latence/gels sur une
 * playlist de 20000+ chaînes) — voir la doc de `DpFlixDestination` pour le détail de ce
 * qui reste de la gestion EPG (OSD, Réglages) indépendamment de cet écran.
 */
@Composable
fun HomeScreen(
    appRepository: AppRepository,
    onNavigateToSettings: () -> Unit,
    onNavigateToPlayerFullscreen: (channelId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = viewModel(
        factory = remember { HomeViewModelFactory(appRepository) }
    )
    val uiState by viewModel.uiState.collectAsState()

    DpFlixTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DP-Flix",
                        color = DpFlixColors.OnBackground,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Réglages",
                                tint = DpFlixColors.OnBackground
                            )
                        }
                    }
                }

                val preview = uiState.previewChannel
                if (preview != null) {
                    MiniPlayer(
                        channel = preview,
                        programTitle = uiState.previewProgramTitle,
                        onExpand = { onNavigateToPlayerFullscreen(preview.id) },
                        onDismiss = viewModel::dismissPreview
                    )
                }

                when {
                    !uiState.hasActivePlaylist -> EmptyState(text = "Aucune playlist active.")
                    uiState.categories.all { it.channels.isEmpty() } -> EmptyState(
                        text = "Aucune chaîne dans cette playlist pour le moment."
                    )
                    else -> ChannelCategoryList(
                        categories = uiState.categories,
                        selectedChannelId = preview?.id,
                        onChannelClick = { channel ->
                            val goFullscreen = viewModel.onChannelClicked(channel)
                            if (goFullscreen) onNavigateToPlayerFullscreen(channel.id)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Zone haute (§4.4) : vidéo en cours (avec le son — [PlayerScreen] gère déjà l'audio et
 * ses propres états de chargement/erreur, réutilisé tel quel) + infos de diffusion en
 * dessous. Bouton de fermeture ajouté (voir la doc de [HomeViewModel.dismissPreview]).
 */
@Composable
private fun MiniPlayer(channel: Channel, programTitle: String?, onExpand: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable(onClick = onExpand)
        ) {
            PlayerScreen(channel = channel, modifier = Modifier.fillMaxSize(), osdEnabled = false)
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Fermer l'aperçu",
                    tint = Color.White
                )
            }
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Text(
                text = channel.name,
                color = DpFlixColors.OnBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (programTitle != null) {
                Text(
                    text = programTitle,
                    color = DpFlixColors.OnBackgroundMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ChannelCategoryList(
    categories: List<ChannelCategory>,
    selectedChannelId: String?,
    onChannelClick: (Channel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        items(categories, key = { it.name }) { category ->
            if (category.channels.isNotEmpty()) {
                CategoryRow(
                    category = category,
                    selectedChannelId = selectedChannelId,
                    onChannelClick = onChannelClick
                )
            }
        }
    }
}

/** Une rangée horizontale (§4.4 "style Netflix") : nom de catégorie en haut à gauche, défilement horizontal des chaînes. */
@Composable
private fun CategoryRow(
    category: ChannelCategory,
    selectedChannelId: String?,
    onChannelClick: (Channel) -> Unit
) {
    Column {
        Text(
            text = category.name.ifBlank { "Sans catégorie" },
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(category.channels, key = { it.id }) { channel ->
                ChannelCard(
                    channel = channel,
                    isSelected = channel.id == selectedChannelId,
                    onClick = { onChannelClick(channel) }
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) DpFlixColors.Red.copy(alpha = 0.25f) else DpFlixColors.Surface)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        channel.displayNumber?.let { number ->
            Text(
                text = "$number",
                color = if (isSelected) DpFlixColors.Red else DpFlixColors.OnBackgroundMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // [Fix logos accueil] channel.logoUrl était collecté (M3U tvg-logo / Xtream
        // stream_icon) et déjà utilisé dans l'OSD du lecteur, mais jamais affiché ici —
        // voir la doc de com.dpflix.android.ui.ChannelLogo pour le détail.
        ChannelLogo(channel = channel, size = 48.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = channel.name,
            color = DpFlixColors.OnBackground,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = DpFlixColors.OnBackgroundMuted,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(32.dp)
        )
    }
}
