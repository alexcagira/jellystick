package org.jellyfin.androidtv.di

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.NetworkFetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import coil3.svg.SvgDecoder
import coil3.util.Logger
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.auth.repository.UserRepositoryImpl
import org.jellyfin.androidtv.data.eventhandling.SocketHandler
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.CustomMessageRepositoryImpl
import org.jellyfin.androidtv.data.repository.ExternalAppRepository
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemMutationRepositoryImpl
import org.jellyfin.androidtv.data.repository.LibraryVisibilityRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepositoryImpl
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepositoryImpl
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.integration.dream.DreamViewModel
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.details.modern.ModernDetailsRepository
import org.jellyfin.androidtv.ui.details.modern.ModernDetailsViewModel
import org.jellyfin.androidtv.ui.details.modern.ThemeMusicPlayer
import org.jellyfin.androidtv.ui.home.modern.HomeCache
import org.jellyfin.androidtv.ui.home.modern.ModernHomeRepository
import org.jellyfin.androidtv.ui.collections.CollectionCatalog
import org.jellyfin.androidtv.ui.settings.update.AppUpdater
import org.jellyfin.androidtv.ui.collections.LanguageCollectionsRepository
import org.jellyfin.androidtv.ui.collections.ServiceCollectionsRepository
import org.jellyfin.androidtv.ui.home.modern.ItemActions
import org.jellyfin.androidtv.ui.home.modern.ModernHomeViewModel
import org.jellyfin.androidtv.ui.home.modern.ModernWatchlistViewModel
import org.jellyfin.androidtv.ui.music.modern.ModernMusicBrowseViewModel
import org.jellyfin.androidtv.ui.collections.ImdbTop250
import org.jellyfin.androidtv.ui.library.modern.LibraryBrowseRepository
import org.jellyfin.androidtv.ui.library.modern.ModernBrowseViewModel
import org.jellyfin.androidtv.ui.library.modern.AudioLanguageIndex
import org.jellyfin.androidtv.ui.music.MusicStopper
import org.jellyfin.androidtv.ui.library.modern.ModernLibraryRepository
import org.jellyfin.androidtv.ui.library.modern.ModernLibraryViewModel
import org.jellyfin.androidtv.ui.music.modern.FavouriteTracks
import org.jellyfin.androidtv.ui.music.modern.MusicCache
import org.jellyfin.androidtv.ui.music.modern.ModernMusicItemViewModel
import org.jellyfin.androidtv.ui.music.modern.ModernMusicRepository
import org.jellyfin.androidtv.ui.music.modern.MusicItemRepository
import org.jellyfin.androidtv.ui.music.share.SharedQueueServer
import org.jellyfin.androidtv.ui.music.modern.ModernMusicViewModel
import org.jellyfin.androidtv.ui.home.modern.TmdbTrendingClient
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.navigation.NavigationRepositoryImpl
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.external.DefaultExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.ExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.MpvExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.MxExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.VimuExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.VlcExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.nextup.NextUpViewModel
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepositoryImpl
import org.jellyfin.androidtv.ui.playback.stillwatching.StillWatchingViewModel
import org.jellyfin.androidtv.ui.player.photo.PhotoPlayerViewModel
import org.jellyfin.androidtv.ui.search.SearchFragmentDelegate
import org.jellyfin.androidtv.ui.search.SearchRepository
import org.jellyfin.androidtv.ui.search.modern.ModernSearchViewModel
import org.jellyfin.androidtv.ui.search.SearchRepositoryImpl
import org.jellyfin.androidtv.ui.search.SearchViewModel
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesScreenViewModel
import org.jellyfin.androidtv.ui.startup.ServerAddViewModel
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.ui.startup.UserLoginViewModel
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.MarkdownRenderer
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.ReportingHelper
import org.jellyfin.androidtv.util.coil.CoilTimberLogger
import org.jellyfin.androidtv.util.coil.createCoilConnectivityChecker
import org.jellyfin.androidtv.util.sdk.SdkPlaybackHelper
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin as JellyfinSdk

val defaultDeviceInfo = named("defaultDeviceInfo")

private const val MAX_REQUESTS = 64
private const val MAX_REQUESTS_PER_SERVER = 24

val appModule = module {
	// SDK
	single(defaultDeviceInfo) { androidDevice(get()) }
	// OkHttp sends at most 5 requests to the same server at a time and queues the rest. The API, the
	// video stream and the theme songs share them: the player waited behind rows of the home screen
	single {
		OkHttpFactory(
			OkHttpClient.Builder()
				.dispatcher(Dispatcher().apply {
					maxRequests = MAX_REQUESTS
					maxRequestsPerHost = MAX_REQUESTS_PER_SERVER
				})
				.build()
		)
	}
	single { HttpClientOptions() }
	single {
		createJellyfin {
			context = androidContext()

			// Add client info
			val clientName = buildString {
				// The server dashboard shows "Jellystick 1.2.2"
				append("Jellystick")
				if (BuildConfig.DEBUG) append(" (debug)")
			}
			clientInfo = ClientInfo(clientName, BuildConfig.VERSION_NAME)
			deviceInfo = get(defaultDeviceInfo)

			// Change server version
			minimumServerVersion = ServerRepository.minimumServerVersion

			// Use our own shared factory instance
			apiClientFactory = get<OkHttpFactory>()
			socketConnectionFactory = get<OkHttpFactory>()
		}
	}

	single {
		// Create an empty API instance, the actual values are set by the SessionRepository
		get<JellyfinSdk>().createApi(httpClientOptions = get<HttpClientOptions>())
	}

	single { SocketHandler(get(), get(), get(), get(), get(), get(), get(), get(), get(), ProcessLifecycleOwner.get().lifecycle) }

	// Coil (images)
	single {
		val okHttpFactory = get<OkHttpFactory>()
		val httpClientOptions = get<HttpClientOptions>()

		@OptIn(ExperimentalCoilApi::class)
		OkHttpNetworkFetcherFactory(
			callFactory = { okHttpFactory.createClient(httpClientOptions) },
			connectivityChecker = ::createCoilConnectivityChecker,
		)
	}

	single {
		ImageLoader.Builder(androidContext()).apply {
			serviceLoaderEnabled(false)
			logger(CoilTimberLogger(if (BuildConfig.DEBUG) Logger.Level.Warn else Logger.Level.Error))

			components {
				add(get<NetworkFetcher.Factory>())

				if (AndroidVersion.isAtLeastP) add(AnimatedImageDecoder.Factory())
				else add(GifDecoder.Factory())
				add(SvgDecoder.Factory())
			}
		}.build()
	}

	// Non API related
	single { DataRefreshService() }
	single { PlaybackControllerContainer() }
	single { InteractionTrackerViewModel(get(), get()) }

	single<UserRepository> { UserRepositoryImpl() }
	single<UserViewsRepository> { UserViewsRepositoryImpl(get()) }
	single<NotificationsRepository> { NotificationsRepositoryImpl(get(), get()) }
	single<ItemMutationRepository> { ItemMutationRepositoryImpl(get(), get()) }
	single<CustomMessageRepository> { CustomMessageRepositoryImpl() }
	single<NavigationRepository> { NavigationRepositoryImpl(Destinations.home) }
	single<SearchRepository> { SearchRepositoryImpl(get(), get()) }
	single { LibraryVisibilityRepository(get(), get(), get(), androidContext()) }

	// Modern home screen
	single { TmdbTrendingClient(BuildConfig.TMDB_API_KEY) }
	single { ModernHomeRepository(get(), get(), get()) }
	single { ItemActions(get(), get()) }
	single { CollectionCatalog(androidContext(), get()) }
	// Settings → Update Jellystick
	single { AppUpdater(androidContext()) }
	single { LanguageCollectionsRepository(androidContext(), get(), get(), get()) }
	single { ServiceCollectionsRepository(androidContext(), get(), get(), get()) }
	single { SharedQueueServer(androidContext(), get(), get()) }
	single { MusicStopper(get(), get(), get()) }
	single { HomeCache(androidContext()) }
	single { ModernDetailsRepository(get(), get()) }
	single { MusicCache(androidContext()) }
	single { ImdbTop250(androidContext(), get()) }
	single { ModernLibraryRepository(androidContext(), get(), get(), get(), get()) }
	single { LibraryBrowseRepository(get(), get()) }
	single { AudioLanguageIndex(androidContext(), get()) }
	single { ModernMusicRepository(get(), get()) }
	single { MusicItemRepository(get(), get()) }
	single { FavouriteTracks(get()) }
	single { ThemeMusicPlayer(androidContext(), get(), get(), get(), ProcessLifecycleOwner.get().lifecycle) }
	single<MediaSegmentRepository> { MediaSegmentRepositoryImpl(get(), get()) }
	single<ExternalAppRepository> { ExternalAppRepository(get(), getAll(), get<DefaultExternalPlayerApi>()) }

	// External player APIs
	single { VlcExternalPlayerApi() } bind ExternalPlayerApi::class
	single { MxExternalPlayerApi() } bind ExternalPlayerApi::class
	single { MpvExternalPlayerApi() } bind ExternalPlayerApi::class
	single { VimuExternalPlayerApi() } bind ExternalPlayerApi::class
	single { DefaultExternalPlayerApi() }

	viewModel { StartupViewModel(get(), get(), get(), get()) }
	viewModel { UserLoginViewModel(get(), get(), get(), get(defaultDeviceInfo)) }
	viewModel { ServerAddViewModel(get()) }
	viewModel { NextUpViewModel(get(), get(), get()) }
	viewModel { StillWatchingViewModel(get(), get(), get(), get()) }
	viewModel { PhotoPlayerViewModel(get(), get()) }
	viewModel { SearchViewModel(get()) }
	viewModel { ModernHomeViewModel(get(), get(), get(), get(), get()) }
	viewModel { ModernDetailsViewModel(get(), get(), get()) }
	viewModel { ModernWatchlistViewModel(get(), get()) }
	viewModel { ModernMusicViewModel(get(), get(), get(), get()) }
	viewModel { ModernMusicItemViewModel(get(), get(), get(), get()) }
	viewModel { ModernLibraryViewModel(get(), get(), get(), get(), get()) }
	viewModel { ModernBrowseViewModel(get(), get(), get(), get()) }
	viewModel { ModernMusicBrowseViewModel(get()) }
	viewModel { ModernSearchViewModel(get(), get(), get(), get()) }
	viewModel { DreamViewModel(get(), get(), get(), get(), get()) }
	viewModel { SettingsViewModel() }
	viewModel { SettingsLibrariesScreenViewModel(get()) }

	single { BackgroundService(get(), get(), get(), get(), get()) }

	single { MarkdownRenderer(get()) }
	single { ItemLauncher() }
	single { KeyProcessor() }
	single { ReportingHelper(get(), get(), get()) }
	single<PlaybackHelper> { SdkPlaybackHelper(get(), get(), get(), get(), get()) }

	factory { (context: Context) -> SearchFragmentDelegate(context, get(), get()) }
}
