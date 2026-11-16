package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.util.AppLanguage

class JellyfinApplication : Application() {
	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		// The language chosen in the settings for the texts of the application context
		AppLanguage.applyToApplication(this)
		TelemetryService.init(this)
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		// The system gives its own language back on every change
		AppLanguage.applyToApplication(this)
	}
}
