package com.dpflix.android.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dpflix.android.splash.SplashScreen

/**
 * Point d'entrée TV (boîtier Android TV, télécommande / D-pad).
 *
 * Étape 2c-2 : la vidéo de splash (§4.1) se lance en premier, identique à
 * celle du point d'entrée mobile ; une fois terminée, on retombe sur l'écran
 * "Hello DP-Flix" TV existant (validé à l'étape 2b — focus D-pad de base),
 * en attendant que l'accueil réel soit développé (§7 du cahier des charges).
 * Indépendant du point d'entrée mobile ([com.dpflix.android.MainActivity]).
 */
class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var splashFinished by remember { mutableStateOf(false) }

            if (splashFinished) {
                HelloDpFlixTv()
            } else {
                SplashScreen(onSplashFinished = { splashFinished = true })
            }
        }
    }
}

@Composable
private fun HelloDpFlixTv() {
    val firstItemFocusRequester = remember { FocusRequester() }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Hello DP-Flix — TV")

            // Deux éléments focusables empilés : valide que le D-pad haut/bas
            // déplace bien le focus entre eux (navigation de base Compose for TV,
            // avec l'indication visuelle de focus fournie nativement par tv-material).
            Button(
                onClick = { /* pas d'action à cette étape */ },
                modifier = Modifier.focusRequester(firstItemFocusRequester)
            ) {
                Text("Chaîne test 1")
            }
            Button(
                onClick = { /* pas d'action à cette étape */ }
            ) {
                Text("Chaîne test 2")
            }
        }

        // Focus initial explicite : sur Android TV, rien n'est focus par défaut
        // tant qu'on ne le demande pas — sans ça le D-pad ne réagirait pas.
        LaunchedEffect(Unit) {
            firstItemFocusRequester.requestFocus()
        }
    }
}
