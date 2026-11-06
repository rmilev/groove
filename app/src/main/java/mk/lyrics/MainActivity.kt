package mk.lyrics

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var engine: Engine? = null
    private val stateFlow = MutableStateFlow(UiState())
    private var permission by mutableStateOf<Boolean?>(null)
    private var forward: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("lyrics", MODE_PRIVATE)
        setContent {
            val state by stateFlow.collectAsStateWithLifecycle()
            LyricsApp(
                state = state,
                permission = permission,
                initialOffsetMs = prefs.getLong("offsetMs", 0),
                onOffset = { prefs.edit().putLong("offsetMs", it).apply() },
                onResync = { engine?.resync() },
                onAskPermission = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permission = granted
        if (granted) begin() else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun begin() {
        if (engine != null) return
        // Test hook (adb only): am start -n mk.lyrics/.MainActivity --es testPcm <name in the app's files dir>
        val test = intent.getStringExtra("testPcm")?.let { java.io.File(getExternalFilesDir(null), it) }?.takeIf { it.exists() }
        val e = Engine(test).also { engine = it }
        e.start()
        forward = main.launch { e.state.collect { stateFlow.value = it } }
    }

    private val main = MainScope()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        permission = granted
        if (granted) begin()
    }

    override fun onStop() {
        super.onStop()
        // Only listen while on screen
        forward?.cancel(); forward = null
        engine?.stop(); engine = null
    }
}
