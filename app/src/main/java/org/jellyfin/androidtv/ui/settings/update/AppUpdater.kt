package org.jellyfin.androidtv.ui.settings.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.BuildConfig
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates Jellystick from the latest release of its GitHub repository: looks for a newer version,
 * downloads its APK and opens the Android installer, which installs it over this one (the same
 * signing key is needed, the one the release APKs are made with).
 */
class AppUpdater(private val context: Context) {
	sealed interface State {
		data object Idle : State
		data object Checking : State
		data class UpToDate(val version: String) : State
		data class Available(val release: Release) : State
		data class Downloading(val release: Release, val progress: Float?) : State
		data class Ready(val release: Release, val file: File) : State

		/** Android needs the permission to install apps for Jellystick */
		data class NeedsPermission(val release: Release, val file: File) : State
		data class Failed(val reason: Reason, val detail: String? = null) : State
	}

	enum class Reason { CHECK, NO_APK, DOWNLOAD, INSTALL }

	data class Release(
		val version: String,
		val apkUrl: String,
		val size: Long,
	)

	@Serializable
	private data class GithubRelease(
		@SerialName("tag_name") val tagName: String,
		val name: String? = null,
		val draft: Boolean = false,
		val prerelease: Boolean = false,
		val assets: List<GithubAsset> = emptyList(),
	)

	@Serializable
	private data class GithubAsset(
		val name: String,
		@SerialName("browser_download_url") val downloadUrl: String,
		val size: Long = 0,
	)

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private val json = Json { ignoreUnknownKeys = true }
	private val _state = MutableStateFlow<State>(State.Idle)
	val state: StateFlow<State> = _state.asStateFlow()
	private var job: Job? = null

	val currentVersion: String get() = BuildConfig.VERSION_NAME

	/** What a press of the button does, depending on the state */
	fun onClick() {
		when (val state = _state.value) {
			State.Checking, is State.Downloading -> Unit
			is State.Available -> download(state.release)
			is State.Ready -> install(state.release, state.file)
			is State.NeedsPermission -> install(state.release, state.file)
			else -> check()
		}
	}

	private fun check() {
		job?.cancel()
		job = scope.launch {
			_state.value = State.Checking
			_state.value = try {
				val release = latestRelease()
				when {
					release == null -> State.Failed(Reason.NO_APK)
					isNewer(release.version, currentVersion) -> State.Available(release)
					else -> State.UpToDate(currentVersion)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to check for updates")
				State.Failed(Reason.CHECK, err.message)
			}
		}
	}

	private suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
		val connection = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest").apply {
			setRequestProperty("Accept", "application/vnd.github+json")
		}
		val body = try {
			if (connection.responseCode != HttpURLConnection.HTTP_OK) error("GitHub: HTTP ${connection.responseCode}")
			connection.inputStream.bufferedReader().use { it.readText() }
		} finally {
			connection.disconnect()
		}

		val release = json.decodeFromString<GithubRelease>(body)
		// The APK of the release ("jellystick.apk", "jellystick-v14.6.apk"...)
		val apk = release.assets
			.filter { it.name.endsWith(".apk", ignoreCase = true) }
			.sortedByDescending { it.name.contains("jellystick", ignoreCase = true) }
			.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
			?: return@withContext null
		Release(
			version = release.tagName.trim().removePrefix("v").removePrefix("V"),
			apkUrl = apk.downloadUrl,
			size = apk.size,
		)
	}

	private fun download(release: Release) {
		job?.cancel()
		job = scope.launch {
			_state.value = State.Downloading(release, null)
			try {
				val file = withContext(Dispatchers.IO) { downloadApk(release) }
				_state.value = State.Ready(release, file)
				install(release, file)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to download the update")
				_state.value = State.Failed(Reason.DOWNLOAD, err.message)
			}
		}
	}

	private fun downloadApk(release: Release): File {
		val folder = File(context.cacheDir, UPDATES_FOLDER).apply { mkdirs() }
		// Only the APK being downloaded is kept
		folder.listFiles()?.forEach { it.delete() }
		val file = File(folder, "jellystick-${release.version}.apk")
		val partial = File(folder, "${file.name}.part")

		val connection = open(release.apkUrl)
		try {
			if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
			val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
			connection.inputStream.use { input ->
				partial.outputStream().use { output ->
					val buffer = ByteArray(BUFFER_SIZE)
					var done = 0L
					var lastShown = 0f
					while (true) {
						val read = input.read(buffer)
						if (read < 0) break
						output.write(buffer, 0, read)
						done += read
						if (total > 0) {
							val progress = (done.toFloat() / total).coerceIn(0f, 1f)
							// Not on every block: a few updates of the text are enough
							if (progress - lastShown >= 0.01f || progress >= 1f) {
								lastShown = progress
								_state.value = State.Downloading(release, progress)
							}
						}
					}
				}
			}
		} finally {
			connection.disconnect()
		}
		if (!partial.renameTo(file)) error("Unable to save the APK")
		return file
	}

	private fun install(release: Release, file: File) {
		if (!file.exists()) {
			download(release)
			return
		}

		// Android 8+: each app needs the permission to install apps
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
			_state.value = State.NeedsPermission(release, file)
			try {
				context.startActivity(
					Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				)
			} catch (err: ActivityNotFoundException) {
				// Fire TV: Settings → My Fire TV → Developer options → Install unknown apps (the text says it)
				Timber.w(err, "No screen for the install permission")
			}
			return
		}

		try {
			val uri = FileProvider.getUriForFile(context, "${context.packageName}$PROVIDER_SUFFIX", file)
			context.startActivity(
				Intent(Intent.ACTION_VIEW)
					.setDataAndType(uri, APK_MIME_TYPE)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
			)
			_state.value = State.Ready(release, file)
		} catch (err: Exception) {
			Timber.w(err, "Unable to open the installer")
			_state.value = State.Failed(Reason.INSTALL, err.message)
		}
	}

	private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
		connectTimeout = TIMEOUT_MS
		readTimeout = TIMEOUT_MS
		instanceFollowRedirects = true
		setRequestProperty("User-Agent", "Jellystick/${BuildConfig.VERSION_NAME}")
	}

	companion object {
		private const val UPDATES_FOLDER = "updates"
		const val PROVIDER_SUFFIX = ".updates"
		private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
		private const val BUFFER_SIZE = 64 * 1024
		private const val TIMEOUT_MS = 20_000

		/** "14.6" is newer than "13.2.0"; "14.6.1" than "14.6" */
		fun isNewer(candidate: String, current: String): Boolean {
			fun parts(version: String) = Regex("\\d+").findAll(version.substringBefore('-').substringBefore('+'))
				.map { it.value.toIntOrNull() ?: 0 }
				.toList()

			val a = parts(candidate)
			val b = parts(current)
			for (i in 0 until maxOf(a.size, b.size)) {
				val x = a.getOrElse(i) { 0 }
				val y = b.getOrElse(i) { 0 }
				if (x != y) return x > y
			}
			return false
		}
	}
}
