package com.dpflix.android.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dpflix.android.epg.EpgGuideScreen
import com.dpflix.android.home.HomeScreen
import com.dpflix.android.model.Channel
import com.dpflix.android.onboarding.OnboardingScreen
import com.dpflix.android.player.PlayerScreen
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.settings.SettingsScreen
import com.dpflix.android.splash.SplashScreen
import kotlinx.coroutines.flow.first

/**
 * NavHost mobile (§7 étape 6a, complété en 6b/6c/6d). Cinq destinations ([DpFlixDestination],
 * désormais partagées avec [DpFlixTvNavHost] — voir sa doc, étape 7a).
 * [Onboarding][DpFlixDestination.Onboarding] (§4.2, étape 6b), [Home][DpFlixDestination.Home]
 * (§4.4, étape 6c) et [Settings][DpFlixDestination.Settings] (§5, coquille + section
 * Général réelles depuis 6d, sections restantes : 6e-6g) ont leur contenu réel.
 *
 * Aiguillage initial (§3 du cahier des charges, "pas de playlist → onboarding / playlist
 * existante → accueil") : après la fin du splash, on regarde une seule fois si une
 * playlist active existe déjà ([AppRepository.playlists] `.observeActive().first()`) pour
 * choisir la destination de démarrage réelle, puis on retire [DpFlixDestination.Splash]
 * de la pile (`popUpTo` + `inclusive = true`) pour que le bouton retour ne puisse jamais y
 * ramener l'utilisateur. Même mécanique à la sortie de l'onboarding (§4.2 terminé avec
 * succès → `popUpTo(Onboarding, inclusive = true)`) : impossible de revenir en arrière
 * vers l'onboarding une fois une playlist enregistrée.
 *
 * [DpFlixDestination.PlayerFullscreen] reçoit désormais toujours un vrai ID de chaîne
 * fourni par [HomeScreen] (§4.4) : le banc de test manuel de l'étape 5a (cas spécial
 * `channelId == "test"`, qui vivait ici depuis 6a/6b en attendant que l'accueil existe)
 * a disparu — l'écran résout la chaîne lui-même via `AppRepository.channels.getById`
 * (nouveau, 6c), cohérent avec le commentaire de `DpFlixDestination.PlayerFullscreen`
 * ("l'écran cible va chercher la chaîne correspondante lui-même").
 *
 * Consomme [appRepository] directement (pas de `ViewModel` propre au `NavHost` — chaque
 * écran gère désormais le sien si besoin, voir `OnboardingViewModel`/`HomeViewModel`).
 */
@Composable
fun DpFlixNavHost(
    appRepository: AppRepository,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = DpFlixDestination.Splash.route) {

        composable(DpFlixDestination.Splash.route) {
            SplashScreen(
                onSplashFinished = {
                    navController.navigate(POST_SPLASH_ROUTE) {
                        popUpTo(DpFlixDestination.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // Route intermédiaire invisible : le temps de lire `observeActive()` une fois
        // (lecture DataStore/Room asynchrone), avant de rediriger vers Onboarding ou
        // Home sans jamais laisser l'utilisateur revenir dessus (voir popUpTo ci-dessus
        // et ci-dessous). `applyDefaultPlaylistOnStartup` (existe depuis 4d, jamais
        // appelée avant ce branchement) active la playlist par défaut choisie en
        // Réglages → Général (§5.6, étape 6d) si aucune playlist n'est déjà active ; sans
        // cet appel, ce réglage serait mémorisé mais sans aucun effet.
        composable(POST_SPLASH_ROUTE) {
            LaunchedEffect(Unit) {
                appRepository.applyDefaultPlaylistOnStartup()
                val hasActivePlaylist = appRepository.playlists.observeActive().first() != null
                val destination = if (hasActivePlaylist) DpFlixDestination.Home.route else DpFlixDestination.Onboarding.route
                navController.navigate(destination) {
                    popUpTo(POST_SPLASH_ROUTE) { inclusive = true }
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }
        }

        composable(DpFlixDestination.Onboarding.route) {
            OnboardingScreen(
                appRepository = appRepository,
                onOnboardingComplete = {
                    navController.navigate(DpFlixDestination.Home.route) {
                        popUpTo(DpFlixDestination.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(DpFlixDestination.Home.route) {
            HomeScreen(
                appRepository = appRepository,
                onNavigateToSettings = { navController.navigate(DpFlixDestination.Settings.route) },
                onNavigateToEpgGuide = { navController.navigate(DpFlixDestination.EpgGuide.route) },
                onNavigateToPlayerFullscreen = { channelId ->
                    navController.navigate(DpFlixDestination.PlayerFullscreen.createRoute(channelId))
                }
            )
        }

        composable(DpFlixDestination.EpgGuide.route) {
            EpgGuideScreen(
                appRepository = appRepository,
                onBack = { navController.popBackStack() },
                onNavigateToPlayerFullscreen = { channelId ->
                    navController.navigate(DpFlixDestination.PlayerFullscreen.createRoute(channelId))
                }
            )
        }

        composable(DpFlixDestination.Settings.route) {
            SettingsScreen(
                appRepository = appRepository,
                onBack = { navController.popBackStack() },
                onResetComplete = {
                    navController.navigate(DpFlixDestination.Onboarding.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = DpFlixDestination.PlayerFullscreen.route,
            arguments = listOf(navArgument(DpFlixDestination.ARG_CHANNEL_ID) { type = NavType.StringType })
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString(DpFlixDestination.ARG_CHANNEL_ID).orEmpty()
            ResolvedChannelPlayer(
                appRepository = appRepository,
                channelId = channelId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private const val POST_SPLASH_ROUTE = "post_splash_routing"

/**
 * Résout [channelId] via `AppRepository.channels.getById` (nouveau, 6c) avant d'afficher
 * [PlayerScreen] — voir la doc de [DpFlixNavHost]. Un court indicateur de chargement le
 * temps de cette lecture Room ; si la chaîne n'existe plus (playlist rafraîchie/supprimée
 * entre-temps), un message simple avec retour plutôt qu'un écran blanc.
 */
@Composable
private fun ResolvedChannelPlayer(appRepository: AppRepository, channelId: String, onBack: () -> Unit) {
    var channel by remember(channelId) { mutableStateOf<Channel?>(null) }
    var notFound by remember(channelId) { mutableStateOf(false) }

    LaunchedEffect(channelId) {
        val resolved = appRepository.channels.getById(channelId)
        if (resolved == null) notFound = true else channel = resolved
    }

    val currentChannel = channel
    when {
        currentChannel != null -> PlayerScreen(channel = currentChannel, modifier = Modifier.fillMaxSize(), appRepository = appRepository)
        notFound -> PlaceholderScreen(
            title = "Chaîne introuvable",
            actions = listOf("Retour" to onBack)
        )
        else -> Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Écran générique pour les destinations pas encore développées (§7 étape 6a) : un titre
 * et une liste de boutons d'action. Ne sert plus qu'au cas "chaîne introuvable"
 * ci-dessus — Réglages a son propre équivalent interne (`ComingSoonSection`, dans
 * `SettingsScreen`) pour ses sections pas encore développées (6e-6g).
 */
@Composable
private fun PlaceholderScreen(title: String, actions: List<Pair<String, () -> Unit>>) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(text = title, color = Color.White)
            actions.forEach { (label, onClick) ->
                Button(onClick = onClick) {
                    Text(label)
                }
            }
        }
    }
}
