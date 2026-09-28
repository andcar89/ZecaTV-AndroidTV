package com.ze.iptvplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.ze.iptvplayer.data.Prefs

/**
 * The app's actual launcher activity. If a playlist source is already configured, this
 * immediately forwards to MainActivity (returning users go straight to their channels,
 * unchanged from before) — this screen only shows for a fresh install / no source yet.
 */
class StartActivity : AppCompatActivity() {

    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Prefs.activePlaylist(this) != null) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_start)
        findViewById<View>(R.id.start_add_playlist_button).setOnClickListener {
            startActivity(Intent(this, AddSourceActivity::class.java))
        }
        findViewById<View>(R.id.start_settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user added a playlist via one of the buttons above and came back, proceed.
        // Guarded by `forwarded` since onCreate() may have already forwarded us once, and
        // onResume() always runs after onCreate() regardless of an earlier finish() call.
        if (!forwarded && Prefs.activePlaylist(this) != null) {
            goToMain()
        }
    }

    private fun goToMain() {
        forwarded = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
