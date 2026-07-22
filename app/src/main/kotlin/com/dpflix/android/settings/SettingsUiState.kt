package com.dpflix.android.settings

import com.dpflix.android.model.Channel
import com.dpflix.android.model.Playlist

/**
 * Sections de l'écran Réglages (§5 du cahier des charges). Un seul écran top-level
 * ([com.dpflix.android.nav.DpFlixDestination.Settings]) avec une navigation interne
 * simple (état + [androidx.activity.compose.BackHandler], voir la doc de
 * `SettingsScreen`) plutôt qu'un `NavHost` imbriqué : la profondeur reste toujours de 1
 * (liste → section → retour à la liste), ça ne justifie pas une pile de navigation
 * dédiée.
 *
 * [General] (6d), [Player] (6e), [Playlists] et [ChannelNumbering] (6f) ont un contenu
 * réel. [Epg] a un contenu réel depuis 6g-1 (statut, lecture seule), 6g-2-1
 * (saisie/persistance d'une source manuelle URL ou fichier local) et 6g-2-2
 * (rafraîchissement réel : téléchargement/lecture + parsing + erreurs). [Diagnostic] a un
 * contenu réel depuis 6g-3 (structure des métriques de §5.5, une seule réellement mesurée)
 * et 6g-4 (compteurs de segments + journal d'erreurs, rafraîchissement périodique — voir
 * [DiagnosticState]), ce qui clôt l'étape 6g dans son intégralité.
 */
sealed class SettingsSection(val title: String) {
    object List : SettingsSection("Réglages")
    object General : SettingsSection("Général")
    object Player : SettingsSection("Lecteur")
    object Playlists : SettingsSection("Playlists")
    object ChannelNumbering : SettingsSection("Numérotation des chaînes")
    object Epg : SettingsSection("Guide TV (EPG)")
    object Diagnostic : SettingsSection("Diagnostic")
}

/**
 * État de l'écran Réglages (§5.6 "Général" depuis 6d, §5.1 "Lecteur" depuis 6e, §5.2
 * "Playlists" + §5.3 "Numérotation des chaînes" depuis 6f). [Epg]/[Diagnostic] n'ont pas
 * encore d'état propre (contenu réel : 6g).
 *
 * @property activePlaylist Sert à la fois d'affichage ("reprise auto : pour telle
 *   playlist") et de cible d'écriture pour [SettingsViewModel.setResumeLastChannelOnStartForActivePlaylist]
 *   — voir la doc de [SettingsScreen] sur ce choix.
 * @property channelCounts Nombre de chaînes par playlist (`playlist.id` → compte),
 *   utilisé par la section Playlists (§4.3 : "nombre de chaînes"). Dérivé des mêmes
 *   flux de chaînes que [numberingChannels] plutôt que d'une requête `COUNT` dédiée —
 *   voir la doc de [SettingsViewModel].
 * @property pendingDeletePlaylistId Playlist en attente de confirmation de suppression
 *   (dialogue affiché par [SettingsScreen] si non nul), même mécanique que
 *   [showResetConfirmation] pour la réinitialisation complète.
 * @property showAddPlaylist Bascule locale entre la liste des playlists et l'assistant
 *   d'ajout ([com.dpflix.android.onboarding.OnboardingScreen] réutilisé tel quel, §4.3 :
 *   "relance le flux 4.2").
 * @property numberingPlaylistId Playlist actuellement affichée par la section
 *   Numérotation des chaînes (§5.3, isolée par playlist) ; `null` tant qu'aucune playlist
 *   n'existe.
 * @property numberingChannels Chaînes de [numberingPlaylistId], déjà triées par
 *   catégorie puis numéro affiché (voir `ChannelDao.observeByPlaylist`).
 * @property epgPlaylistId Playlist actuellement affichée par la section Guide TV (EPG)
 *   (§5.4, isolée par playlist comme [numberingPlaylistId]). `null` = pas encore de choix
 *   explicite ; l'écran retombe alors sur [activePlaylist] (voir `SettingsScreen`).
 *   Contrairement à [numberingPlaylistId], aucune collection dérivée dédiée n'est
 *   nécessaire : la [Playlist] elle-même (déjà dans [playlists]) porte tout ce qu'affiche
 *   cette section (statut, URL effective, dernière mise à jour).
 * @property epgRefreshInProgress Rafraîchissement EPG en cours (§5.4, 6g-2-2) pour la
 *   playlist actuellement affichée par cette section — désactive le bouton "Rafraîchir
 *   l'EPG" pendant l'opération plutôt que de permettre des clics concurrents.
 * @property epgRefreshError Message d'erreur du dernier rafraîchissement EPG en échec
 *   (fichier invalide, URL injoignable, §5.4/6g-2-2), affiché sous le bouton
 *   "Rafraîchir l'EPG". `null` en l'absence d'échec récent ou après un changement de
 *   playlist sélectionnée (voir `SettingsViewModel.selectEpgPlaylist`) — une erreur ne
 *   doit pas rester affichée pour une playlist différente de celle qui l'a provoquée.
 * @property diagnosticState Métriques de la section Diagnostic (§5.5, 6g-3) — voir
 *   [DiagnosticState] pour le détail de ce qui est réellement mesuré à ce stade.
 *   Global à l'appli (pas par playlist, contrairement à [epgPlaylistId]/[numberingPlaylistId]) :
 *   ces métriques décrivent le lecteur/le cache, pas une playlist en particulier.
 */
data class SettingsUiState(
    val generalSettings: GeneralSettings = GeneralSettings(),
    val playerSettings: PlayerSettings = PlayerSettings(),
    val playlists: List<Playlist> = emptyList(),
    val activePlaylist: Playlist? = null,
    val showResetConfirmation: Boolean = false,
    val cacheClearedTick: Int = 0,
    val channelCounts: Map<String, Int> = emptyMap(),
    val pendingDeletePlaylistId: String? = null,
    val showAddPlaylist: Boolean = false,
    val numberingPlaylistId: String? = null,
    val numberingChannels: List<Channel> = emptyList(),
    val epgPlaylistId: String? = null,
    val epgRefreshInProgress: Boolean = false,
    val epgRefreshError: String? = null,
    val diagnosticState: DiagnosticState = DiagnosticState()
)
