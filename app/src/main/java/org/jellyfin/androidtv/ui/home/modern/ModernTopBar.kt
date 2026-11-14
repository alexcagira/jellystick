package org.jellyfin.androidtv.ui.home.modern

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
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
	onMoveLibrary: ((BaseItemDto, Int) -> Unit)? = null,
) {
	val navigationRepository = koinInject<NavigationRepository>()
	val itemLauncher = koinInject<ItemLauncher>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val context = LocalContext.current
	var libraryToHide by remember { mutableStateOf<BaseItemDto?>(null) }
	// Keeps the name visible while the dialog fades out
	var lastLibraryToHide by remember { mutableStateOf<BaseItemDto?>(null) }
	// Hold OK on a library: menu with "Move" and "Hide"
	var libraryMenu by remember { mutableStateOf<BaseItemDto?>(null) }
	var lastLibraryMenu by remember { mutableStateOf<BaseItemDto?>(null) }
	// Library being moved with left/right
	var movingLibraryId by remember { mutableStateOf<java.util.UUID?>(null) }

	// Music playing: bubble under the buttons on the right, Down from those buttons goes to it
	val musicPlaying = rememberMusicPlaying()
	val bubbleFocusRequester = remember { FocusRequester() }
	var endButtonsFocused by remember { mutableStateOf(false) }

	Column(
		horizontalAlignment = Alignment.End,
		modifier = modifier
			.fillMaxWidth()
			.padding(
				start = ModernHomeDimens.ScreenPaddingHorizontal,
				end = ModernHomeDimens.ScreenPaddingHorizontal,
				top = ModernHomeDimens.ScreenPaddingTop,
			),
	) {
		ToolbarLayout(
			modifier = Modifier
				.fillMaxWidth()
				// Down always leaves the bar (to the hero buttons), whatever is below it
				.onPreviewKeyEvent { event ->
					if (movingLibraryId != null || event.key != Key.DirectionDown) return@onPreviewKeyEvent false
					if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
					if (endButtonsFocused && musicPlaying && bubbleFocusRequester.tryRequestFocus()) true
					else onNavigateDown()
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
						// Keyed so the focus follows the library when it moves
						key(library.id) {
							NavigationPillButton(
								text = library.name.orEmpty(),
								icon = library.collectionType.navigationIcon,
								active = false,
								moving = movingLibraryId == library.id,
								onClick = {
									if (movingLibraryId == library.id) movingLibraryId = null
									else itemLauncher.launchUserView(library)
								},
								// Hold OK: move or hide the library
								onLongClick = if (onHideLibrary != null || onMoveLibrary != null) {
									{
										libraryMenu = library
										lastLibraryMenu = library
									}
								} else null,
								onMove = { offset -> onMoveLibrary?.invoke(library, offset) },
								onMoveDone = { movingLibraryId = null },
							)
						}
					}

					NavigationPillButton(
						text = null,
						icon = R.drawable.ic_search,
						active = false,
						onClick = { navigationRepository.navigate(Destinations.modernSearch()) },
					)
				}
			},
			end = {
				Row(
					horizontalArrangement = Arrangement.spacedBy(10.dp),
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.onFocusChanged { endButtonsFocused = it.hasFocus },
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

		ModernNowPlayingBubble(
			glassBackdrop = glassBackdrop,
			focusRequester = bubbleFocusRequester,
			modifier = Modifier.onPreviewKeyEvent { event ->
				// Down: to the page, like the rest of the bar. Left/right stay on the bubble
				when (event.key) {
					Key.DirectionDown -> {
						if (event.type == KeyEventType.KeyDown) onNavigateDown()
						true
					}

					Key.DirectionLeft, Key.DirectionRight -> true
					else -> false
				}
			},
		)
	}

	val menuLibrary = lastLibraryMenu
	ModernMenuDialog(
		visible = libraryMenu != null,
		title = menuLibrary?.name.orEmpty(),
		subtitle = null,
		options = buildList {
			if (menuLibrary == null) return@buildList
			if (onMoveLibrary != null) {
				add(ModernMenuOption(stringResource(R.string.modern_home_move), R.drawable.ic_move_horizontal) {
					movingLibraryId = menuLibrary.id
					Toast.makeText(context, context.getString(R.string.modern_home_move_hint), Toast.LENGTH_LONG).show()
				})
			}
			if (onHideLibrary != null) {
				add(ModernMenuOption(stringResource(R.string.modern_home_hide), R.drawable.ic_visibility_off) {
					libraryToHide = menuLibrary
					lastLibraryToHide = menuLibrary
				})
			}
		},
		onDismissRequest = { libraryMenu = null },
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
	moving: Boolean = false,
	onMove: (Int) -> Unit = {},
	onMoveDone: () -> Unit = {},
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	val containerColor = when {
		moving -> ModernHomeColors.Accent
		focused -> Color.White
		active -> ModernHomeColors.PillActive
		else -> Color.Transparent
	}
	val contentColor = if (focused && !moving) Color.Black else ModernHomeColors.TextPrimary

	Row(
		modifier = Modifier
			// Leaving the library ends the move
			.onFocusChanged { state -> if (moving && !state.isFocused) onMoveDone() }
			// While moving, left/right move the library instead of the focus
			.onPreviewKeyEvent { event ->
				if (!moving) return@onPreviewKeyEvent false
				val isDown = event.type == KeyEventType.KeyDown
				when (event.key) {
					Key.DirectionLeft -> {
						if (isDown) onMove(-1)
						true
					}

					Key.DirectionRight -> {
						if (isDown) onMove(1)
						true
					}

					Key.DirectionUp, Key.DirectionDown -> true

					Key.Back, Key.Escape -> {
						if (event.type == KeyEventType.KeyUp) onMoveDone()
						true
					}

					else -> false
				}
			}
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
				text = if (moving) "‹ $text ›" else text,
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
