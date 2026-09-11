package ir.meelano.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import ir.meelano.vpn.data.AppSettings
import ir.meelano.vpn.ui.HomeScreen
import ir.meelano.vpn.ui.VpnViewModel
import ir.meelano.vpn.ui.theme.AppThemeMode
import ir.meelano.vpn.ui.theme.MeelanoTheme

/**
 * One activity, one screen, a few sheets. Everything the user can do without leaving the app
 * (pick a node, change a setting, install an update) is a sheet over the same home surface:
 * page-to-page navigation is what made the old app feel like it "lost" the connection when you
 * came back.
 */
class MainActivity : ComponentActivity() {

    private val vm: VpnViewModel by viewModel()

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            vm.onConsentResult(r.resultCode == RESULT_OK)
        }
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermissionIfNeeded()
        handleShortcut(intent)

        setContent {
            // AppSettings.themeMode is a mutableStateOf -> this recomposes when the tile/settings change
            val mode = AppSettings.themeMode
            MeelanoTheme(
                dark = when (mode) {
                    AppThemeMode.LIGHT -> false
                    AppThemeMode.DARK -> true
                    else -> isSystemInDarkTheme()
                },
                reducedMotion = AppSettings.reducedMotion,
            ) {
                val consent by vm.consent.collectAsState()
                LaunchedEffect(consent) { consent?.let { vpnConsent.launch(it) } }
                HomeScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let(::handleShortcut)
    }

    /** shortcuts and deep links land here; a deep link NEVER auto-connects (import only, then the user taps) */
    private fun handleShortcut(i: Intent) {
        when (i.getStringExtra("ShortcutAction")) {
            "connect" -> vm.toggle()
            "servers" -> vm.requestOpenServers()
            else -> i.data?.scheme?.let { if (it in IMPORT_SCHEMES) vm.requestImport(i.dataString ?: "") }
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // the ongoing tunnel notification is what keeps the connection alive when the app is
            // closed, so this permission is not cosmetic — but it is asked for once, never nagged
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        val IMPORT_SCHEMES = setOf("vless", "vmess", "trojan", "ss", "hysteria2")
    }
}
