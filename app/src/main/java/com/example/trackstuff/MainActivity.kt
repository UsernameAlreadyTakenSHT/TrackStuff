package com.example.trackstuff

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.trackstuff.ui.navigation.AppNavigation
import com.example.trackstuff.ui.theme.TrackStuffTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackStuffTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** A page URL tapped (VIEW) or shared (SEND, first URL in the text) opens the matching title. */
    private fun handleLink(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { URL_IN_TEXT.find(it)?.value }
            else -> null
        } ?: return
        (application as TrackStuffApp).container.sharedLink.value = url
    }

    override fun onStart() {
        super.onStart()
        // Automatic sync when returning to the foreground (at most once every 15 minutes).
        (application as TrackStuffApp).container.sync.apply { inForeground = true; onForeground() }
    }

    override fun onStop() {
        (application as TrackStuffApp).container.sync.inForeground = false
        super.onStop()
    }

    private companion object {
        val URL_IN_TEXT = Regex("""https?://\S+""")
    }
}
