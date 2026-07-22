package com.dpflix.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ChannelCategory
import com.dpflix.android.player.PlayerScreen
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors

/**
 * Accueil TV (§4.4 du cahier des charges, étape 7c) — équivalent TV de [HomeScreen]
 * (mobile, 6c) : **même [HomeViewModel]/[HomeUiState] réutilisés tels quels** (même
 * principe que [com.dpflix.android.onboarding.OnboardingScreenTv] à l'étape 7b — voir sa
 * doc), seule la disposition et les composants changent (`androidx.tv.material3` /
 * `androidx.tv.foundation`, navigation D-pad horizontale/verticale).
 *
 * Remplace le placeholder Accueil de [com.dpflix.android.nav.DpFlixTvNavHost] posé à
 * l'étape 7a, et fait disparaître son cas spécial `channelId == "test"` : comme pour la
 * transition équivalente côté mobile (6a → 6c, voir la doc de
 * [com.dpflix.android.nav.DpFlixNavHost]), cet écran fournit désormais toujours de vrais
 * IDs de chaîne.
 *
 * ## Grilles D-pad : `LazyColumn`/`LazyRow` (Compose Foundation standard)
 * Catégories empilées verticalement (`LazyColumn`), chaînes de chaque catégorie
 * défilant horizontalement (`LazyRow` imbriqué). Les composants `TvLazyColumn`/`TvLazyRow`
 * de `tv-foundation`, utilisés jusqu'à l'étape 10, ont été dépréciés puis retirés par
 * Google : depuis Compose Foundation 1.7+ (stable en 1.8+), `LazyColumn`/`LazyRow`
 * intègrent nativement le même comportement (faire défiler la liste pour garder l'élément
 * focus visible au D-pad) — voir la doc officielle "Create scrollable layouts for TV".
 *
 * ## Focus initial
 * Posé sur la toute première carte de chaîne de la première catégorie non vide dès que
 * les données arrivent (`LaunchedEffect` déclenché une seule fois, via
 * `hasRequestedInitialFocus`) — même mécanique que partout ailleurs côté TV depuis
 * l'étape 2b (rien n'est focus par défaut sur Android TV).
 *
 * ## Mini-lecteur (aperçu)
 * Reste un `Box` focusable/cliquable manuel (pas de composant `tv-material3` dédié type
 * "carte média" utilisé ici) plutôt qu'un [Button] : contrairement aux boutons texte de
 * l'onboarding (7b), le mini-lecteur embarque une vraie surface vidéo
 * ([PlayerScreen]) et un simple encadré qui s'éclaire en rouge au focus (`onFocusChanged`)
 * rend mieux cet effet qu'un `Button` dont le style par défaut n'est pas pensé pour du
 * contenu vidéo. Bouton de fermeture Material3 réutilisé tel quel (mobile, 6c) : reste
 * focusable/cliquable au D-pad comme n'importe quel composant Compose standard.
 *
 * ## Accès au Guide TV (§4.6, étape 9b1)
 * Nouveau bouton "Guide TV" à côté de "Réglages", même équivalent TV du bouton mobile
 * (voir la doc de [HomeScreen]) — navigue vers [com.dpflix.android.epg.EpgGuideScreenTv].
 */
@Composable
fun HomeScreenTv(
    appRepository: AppRepository,
    onNavigateToSettings: () -> Unit,
    onNavigateToEpgGuide: () -> Unit,
    onNavigateToPlayerFullscreen: (channelId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: HomeViewModel = viewModel(
        factory = remember { HomeViewModelFactory(appRepository) }
    )
    val uiState by viewModel.uiState.collectAsState()

    val epgGuideFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val firstChannelFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

    MaterialTheme {
        DpFlixBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "DP-Flix", color = DpFlixColors.OnBackground, fontSize = 28.sp)
                    Row {
                        Button(
                            onClick = onNavigateToEpgGuide,
                            modifier = Modifier.focusRequester(epgGuideFocusRequester)
                        ) {
                            Text("Guide TV")
                        }
                        Button(
                            onClick = onNavigateToSettings,
                            modifier = Modifier
                                .focusRequester(settingsFocusRequester)
                                .padding(start = 12.dp)
                        ) {
                            Text("Réglages")
                        }
                    }
                }

                val preview = uiState.previewChannel
                if (preview != null) {
                    MiniPlayerTv(
                        channel = preview,
                        onExpand = { onNavigateToPlayerFullscreen(preview.id) },
                        onDismiss = viewModel::dismissPreview
                    )
                }

                when {
                    !uiState.hasActivePlaylist -> EmptyStateTv(text = "Aucune playlist active.")
                    uiState.categories.all { it.channels.isEmpty() } -> EmptyStateTv(
                        text = "Aucune chaîne dans cette playlist pour le moment."
                    )
                    else -> ChannelCategoryListTv(
                        categories = uiState.categories,
                        selectedChannelId = preview?.id,
                        firstChannelFocusRequester = firstChannelFocusRequester,
                        onChannelClick = { channel ->
                            val goFullscreen = viewModel.onChannelClicked(channel)
                            if (goFullscreen) onNavigateToPlayerFullscreen(channel.id)
                        }
                    )
                }
            }
        }
    }

    if (!hasRequestedInitialFocus && uiState.categories.any { it.channels.isNotEmpty() }) {
        LaunchedEffect(Unit) {
            firstChannelFocusRequester.requestFocus()
            hasRequestedInitialFocus = true
        }
    }
}

/**
 * Zone haute (§4.4), équivalent TV de `MiniPlayer` (mobile, `HomeScreen.kt`) — voir la
 * doc de [HomeScreenTv] sur le choix d'un `Box` focusable plutôt qu'un [Button] ici.
 * Même absence d'infos EPG que côté mobile : voir la doc de [HomeScreen] à ce sujet,
 * inchangée à cette sous-étape.
 */
@Composable
private fun MiniPlayerTv(channel: Channel, onExpand: () -> Unit, onDismiss: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .clickable(onClick = onExpand)
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = DpFlixColors.Red,
                    shape = RoundedCornerShape(12.dp)
                )
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
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
        Text(
            text = channel.name,
            color = DpFlixColors.OnBackground,
            fontSize = 20.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        // Programme en cours : non affiché, voir la doc de HomeScreenTv (EPG pas encore branché).
    }
}

@Composable
private fun ChannelCategoryListTv(
    categories: List<ChannelCategory>,
    selectedChannelId: String?,
    firstChannelFocusRequester: FocusRequester,
    onChannelClick: (Channel) -> Unit
) {
    // Calculé une fois ici plutôt que dans chaque rangée : c'est la SEULE carte de tout
    // l'écran qui doit porter le FocusRequester initial (voir la doc de [HomeScreenTv]).
    val firstFocusableChannelId = categories.firstOrNull { it.channels.isNotEmpty() }
        ?.channels?.firstOrNull()?.id

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        items(categories, key = { it.name }) { category ->
            if (category.channels.isNotEmpty()) {
                CategoryRowTv(
                    category = category,
                    selectedChannelId = selectedChannelId,
                    firstFocusableChannelId = firstFocusableChannelId,
                    firstChannelFocusRequester = firstChannelFocusRequester,
                    onChannelClick = onChannelClick
                )
            }
        }
    }
}

/** Une rangée horizontale (§4.4 "style Netflix"), défilement D-pad via `LazyRow`. */
@Composable
private fun CategoryRowTv(
    category: ChannelCategory,
    selectedChannelId: String?,
    firstFocusableChannelId: String?,
    firstChannelFocusRequester: FocusRequester,
    onChannelClick: (Channel) -> Unit
) {
    Column {
        Text(
            text = category.name.ifBlank { "Sans catégorie" },
            color = DpFlixColors.OnBackground,
            fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(category.channels, key = { it.id }) { channel ->
                ChannelCardTv(
                    channel = channel,
                    isSelected = channel.id == selectedChannelId,
                    focusRequester = if (channel.id == firstFocusableChannelId) firstChannelFocusRequester else null,
                    onClick = { onChannelClick(channel) }
                )
            }
        }
    }
}

@Composable
private fun ChannelCardTv(
    channel: Channel,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(160.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
    ) {
        Column {
            channel.displayNumber?.let { number ->
                Text(text = "$number", color = DpFlixColors.OnBackgroundMuted, fontSize = 14.sp)
            }
            Text(text = channel.name, color = DpFlixColors.OnBackground, fontSize = 16.sp)
            if (isSelected) {
                Text(text = "En aperçu", color = DpFlixColors.Red, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyStateTv(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = DpFlixColors.OnBackgroundMuted,
            fontSize = 18.sp,
            modifier = Modifier.padding(32.dp)
        )
    }
}
