package com.dpflix.android.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dpflix.android.model.Channel
import com.dpflix.android.model.EpgLoadResult
import com.dpflix.android.model.EpgProgram
import com.dpflix.android.repository.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Logique de l'écran Guide TV (§4.6, partagé mobile/TV comme
 * [com.dpflix.android.home.HomeViewModel] l'est déjà pour l'accueil : même
 * `EpgGuideUiState`/`EpgGuideViewModel` réutilisés tels quels par les deux écrans, seule
 * la disposition change).
 *
 * ## Chaînes + EPG : réactif comme en 9b1, désormais recombiné avec le jour affiché
 * Les chaînes suivent `ChannelRepository.observeByPlaylist` (`Flow`, réagit si la
 * playlist active est modifiée ailleurs), le chargement EPG (`EpgRepository.getOrLoad`,
 * étape 9a) reste une fonction suspendue à résultat unique déclenchée une fois par
 * playlist via `flow { emit(...) }` — inchangé depuis 9b1 (voir la doc historique de
 * cette classe pour le détail). Nouveau à cette étape 9c : le résultat combiné
 * (chaînes + EPG) est recombiné une seconde fois avec [selectedDayStart], pour que
 * changer de jour ([goToPreviousDay]/[goToNextDay]/[goToToday]) recalcule immédiatement
 * les lignes affichées sans retélécharger le guide (toujours un seul chargement réseau
 * par playlist, voir la doc d'[com.dpflix.android.repository.EpgRepository]).
 *
 * ## Filtrage par jour
 * Un programme est retenu pour [selectedDayStart] s'il **chevauche** ce jour (son
 * intervalle `[startTimeMillis, endTimeMillis)` croise `[dayStart, dayStart + 24h)`),
 * pas seulement s'il y démarre — un programme commencé juste avant minuit et qui déborde
 * sur le jour suivant doit rester visible sur les deux jours concernés.
 *
 * ## Sélection de détail : état transitoire, préservé lors des recalculs de données
 * [selectProgram]/[dismissProgramDetail] ne touchent que [EpgGuideUiState.selectedProgram]
 * via `MutableStateFlow.update { it.copy(...) }` — même principe que
 * `HomeViewModel.onChannelClicked`/`dismissPreview` pour `previewChannel` (voir sa doc) :
 * la boîte de dialogue de détail n'est donc pas fermée par un simple rafraîchissement de
 * la liste de chaînes déclenché ailleurs (Room), seul un changement de jour ou de
 * playlist active la referme (recalcul complet de [EpgGuideUiState] par le flux principal
 * ci-dessous, qui ne reporte pas l'ancien [EpgGuideUiState.selectedProgram]).
 */
class EpgGuideViewModel(appRepository: AppRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(EpgGuideUiState())
    val uiState: StateFlow<EpgGuideUiState> = _uiState.asStateFlow()

    private val selectedDayStart = MutableStateFlow(startOfDay(System.currentTimeMillis()))

    init {
        viewModelScope.launch {
            appRepository.playlists.observeActive()
                .flatMapLatest { playlist ->
                    if (playlist == null) {
                        flowOf<PlaylistChannelsEpg>(PlaylistChannelsEpg(hasActivePlaylist = false, channels = emptyList(), epgResult = null))
                    } else {
                        val epgResultFlow = flow { emit(appRepository.epg.getOrLoad(playlist)) }
                        appRepository.channels.observeByPlaylist(playlist.id)
                            .combine(epgResultFlow) { channels, epgResult ->
                                PlaylistChannelsEpg(hasActivePlaylist = true, channels = channels, epgResult = epgResult)
                            }
                    }
                }
                .combine(selectedDayStart) { data, dayStart -> data to dayStart }
                .collect { (data, dayStart) ->
                    val programsByChannel = (data.epgResult as? EpgLoadResult.Success)?.programsByChannel
                    val dayEnd = addDays(dayStart, 1)
                    val rows = data.channels.map { channel ->
                        val allPrograms = channel.tvgId?.let { programsByChannel?.get(it) }.orEmpty()
                        val dayPrograms = allPrograms
                            .filter { it.startTimeMillis < dayEnd && it.endTimeMillis > dayStart }
                            .sortedBy { it.startTimeMillis }
                        EpgGuideRow(channel = channel, programs = dayPrograms)
                    }
                    _uiState.value = EpgGuideUiState(
                        hasActivePlaylist = data.hasActivePlaylist,
                        rows = rows,
                        epgUnavailableReason = (data.epgResult as? EpgLoadResult.Unavailable)?.reason,
                        selectedDayStartMillis = dayStart,
                        selectedProgram = null
                    )
                }
        }
    }

    /** Jour précédent (§4.6 "navigation temporelle"). Aucune borne basse : un guide XMLTV
     *  n'a en pratique presque jamais de données passées, mais rien n'empêche de naviguer
     *  au-delà — la grille affiche simplement des lignes vides ([EpgGuideRow.programs]). */
    fun goToPreviousDay() {
        selectedDayStart.update { addDays(it, -1) }
    }

    /** Jour suivant. Même absence de borne haute que [goToPreviousDay], pour la même
     *  raison (limité en pratique par la couverture réelle du guide XMLTV chargé, pas
     *  par une limite arbitraire côté UI). */
    fun goToNextDay() {
        selectedDayStart.update { addDays(it, 1) }
    }

    /** Retour direct à aujourd'hui, raccourci pratique après plusieurs jours de
     *  navigation plutôt que de forcer à répéter [goToPreviousDay]/[goToNextDay]. */
    fun goToToday() {
        selectedDayStart.value = startOfDay(System.currentTimeMillis())
    }

    /** Ouvre le détail d'un programme (clic/OK sur une cellule, §4.6). [channelName] est
     *  fourni par l'appelant (déjà disponible via `EpgGuideRow.channel.name`) plutôt que
     *  recherché ici — voir la doc de [SelectedEpgProgram]. */
    fun selectProgram(channelName: String, program: EpgProgram) {
        _uiState.update { it.copy(selectedProgram = SelectedEpgProgram(channelName, program)) }
    }

    /** Ferme la boîte de dialogue de détail. */
    fun dismissProgramDetail() {
        _uiState.update { it.copy(selectedProgram = null) }
    }

    /** Regroupement interne du dernier triplet (playlist active / ses chaînes / son EPG),
     *  avant recombinaison avec [selectedDayStart] — évite un `Triple` anonyme peu lisible
     *  au point d'appel. */
    private data class PlaylistChannelsEpg(
        val hasActivePlaylist: Boolean,
        val channels: List<Channel>,
        val epgResult: EpgLoadResult?
    )
}

class EpgGuideViewModelFactory(private val appRepository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return EpgGuideViewModel(appRepository) as T
    }
}
