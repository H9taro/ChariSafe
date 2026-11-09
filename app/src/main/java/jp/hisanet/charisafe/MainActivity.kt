package jp.hisanet.charisafe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import jp.hisanet.charisafe.ui.main.MainScreen
import jp.hisanet.charisafe.ui.main.MainViewModel
import jp.hisanet.charisafe.ui.theme.ChariSafeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            val denied = result
                .filterValues { granted -> !granted }
                .keys

            if (denied.isNotEmpty()) {
                android.util.Log.w(
                    "Permission",
                    "Denied permissions = $denied"
                )
            } else {
                android.util.Log.i(
                    "Permission",
                    "All permissions granted"
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        requestPermissionsIfNeeded()

        handleIntent(intent)

        setContent {
            ChariSafeTheme {
                MainScreen(
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val targetEventId = intent?.getStringExtra("EXTRA_TARGET_EVENT_ID")
        if (!targetEventId.isNullOrEmpty()) {
            viewModel.setTargetEventIdToHighlight(targetEventId)
        }
    }

    private fun requestPermissionsIfNeeded() {

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        permissions.add(
            Manifest.permission.POST_NOTIFICATIONS
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(
                missingPermissions.toTypedArray()
            )
        }
    }
}
