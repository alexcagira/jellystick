import java.util.Properties

plugins {
	alias(libs.plugins.aboutlibraries)
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
}

// Values from local.properties (never committed), then Gradle properties and environment variables
val localProperties = Properties().apply {
	val localPropertiesFile = rootProject.file("local.properties")
	if (localPropertiesFile.exists()) localPropertiesFile.inputStream().use { load(it) }
}
fun localProperty(name: String): String? = localProperties.getProperty(name)?.trim() ?: getProperty(name)

// TMDB key for the "Trending this week" row of the modern home screen.
// Add `tmdb.api.key=YOUR_KEY` to local.properties (never committed) or set the TMDB_API_KEY environment variable.
val tmdbApiKey: String = (localProperty("tmdb.api.key") ?: "").trim().replace("\"", "")

// Jellystick: its own application id, so the release APK installs next to the official Jellyfin app
// (same id = "App not installed"). The debug build keeps the old id and with it its login and settings.
val jellystickApplicationId = "com.jellystick.tv"
val debugApplicationId = "org.jellyfin.androidtv.debug"

// Version shown in Settings → About. Raise it before every APK you publish: Android only installs an
// update over the old one when its version code (made from this) is higher
val jellystickVersion: String = getProperty("jellystick.version") ?: project.getVersionName()

// Settings → Update Jellystick: the latest release of this GitHub repository (owner/name)
val updateRepository: String = (localProperty("jellystick.updateRepo") ?: "alexcagira/jellystick").trim().replace("\"", "")

android {
	namespace = "org.jellyfin.androidtv"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()

		// Release version
		applicationId = jellystickApplicationId
		versionName = jellystickVersion
		versionCode = getVersionCode(versionName!!)

		buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
		// GitHub repository whose latest release Settings → Update installs
		buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
		compose = true
		resValues = true
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	signingConfigs {
		// Release signing, from local.properties (see docs/MODERN_HOME.md, "Publicar el APK")
		val keystoreFile = localProperty("keystore.file")
		val keystorePassword = localProperty("keystore.password")
		val signingKeyAlias = localProperty("signing.key.alias")
		val signingKeyPassword = localProperty("signing.key.password")

		if (keystoreFile != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
			create("release") {
				storeFile = file(keystoreFile)
				storePassword = keystorePassword
				keyAlias = signingKeyAlias
				keyPassword = signingKeyPassword
			}
		}
	}

	dependenciesInfo {
		includeInBundle = false
		includeInApk = false
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

			// Set package names used in various XML files
			resValue("string", "app_id", jellystickApplicationId)
			resValue("string", "app_search_suggest_authority", "$jellystickApplicationId.content")
			resValue("string", "app_search_suggest_intent_data", "content://$jellystickApplicationId.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_release")

			buildConfigField("boolean", "DEVELOPMENT", "false")

			signingConfig = signingConfigs.findByName("release")
		}

		debug {
			// Different application id (set below) to run release and debug at the same time

			// Set package names used in various XML files
			resValue("string", "app_id", debugApplicationId)
			resValue("string", "app_search_suggest_authority", "$debugApplicationId.content")
			resValue("string", "app_search_suggest_intent_data", "content://$debugApplicationId.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_debug")

			buildConfigField("boolean", "DEVELOPMENT", "true")
		}
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
		checkDependencies = true
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

androidComponents {
	onVariants(selector().withBuildType("debug")) { variant ->
		variant.applicationId.set(debugApplicationId)
	}
}

// Output: jellystick-v13.2.0-release.apk
base.archivesName.set("jellystick-v$jellystickVersion")

tasks.register("versionTxt") {
	val path = layout.buildDirectory.asFile.get().resolve("version.txt")

	doLast {
		val versionString = "v${android.defaultConfig.versionName}=${android.defaultConfig.versionCode}"
		logger.info("Writing [$versionString] to $path")
		path.writeText("$versionString\n")
	}
}

dependencies {
	// Jellyfin
	implementation(projects.design)
	implementation(projects.playback.core)
	implementation(projects.playback.jellyfin)
	implementation(projects.playback.media3.exoplayer)
	implementation(projects.playback.media3.session)
	implementation(projects.preference)
	implementation(libs.jellyfin.sdk) {
		// Change version if desired
		val sdkVersion = findProperty("sdk.version")?.toString()
		when (sdkVersion) {
			"local" -> version { strictly("latest-SNAPSHOT") }
			"snapshot" -> version { strictly("master-SNAPSHOT") }
			"unstable-snapshot" -> version { strictly("openapi-unstable-SNAPSHOT") }
		}
	}

	// Kotlin
	implementation(libs.kotlinx.coroutines)
	implementation(libs.kotlinx.serialization.json)

	// Android(x)
	implementation(libs.androidx.core)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.fragment.compose)
	implementation(libs.androidx.leanback.core)
	implementation(libs.androidx.leanback.preference)
	implementation(libs.androidx.navigation3.ui)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.tvprovider)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.work.runtime)
	implementation(libs.bundles.androidx.lifecycle)
	implementation(libs.androidx.window)
	implementation(libs.androidx.cardview)
	implementation(libs.androidx.startup)
	implementation(libs.bundles.androidx.compose)
	implementation(libs.accompanist.permissions)

	// Dependency Injection
	implementation(libs.bundles.koin)

	// Media players
	implementation(libs.androidx.media3.exoplayer)
	implementation(libs.androidx.media3.datasource.okhttp)
	implementation(libs.androidx.media3.exoplayer.hls)
	implementation(libs.androidx.media3.ui)
	implementation(libs.jellyfin.androidx.media3.ffmpeg.decoder)
	implementation(libs.libass.media3)

	// Markdown
	implementation(libs.bundles.markwon)

	// Image utility
	implementation(libs.bundles.coil)

	// Crash Reporting
	implementation(libs.bundles.acra)

	// Licenses
	implementation(libs.aboutlibraries)

	// QR codes (shared music queue)
	implementation(libs.zxing.core)

	// Logging
	implementation(libs.timber)
	implementation(libs.slf4j.timber)

	// Compatibility (desugaring)
	coreLibraryDesugaring(libs.android.desugar)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
	testImplementation(libs.mockk)
}
