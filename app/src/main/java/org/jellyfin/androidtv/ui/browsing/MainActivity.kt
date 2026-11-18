package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.util.AppLanguage
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.music.MusicStopper
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideLocalInteractionTracker
import org.jellyfin.androidtv.ui.composable.compat.AppNavigationHost
import org.jellyfin.androidtv.ui.home.modern.BackgroundTraffic
import org.jellyfin.androidtv.ui.home.modern.ImdbRatings
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.modern.ModernPlayerOverlay
import org.jellyfin.androidtv.ui.screensaver.InAppScreensaver
import org.jellyfin.androidtv.ui.settings.compat.MainActivitySettings
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.applyTheme
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.RefreshProgressMessage
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

class MainActivity : FragmentActivity() {
	// The language chosen in the settings for the texts of this screen
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(AppLanguage.wrap(newBase))
	}

	private val navigationRepository by inject<NavigationRepository>()
	private val sessionRepository by inject<SessionRepository>()
	private val userRepository by inject<UserRepository>()
	private val interactionTrackerViewModel by viewModel<InteractionTrackerViewModel>()
	private val workManager by inject<WorkManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val musicStopper by inject<MusicStopper>()
	private val api by inject<ApiClient>()
	private val dataRefreshService by inject<DataRefreshService>()

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()

		super.onCreate(savedInstanceState)

		if (!validateAuthentication()) return

		// Also while music plays: with nobody touching the remote (a shared queue party) the Fire TV
		// went to sleep after a while and the music stopped
		combine(interactionTrackerViewModel.keepScreenOn, playbackManager.state.playState) { keepScreenOn, playState ->
			keepScreenOn || playState == PlayState.PLAYING
		}.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { keepScreenOn ->
				if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
				else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			}.launchIn(lifecycleScope)

		if (savedInstanceState == null && navigationRepository.canGoBack) navigationRepository.reset(clearHistory = true)

		// IMDb ratings: what the device has at once, a new copy in the background when a week old
		if (!imdbRatingsStarted) {
			imdbRatingsStarted = true
			val appContext = applicationContext
			ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
				ImdbRatings.load(appContext)
				delay(IMDB_REFRESH_DELAY_MS)
				BackgroundTraffic.awaitTurn()
				ImdbRatings.refreshIfOld(appContext)
			}
		}

		// New, changed or removed titles on the server: the pages on screen load again. LibraryChanged
		// comes once the server has waited for more changes (30 s by default); the end of a library
		// refresh (sent to administrators) comes earlier
		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach { dataRefreshService.onLibraryChanged() }
					.launchIn(this)
				api.webSocket.subscribe<RefreshProgressMessage>()
					.filter { message -> (message.data?.get("Progress")?.toDoubleOrNull() ?: 0.0) >= REFRESH_DONE }
					.onEach { dataRefreshService.onLibraryChanged() }
					.launchIn(this)
			}
		}

		navigationRepository.currentAction
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach {
				interactionTrackerViewModel.notifyInteraction(canCancel = false, userInitiated = false)
			}.launchIn(lifecycleScope)

		setContent {
			JellyfinTheme {
				ProvideLocalInteractionTracker(
					interactionTracker = { interactionTrackerViewModel.notifyInteraction(false, userInitiated = true) }
				) {
					AppBackground()
					AppNavigationHost(
						navigationRepository = navigationRepository,
					)
					InAppScreensaver()
					MainActivitySettings()
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()

		if (!validateAuthentication()) return

		applyTheme()

		interactionTrackerViewModel.activityPaused = false
	}

	private fun validateAuthentication(): Boolean {
		if (sessionRepository.currentSession.value == null || userRepository.currentUser.value == null) {
			Timber.w("Activity ${this::class.qualifiedName} started without a session, bouncing to StartupActivity")
			startActivity(Intent(this, StartupActivity::class.java))
			finish()
			return false
		}

		return true
	}

	override fun onPause() {
		super.onPause()

		interactionTrackerViewModel.activityPaused = true
	}

	override fun onStop() {
		super.onStop()

		workManager.enqueue(OneTimeWorkRequestBuilder<LeanbackChannelWorker>().build())

		lifecycleScope.launch(Dispatchers.IO) {
			Timber.i("MainActivity stopped")
			sessionRepository.restoreSession(destroyOnly = true)
		}
	}

	// ☰ of the remote while there is music: stops it, whatever the screen (before the screens get it)
	override fun dispatchKeyEvent(event: KeyEvent): Boolean =
		// In the player ☰ shows the stream info (ModernPlayerScreen)
		(!ModernPlayerOverlay.isShowing && musicStopper.handleMenuKey(event)) || super.dispatchKeyEvent(event)

	// Forward key events to fragments

	private fun Fragment.onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		var result = childFragmentManager.fragments.any { it.onKeyEvent(keyCode, event) }
		if (!result && this is View.OnKeyListener) result = onKey(currentFocus, keyCode, event)
		return result
	}

	private fun onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean = supportFragmentManager.fragments
		.any { it.onKeyEvent(keyCode, event) }

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyDown(keyCode, event)

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)

	override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)
}

/** Progress of a library refresh that means it is done */
private const val REFRESH_DONE = 100.0

/** The download of the IMDb ratings waits for the app to settle after starting */
private const val IMDB_REFRESH_DELAY_MS = 60_000L

/** The IMDb ratings are loaded once per app process (activities can be created again) */
private var imdbRatingsStarted = false
