package com.dpflix.android.onboarding

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dpflix.android.model.Playlist
import com.dpflix.android.model.PlaylistType
import com.dpflix.android.network.XtreamClient
import com.dpflix.android.network.XtreamCredentials
import com.dpflix.android.network.XtreamResult
import com.dpflix.android.parser.M3uParser
import com.dpflix.android.repository.AddPlaylistResult
import com.dpflix.android.repository.AppRepository
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Logique de l'écran d'onboarding (§4.2 du cahier des charges, étape 6b).
 *
 * Orchestre ce que [XtreamClient] et [M3uParser] documentent explicitement comme hors de
 * leur périmètre respectif : décider s'il faut appeler `fetchLiveChannels` selon la case
 * "Inclure les chaînes de télévision" (voir la doc de `XtreamClient`), et récupérer le
 * contenu texte (téléchargement URL ou lecture fichier local) avant de le passer à
 * `M3uParser.parse` (fonction pure, voir sa doc). Cette orchestration n'existait dans
 * aucune couche avant cette sous-étape — elle vit ici plutôt que dans les repositories
 * (4a-4d) pour rester propre à l'écran qui la déclenche ; elle sera réutilisée telle
 * quelle par l'écran "Ajouter une playlist" de Réglages → Playlists (§4.3, étape 6f).
 *
 * @param appContext Contexte **application** (pas Activity) : uniquement utilisé pour
 * lire un fichier importé via [android.content.ContentResolver] et le recopier dans le
 * stockage privé de l'app (`filesDir/playlists/`), aucune référence longue durée à une UI.
 */
class OnboardingViewModel(
    private val appRepository: AppRepository,
    private val appContext: Context,
    private val xtreamClient: XtreamClient = XtreamClient(),
    private val httpClient: OkHttpClient = OkHttpClient()
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun selectType(type: PlaylistType) {
        val nextStep = if (type == PlaylistType.XTREAM) OnboardingStep.XtreamForm else OnboardingStep.M3uForm
        _uiState.update { it.copy(step = nextStep, errorMessage = null) }
    }

    fun backToChooseType() {
        _uiState.update { it.copy(step = OnboardingStep.ChooseType, errorMessage = null, isSubmitting = false) }
    }

    fun updateXtreamForm(update: (XtreamFormState) -> XtreamFormState) {
        _uiState.update { it.copy(xtreamForm = update(it.xtreamForm), errorMessage = null) }
    }

    fun updateM3uForm(update: (M3uFormState) -> M3uFormState) {
        _uiState.update { it.copy(m3uForm = update(it.m3uForm), errorMessage = null) }
    }

    /** Bouton "Suivant" du formulaire Xtream Codes (§4.2 Étape 2a). */
    fun submitXtream(onComplete: () -> Unit) {
        val form = _uiState.value.xtreamForm
        if (form.serverUrl.isBlank() || form.username.isBlank() || form.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Adresse du serveur, nom d'utilisateur et mot de passe sont obligatoires") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val credentials = XtreamCredentials(
                serverUrl = form.serverUrl.trim(),
                username = form.username.trim(),
                password = form.password
            )

            when (val authResult = xtreamClient.authenticate(credentials)) {
                is XtreamResult.Success -> Unit
                else -> {
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = xtreamErrorMessage(authResult)) }
                    return@launch
                }
            }

            val playlist = Playlist(
                name = suggestedXtreamPlaylistName(form),
                type = PlaylistType.XTREAM,
                xtreamServerUrl = credentials.serverUrl,
                xtreamUsername = credentials.username,
                xtreamPassword = credentials.password,
                includeTvChannels = form.includeTvChannels
            )

            when (val addResult = appRepository.playlists.addPlaylist(playlist)) {
                is AddPlaylistResult.LimitReached -> {
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = "Limite de 5 playlists atteinte") }
                }
                is AddPlaylistResult.Success -> {
                    // §4.2 : la case décide si les chaînes live sont importées ; VOD hors périmètre (§1).
                    if (form.includeTvChannels) {
                        importXtreamChannels(credentials, addResult.playlist)
                    }
                    _uiState.update { it.copy(isSubmitting = false) }
                    onComplete()
                }
            }
        }
    }

    private suspend fun importXtreamChannels(credentials: XtreamCredentials, playlist: Playlist) {
        when (val channelsResult = xtreamClient.fetchLiveChannels(credentials, playlist.id)) {
            is XtreamResult.Success -> {
                appRepository.channels.refreshChannels(playlist.id, channelsResult.data.channels)
                channelsResult.data.detectedEpgUrl?.let { epgUrl ->
                    appRepository.playlists.updatePlaylist(playlist.copy(autoDetectedEpgUrl = epgUrl))
                }
            }
            else -> {
                // La playlist reste enregistrée même si l'import des chaînes échoue ici
                // (ex. coupure réseau juste après l'authentification) : l'utilisateur
                // pourra la rafraîchir depuis Réglages → Playlists (§4.3, étape 6f)
                // plutôt que de perdre toute la saisie du formulaire.
            }
        }
    }

    /** Bouton "Suivant" du formulaire M3U (§4.2 Étape 2b). */
    fun submitM3u(onComplete: () -> Unit) {
        val form = _uiState.value.m3uForm
        if (form.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Le nom de la playlist est obligatoire") }
            return
        }
        if (form.url.isBlank() && form.localFileUri == null) {
            _uiState.update { it.copy(errorMessage = "Indiquez une URL ou importez un fichier local") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val playlistId = UUID.randomUUID().toString()
            val sourceResult = loadM3uSource(playlistId, form)
            val source = sourceResult.getOrElse { error ->
                _uiState.update { it.copy(isSubmitting = false, errorMessage = error.message ?: "Erreur inconnue") }
                return@launch
            }

            val parseResult = try {
                M3uParser.parse(source.rawContent, playlistId)
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(isSubmitting = false, errorMessage = e.message ?: "Fichier M3U invalide") }
                return@launch
            }

            val playlist = Playlist(
                id = playlistId,
                name = form.name.trim(),
                type = PlaylistType.M3U,
                m3uUrl = form.url.trim().takeIf { it.isNotBlank() },
                m3uLocalFilePath = source.localFilePath,
                autoDetectedEpgUrl = parseResult.detectedEpgUrl
            )

            when (val addResult = appRepository.playlists.addPlaylist(playlist)) {
                is AddPlaylistResult.LimitReached -> {
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = "Limite de 5 playlists atteinte") }
                }
                is AddPlaylistResult.Success -> {
                    appRepository.channels.refreshChannels(playlistId, parseResult.channels)
                    _uiState.update { it.copy(isSubmitting = false) }
                    onComplete()
                }
            }
        }
    }

    private data class M3uSource(val rawContent: String, val localFilePath: String?)

    private suspend fun loadM3uSource(playlistId: String, form: M3uFormState): Result<M3uSource> {
        val uri = form.localFileUri
        return if (uri != null) {
            copyLocalFileToPrivateStorage(playlistId, uri).map { (text, path) -> M3uSource(text, path) }
        } else {
            downloadM3u(form.url.trim()).map { text -> M3uSource(text, null) }
        }
    }

    private suspend fun downloadM3u(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("Le serveur a répondu avec le code ${response.code}"))
                } else {
                    Result.success(response.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            Result.failure(IOException(e.message ?: "Erreur réseau"))
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("URL de playlist invalide"))
        }
    }

    /**
     * Recopie le fichier sélectionné via le sélecteur système dans le stockage privé de
     * l'app (`filesDir/playlists/$playlistId.m3u`) : un `Uri` `OpenDocument` sans
     * permission persistante explicitement prise n'est pas garanti lisible après
     * redémarrage de l'app, alors que [Playlist.m3uLocalFilePath] doit rester
     * relisible indéfiniment (rafraîchissement manuel depuis Réglages, §5.2/étape 6f).
     */
    private suspend fun copyLocalFileToPrivateStorage(playlistId: String, uri: Uri): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val text = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return@withContext Result.failure(IOException("Impossible de lire le fichier sélectionné"))
                val dir = File(appContext.filesDir, "playlists").apply { mkdirs() }
                val target = File(dir, "$playlistId.m3u")
                target.writeText(text)
                Result.success(text to target.absolutePath)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * Aucun champ "Nom" dans le formulaire Xtream (§4.2 Étape 2a, contrairement au
     * formulaire M3U) alors que [Playlist.name] est obligatoire : dérivé de l'hôte du
     * serveur (repris tel quel si l'URL n'est pas analysable), affiché ensuite dans
     * Réglages → Playlists (§4.3) et modifiable depuis là si besoin.
     */
    private fun suggestedXtreamPlaylistName(form: XtreamFormState): String {
        val host = try {
            URI(form.serverUrl.trim()).host
        } catch (e: Exception) {
            null
        }
        return "Xtream – ${host ?: form.serverUrl.trim()}"
    }

    private fun xtreamErrorMessage(result: XtreamResult<*>): String = when (result) {
        is XtreamResult.Success -> "" // inatteignable ici, voir l'appel dans submitXtream
        is XtreamResult.InvalidCredentials -> "Identifiants incorrects"
        is XtreamResult.AccountInactive -> "Compte inactif (statut : ${result.status})"
        is XtreamResult.ServerError -> result.message
        is XtreamResult.NetworkError -> result.message
    }
}

/**
 * [OnboardingViewModel] a besoin d'[AppRepository] et d'un `Context` application, donc pas
 * du constructeur sans argument attendu par défaut par `viewModel()` — une factory manuelle
 * suffit ici, cohérente avec l'absence de Hilt/Koin déjà actée pour [com.dpflix.android.di.AppContainer]
 * (étape 6a).
 */
class OnboardingViewModelFactory(
    private val appRepository: AppRepository,
    context: Context
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return OnboardingViewModel(appRepository, appContext) as T
    }
}
