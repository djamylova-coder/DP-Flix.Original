package com.dpflix.android.home

import com.dpflix.android.model.Channel
import com.dpflix.android.model.ChannelCategory

/**
 * État de l'écran d'accueil (§4.4 du cahier des charges, étape 6c).
 *
 * @property hasActivePlaylist Distingue "aucune playlist active" (ne devrait pas arriver en
 *   pratique : `DpFlixNavHost` ne route vers Accueil que si `observeActive()` a renvoyé une
 *   playlist, voir 6a/6b) de "playlist active mais sans aucune chaîne" (import Xtream/M3U
 *   ayant échoué après l'enregistrement de la playlist, cas explicitement accepté par
 *   `OnboardingViewModel` depuis 6b) — les deux cas affichent un état vide, mais avec un
 *   message différent (voir [HomeScreen]).
 * @property categories Rangées horizontales de chaînes groupées par catégorie, déjà triées
 *   par [com.dpflix.android.repository.ChannelRepository.observeGroupedByCategory].
 * @property previewChannel Chaîne actuellement ouverte dans le mini-lecteur (§4.4 "Zone
 *   haute"), `null` tant qu'aucune chaîne n'a encore été cliquée.
 */
data class HomeUiState(
    val hasActivePlaylist: Boolean = false,
    val categories: List<ChannelCategory> = emptyList(),
    val previewChannel: Channel? = null
)
