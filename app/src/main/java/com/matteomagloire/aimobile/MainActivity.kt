package com.matteomagloire.aimobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.matteomagloire.aimobile.ui.AppRoot
import com.matteomagloire.aimobile.ui.theme.AiMobileTheme
import com.matteomagloire.aimobile.update.UpdateCheckWorker

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AiMobileTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Vérifie à chaque retour dans l'app (exécuté dès que le réseau est là).
        UpdateCheckWorker.schedule(this)
    }
}
