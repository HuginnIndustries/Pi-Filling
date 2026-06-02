package industries.huginn.pifilling

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import industries.huginn.pifilling.runtime.AgentController
import industries.huginn.pifilling.ui.AppViewModel
import industries.huginn.pifilling.ui.PiFillingApp
import industries.huginn.pifilling.ui.theme.PiFillingTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private val agent: AgentController
        get() = (application as PiFillingApplication).agent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()

        setContent {
            PiFillingTheme {
                val vm: AppViewModel = viewModel(factory = AppViewModel.factory(agent))
                PiFillingApp(vm)
            }
        }
    }

    /**
     * Re-assert the daemon every time the activity is foregrounded. onCreate
     * alone is not enough: aggressive OEM battery managers kill the FGS while
     * the activity is backgrounded. startForegroundService is idempotent when
     * the service is already up. (Kai's MainActivity.onStart pattern.)
     */
    override fun onStart() {
        super.onStart()
        agent.reassertDaemonIfActive(this)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
