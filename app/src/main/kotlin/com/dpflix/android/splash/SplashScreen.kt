package com.dpflix.android.splash

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dpflix.android.R

/**
 * Écran de démarrage (§4.1 du cahier des charges) : lit `res/raw/splash.mp4`
 * (logo + son, ~3,6 s) en plein écran, sans aucun contrôle visible, puis
 * appelle [onSplashFinished] une seule fois à la fin de la vidéo.
 *
 * Commun aux deux points d'entrée (mobile et TV) — même vidéo, même
 * comportement, quel que soit l'appareil. L'onboarding / accueil réel
 * arrivent à une étape ultérieure (§7) : pour l'instant [onSplashFinished]
 * ramène simplement vers l'écran "Hello DP-Flix" existant de chaque point
 * d'entrée, le temps que ces écrans soient développés.
 */
@OptIn(UnstableApi::class)
@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.splash}")
            setMediaItem(MediaItem.fromUri(uri))
            volume = 1f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        // onSplashFinished ne doit être appelé qu'une seule fois, même si
        // onPlaybackStateChanged(STATE_ENDED) était déclenché plusieurs fois.
        var finished = false

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !finished) {
                    finished = true
                    onSplashFinished()
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    PlayerView(context).apply {
                        player = exoPlayer
                        useController = false
                        // Vidéo affichée en entier (logo centré), pas de recadrage.
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            )
        }
    }
}
