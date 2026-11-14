package org.jellyfin.androidtv.ui.home.modern

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filterNotNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.ui.shared.toolbar.Logo
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarLayout
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * Floating navigation bar: logo on the left, a frosted glass pill with Home + libraries + search in
 * the center, settings and the user avatar on the right.
 */
@Composable
internal fun ModernTopBar(
	libraries: List<BaseItemDto>,
	glassBackdrop: GlassBackdrop?,
	modifier: Modifier = Modifier,
	homeActive: Boolean = true,
	watchlistActive: Boolean = false,
	onNavigateDown: () -> Boolean = { false },
	onHideLibrary: ((BaseItemDto) -> Unit)? = null,
) {
	val navigationRepository = koinInject<NavigationRepository>()
	val itemLauncher = koinInject<ItemLauncher>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	var libraryToHide by remember { mutableStateOf<BaseItemDto?>(null) }
	// Keeps the name visible while the dialog fades out
	var lastLibraryToHide by remember { mutableStateOf<BaseItemDto?>(null) }

	ToolbarLayout(
		modifier = modifier
			.fillMaxWidth()
			.padding(
				start = ModernHomeDimens.ScreenPaddingHorizontal,
				end = ModernHomeDimens.ScreenPaddingHorizontal,
				top = ModernHomeDimens.ScreenPaddingTop,
			)
			// Down always leaves the bar (to the hero buttons), whatever is below it
			.onPreviewKeyEvent { event ->
				event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onNavigateDown()
			}
			.focusRestorer()
			.focusGroup(),
		start = {
			Logo(modifier = Modifier.height(28.dp))
		},
		center = {
			NavigationPill(glassBackdrop = glassBackdrop) {
				NavigationPillButton(
					text = stringResource(R.string.lbl_home),
					icon = R.drawable.ic_house,
					active = homeActive,
					onClick = {
						if (!homeActive) navigationRepository.reset(Destinations.home, clearHistory = true)
					},
				)

				for (library in libraries) {
					NavigationPillButton(
						text = library.name.orEmpty(),
						icon = library.collectionType.navigationIcon,
						active = false,
						onClick = { itemLauncher.launchUserView(library) },
						// Hold OK to hide the library
						onLongClick = onHideLibrary?.let {
							{
								libraryToHide = library
								lastLibraryToHide = library
							}
						},
					)
				}

				NavigationPillButton(
					text = null,
					icon = R.drawable.ic_search,
					active = false,
					onClick = { navigationRepository.navigate(Destinations.search()) },
				)
			}
		},
		end = {
			Row(
				horizontalArrangement = Arrangement.spacedBy(10.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CircleIconButton(
					icon = if (watchlistActive) R.drawable.ic_watchlist_added else R.drawable.ic_watchlist,
					contentDescription = stringResource(R.string.modern_home_watchlist),
					glassBackdrop = glassBackdrop,
					active = watchlistActive,
					onClick = { if (!watchlistActive) navigationRepository.navigate(Destinations.modernWatchlist) },
				)
				CircleIconButton(
					icon = R.drawable.ic_settings,
					contentDescription = stringResource(R.string.lbl_settings),
					glassBackdrop = glassBackdrop,
					onClick = { settingsViewModel.show() },
				)
				UserButton()
			}
		},
	)

	val hideName = lastLibraryToHide?.name.orEmpty()
	ModernConfirmDialog(
		visible = libraryToHide != null,
		title = stringResource(R.string.modern_home_hide_library_title, hideName),
		message = stringResource(R.string.modern_home_hide_library_message),
		confirmText = stringResource(R.string.modern_home_hide),
		dismissText = stringResource(R.string.lbl_cancel),
		onConfirm = {
			libraryToHide?.let { library -> onHideLibrary?.invoke(library) }
			libraryToHide = null
		},
		onDismissRequest = { libraryToHide = null },
	)
}

private val CollectionType?.navigationIcon: Int
	get() = when (this) {
		CollectionType.MOVIES -> R.drawable.ic_movie
		CollectionType.TVSHOWS -> R.drawable.ic_tv
		CollectionType.MUSIC -> R.drawable.ic_music_album
		CollectionType.PLAYLISTS -> R.drawable.ic_grid
		CollectionType.LIVETV -> R.drawable.ic_tv_guide
		CollectionType.PHOTOS, CollectionType.HOMEVIDEOS -> R.drawable.ic_photo
		else -> R.drawable.ic_folder
	}

/**
 * Glass pill. Scrolls horizontally when there are more libraries than fit on screen.
 */
@Composable
private fun NavigationPill(
	glassBackdrop: GlassBackdrop?,
	content: @Composable RowScope.() -> Unit,
) {
	Row(
		modifier = Modifier
			.glassSurface(glassBackdrop, CircleShape)
			.horizontalScroll(rememberScrollState())
			.padding(4.dp)
			.focusRestorer()
			.focusGroup(),
		horizontalArrangement = Arrangement.spacedBy(2.dp),
		verticalAlignment = Alignment.CenterVertically,
		content = content,
	)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavigationPillButton(
	text: String?,
	icon: Int,
	active: Boolean,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	val containerColor = when {
		focused -> Color.White
		active -> ModernHomeColors.PillActive
		else -> Color.Transparent
	}
	val contentColor = if (focused) Color.Black else ModernHomeColors.TextPrimary

	Row(
		modifier = Modifier
			.combinedClickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
				onLongClick = onLongClick,
			)
			.background(containerColor, CircleShape)
			.padding(horizontal = if (text != null) 12.dp else 10.dp, vertical = 7.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = if (text == null) stringResource(R.string.lbl_search) else null,
			tint = contentColor,
			modifier = Modifier.size(14.dp),
		)

		if (text != null) {
			Spacer(Modifier.width(6.dp))
			Text(
				text = text,
				color = contentColor,
				fontSize = 12.sp,
				fontWeight = FontWeight.SemiBold,
				maxLines = 1,
			)
		}
	}
}

@Composable
private fun CircleIconButton(
	icon: Int,
	contentDescription: String,
	glassBackdrop: GlassBackdrop?,
	onClick: () -> Unit,
	active: Boolean = false,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	Box(
		modifier = Modifier
			.size(36.dp)
			.glassSurface(glassBackdrop, CircleShape)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.background(
				when {
					focused -> Color.White
					active -> ModernHomeColors.PillActive
					else -> Color.Transparent
				},
				CircleShape,
			),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = contentDescription,
			tint = if (focused) Color.Black else ModernHomeColors.TextPrimary,
			modifier = Modifier.size(18.dp),
		)
	}
}

/**
 * User avatar, switches user when clicked (same behavior as the original toolbar).
 */
@Composable
private fun UserButton() {
	val userRepository = koinInject<UserRepository>()
	val sessionRepository = koinInject<SessionRepository>()
	val mediaManager = koinInject<MediaManager>()
	val api = koinInject<ApiClient>()
	val activity = LocalActivity.current

	val currentUser by remember { userRepository.currentUser.filterNotNull() }.collectAsState(null)
	val userImage = remember(currentUser) { currentUser?.primaryImage?.getUrl(api) }

	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	Box(
		modifier = Modifier
			.size(36.dp)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = {
					mediaManager.clearAudioQueue()
					sessionRepository.destroyCurrentSession()
					activity?.startActivity(ActivityDestinations.startup(activity))
					activity?.finishAfterTransition()
				},
			)
			.border(if (focused) 2.dp else 1.dp, if (focused) Color.White else ModernHomeColors.GlassBorder, CircleShape)
			.padding(2.dp)
			.clip(CircleShape),
		contentAlignment = Alignment.Center,
	) {
		ProfilePicture(
			url = userImage,
			contentDescription = stringResource(R.string.lbl_switch_user),
			modifier = Modifier
				.aspectRatio(1f)
				.clip(CircleShape),
		)
	}
}
