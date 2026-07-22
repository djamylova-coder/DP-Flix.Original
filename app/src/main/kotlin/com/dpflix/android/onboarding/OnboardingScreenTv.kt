package com.dpflix.android.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.model.PlaylistType
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.ui.theme.DpFlixColors

/**
 * Onboarding TV (§4.2, §7 étape 7b) — équivalent TV de [OnboardingScreen] (mobile, 6b) :
 * **mêmes trois étapes, même [OnboardingViewModel]/[OnboardingUiState] réutilisés tels
 * quels** (aucune logique métier propre à cette sous-étape, purement une nouvelle UI —
 * voir la doc de [OnboardingViewModel], déjà écrite pour être indépendante de la
 * plateforme). Seul le rendu change.
 *
 * ## Disposition : le mockup TV fourni, repris littéralement cette fois
 * [OnboardingScreen] (mobile) documentait explicitement avoir adapté la disposition
 * "icône/titre à gauche, formulaire au centre, actions à droite" du mockup fourni
 * (§4.2) pour l'écran mobile étroit, **en reportant la disposition à trois colonnes
 * littérale à cette étape 7**. C'est ce que fait [OnboardingScaffoldTv] ci-dessous :
 * titre/sous-titre/erreur à gauche, contenu du formulaire au centre, actions
 * (Suivant/Précédent) à droite. [ChooseTypeStepTv] n'a pas de colonne d'actions (la
 * sélection navigue directement au clic, comme sur mobile) mais garde le même principe
 * gauche/centre.
 *
 * Pas de bouton "Annuler" comme sur le mockup fourni (capture d'écran §7a/2c-1) : ce
 * mockup illustre l'écran "Type de liste de lecture" tel que réutilisé plus tard pour
 * *ajouter* une playlist depuis Réglages (§4.3/6f, "Portail Stalker" y compris — hors
 * périmètre ici, comme sur mobile). Le tout premier onboarding (aucune playlist encore
 * enregistrée) n'a rien vers quoi annuler — même choix que [OnboardingScreen] mobile,
 * qui n'a pas non plus ce bouton à ce stade.
 *
 * ## Pas de `Checkbox`/`Icon` `tv-material3`
 * La version de `androidx.tv.material3` utilisée par le projet (voir
 * `gradle/libs.versions.toml`) n'expose ni case à cocher ni composant d'icône — la case
 * "Inclure les chaînes de télévision" (§4.2) est donc un simple [Button] dont le texte
 * bascule ☑/☐ au clic ([IncludeTvChannelsToggle] plus bas), plutôt qu'un vrai composant
 * de case à cocher. Le champ de saisie de texte pose le même problème (`tv-material3` ne
 * fournit pas de `TextField`) — voir la note sur [TvTextField] ci-dessous.
 *
 * ## `OutlinedTextField` Material3 réutilisé tel quel pour la saisie de texte
 * Faute de composant de saisie dans `tv-material3`, les champs (adresse serveur,
 * identifiants, nom/URL de playlist) restent des `androidx.compose.material3.OutlinedTextField`
 * — comme [OnboardingScreen] mobile. Ce composant reste focusable et déclenche le
 * clavier virtuel système au focus/validation D-pad (même mécanisme que la saisie de
 * texte sur les téléviseurs du commerce quand la télécommande n'a pas de clavier
 * physique) : fonctionnel, mais pas la lecture "10 pieds" optimisée d'un vrai composant
 * `tv-material3` — amélioration visuelle possible plus tard, pas bloquante ici.
 */
@Composable
fun OnboardingScreenTv(
    appRepository: AppRepository,
    onOnboardingComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: OnboardingViewModel = viewModel(
        factory = remember { OnboardingViewModelFactory(appRepository, context) }
    )
    val uiState by viewModel.uiState.collectAsState()

    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(DpFlixColors.Background)
        ) {
            when (uiState.step) {
                OnboardingStep.ChooseType -> ChooseTypeStepTv(onSelect = viewModel::selectType)

                OnboardingStep.XtreamForm -> XtreamFormStepTv(
                    state = uiState,
                    onFormChange = viewModel::updateXtreamForm,
                    onBack = viewModel::backToChooseType,
                    onSubmit = { viewModel.submitXtream(onOnboardingComplete) }
                )

                OnboardingStep.M3uForm -> M3uFormStepTv(
                    state = uiState,
                    onFormChange = viewModel::updateM3uForm,
                    onBack = viewModel::backToChooseType,
                    onSubmit = { viewModel.submitM3u(onOnboardingComplete) }
                )
            }
        }
    }
}

/** Étape 1 — Choix du type (§4.2). Portail Stalker non repris (hors périmètre, comme mobile). */
@Composable
private fun ChooseTypeStepTv(onSelect: (PlaylistType) -> Unit) {
    val firstChoiceFocusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            Text(text = "Ajouter une playlist", fontSize = 32.sp, color = DpFlixColors.OnBackground)
            Text(
                text = "Choisissez comment DP-Flix doit récupérer vos chaînes.",
                fontSize = 18.sp,
                color = DpFlixColors.OnBackgroundMuted
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Button(
                onClick = { onSelect(PlaylistType.M3U) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstChoiceFocusRequester)
            ) {
                Text("Liste de lecture M3U")
            }
            Button(
                onClick = { onSelect(PlaylistType.XTREAM) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Xtream Codes")
            }
        }
    }

    LaunchedEffect(Unit) {
        firstChoiceFocusRequester.requestFocus()
    }
}

/** Étape 2a — Formulaire Xtream Codes (§4.2). */
@Composable
private fun XtreamFormStepTv(
    state: OnboardingUiState,
    onFormChange: ((XtreamFormState) -> XtreamFormState) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.xtreamForm
    val firstFieldFocusRequester = remember { FocusRequester() }

    OnboardingScaffoldTv(
        title = "Xtream Code",
        subtitle = null,
        errorMessage = state.errorMessage,
        actions = { OnboardingActionsTv(isSubmitting = state.isSubmitting, onBack = onBack, onSubmit = onSubmit) }
    ) {
        TvTextField(
            value = form.serverUrl,
            onValueChange = { value -> onFormChange { it.copy(serverUrl = value) } },
            label = "Adresse du serveur",
            focusRequester = firstFieldFocusRequester
        )
        TvTextField(
            value = form.username,
            onValueChange = { value -> onFormChange { it.copy(username = value) } },
            label = "Nom d'utilisateur"
        )
        TvTextField(
            value = form.password,
            onValueChange = { value -> onFormChange { it.copy(password = value) } },
            label = "Mot de passe",
            visualTransformation = PasswordVisualTransformation()
        )
        IncludeTvChannelsToggle(
            checked = form.includeTvChannels,
            onToggle = { checked -> onFormChange { it.copy(includeTvChannels = checked) } }
        )
    }

    LaunchedEffect(Unit) {
        firstFieldFocusRequester.requestFocus()
    }
}

/** Étape 2b — Formulaire M3U (§4.2). */
@Composable
private fun M3uFormStepTv(
    state: OnboardingUiState,
    onFormChange: ((M3uFormState) -> M3uFormState) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit
) {
    val form = state.m3uForm
    val firstFieldFocusRequester = remember { FocusRequester() }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onFormChange { it.copy(localFileUri = uri, localFileName = uri.lastPathSegment) }
        }
    }

    OnboardingScaffoldTv(
        title = "Liste de lecture M3U",
        subtitle = null,
        errorMessage = state.errorMessage,
        actions = { OnboardingActionsTv(isSubmitting = state.isSubmitting, onBack = onBack, onSubmit = onSubmit) }
    ) {
        TvTextField(
            value = form.name,
            onValueChange = { value -> onFormChange { it.copy(name = value) } },
            label = "Nom",
            focusRequester = firstFieldFocusRequester
        )
        TvTextField(
            value = form.url,
            onValueChange = { value ->
                // Une URL saisie et un fichier importé sont mutuellement exclusifs (§4.2 : "en alternative").
                onFormChange { it.copy(url = value, localFileUri = null, localFileName = null) }
            },
            label = "URL de la playlist"
        )
        Text("— ou —", color = DpFlixColors.OnBackgroundMuted)
        Button(
            onClick = {
                filePickerLauncher.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "application/octet-stream", "*/*"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(form.localFileName?.let { "Fichier : $it" } ?: "Importer un fichier local (.m3u / .m3u8)")
        }
    }

    LaunchedEffect(Unit) {
        firstFieldFocusRequester.requestFocus()
    }
}

/**
 * Squelette commun aux deux formulaires TV : trois colonnes (titre/erreur à gauche,
 * contenu au centre, actions à droite) — voir la doc de [OnboardingScreenTv] sur ce
 * choix. [ChooseTypeStepTv] n'utilise pas ce scaffold (pas de colonne d'actions,
 * disposition à deux colonnes seulement).
 */
@Composable
private fun OnboardingScaffoldTv(
    title: String,
    subtitle: String?,
    errorMessage: String?,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        Column(modifier = Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, fontSize = 28.sp, color = DpFlixColors.OnBackground)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 16.sp, color = DpFlixColors.OnBackgroundMuted)
            }
            if (errorMessage != null) {
                Text(text = errorMessage, fontSize = 16.sp, color = DpFlixColors.Red)
            }
        }
        Column(
            modifier = Modifier
                .weight(1.3f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
        Column(
            modifier = Modifier.weight(0.7f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            actions()
        }
    }
}

/** Boutons "Suivant" / "Précédent" (§4.2), empilés verticalement (colonne de droite du scaffold). */
@Composable
private fun OnboardingActionsTv(isSubmitting: Boolean, onBack: () -> Unit, onSubmit: () -> Unit) {
    Button(onClick = onSubmit, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
        if (isSubmitting) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text("Suivant")
        }
    }
    Button(onClick = onBack, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
        Text("Précédent")
    }
}

/**
 * Bascule ☑/☐ pour "Inclure les chaînes de télévision" (§4.2 Étape 2a) — voir la doc de
 * [OnboardingScreenTv] sur l'absence de `Checkbox` dans `tv-material3` à ce stade.
 */
@Composable
private fun IncludeTvChannelsToggle(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Button(onClick = { onToggle(!checked) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (checked) "☑ Inclure les chaînes de télévision" else "☐ Inclure les chaînes de télévision")
    }
}

/**
 * Champ de saisie de texte — voir la doc de [OnboardingScreenTv] sur la réutilisation
 * d'`OutlinedTextField` Material3 faute de composant `tv-material3` équivalent. Couleurs
 * de marque appliquées explicitement (comme la version mobile, `DpFlixTextField`) : pas
 * besoin d'un `androidx.compose.material3.MaterialTheme` ambiant pour ça.
 */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = DpFlixColors.OnBackground,
            unfocusedTextColor = DpFlixColors.OnBackground,
            focusedBorderColor = DpFlixColors.Red,
            cursorColor = DpFlixColors.Red,
            focusedLabelColor = DpFlixColors.Red,
            unfocusedLabelColor = DpFlixColors.OnBackgroundMuted
        )
    )
}
