package com.dpflix.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dpflix.android.splash.SplashScreen

/**
 * Point d'entrée MOBILE (téléphone / tablette, interaction tactile).
 *
 * Étape 2c-2 : la vidéo de splash (§4.1) se lance en premier ; une fois
 * terminée, on retombe sur l'écran "Hello DP-Flix" existant (validé à
 * l'étape 2b), en attendant que l'onboarding/accueil réel soit développé
 * (§7 du cahier des charges). Indépendant du point d'entrée TV
 * ([com.dpflix.android.tv.TvMainActivity]), qui a son propre enchaînement.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var splashFinished by remember { mutableStateOf(false) }

            if (splashFinished) {
                HelloDpFlixMobile()
            } else {
                SplashScreen(onSplashFinished = { splashFinished = true })
            }
        }
    }
}

@Composable
private fun HelloDpFlixMobile() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Hello DP-Flix — mobile",
                    color = Color.White
                )
            }
        }
    }
}
