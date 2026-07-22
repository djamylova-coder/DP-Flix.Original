package com.dpflix.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dpflix.android.nav.DpFlixNavHost

/**
 * Point d'entrée MOBILE (téléphone / tablette, interaction tactile).
 *
 * Étape 6a : branchée sur le vrai graphe de navigation ([DpFlixNavHost]) — remplace le
 * banc de test ad hoc de l'étape 5a, désormais intégré au graphe lui-même (voir la doc de
 * [DpFlixNavHost] pour le détail de cette transition). Indépendant du point d'entrée TV
 * ([com.dpflix.android.tv.TvMainActivity]), qui garde sa propre UI (Compose for TV,
 * étape 7) — seule la couche de données ([DpFlixApplication.container]) est partagée
 * entre les deux points d'entrée.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appRepository = (application as DpFlixApplication).container.appRepository
        setContent {
            DpFlixNavHost(appRepository = appRepository)
        }
    }
}
