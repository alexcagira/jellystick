package org.jellyfin.androidtv.ui.search.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.AmbientBackground
import org.jellyfin.androidtv.ui.home.modern.CardStyle
import org.jellyfin.androidtv.ui.home.modern.HomeCard
import org.jellyfin.androidtv.ui.home.modern.HomeSection
import org.jellyfin.androidtv.ui.home.modern.HomeSectionRow
import org.jellyfin.androidtv.ui.home.modern.HomeSectionType
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.PosterActionsDialogs
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.menuTarget
import org.jellyfin.androidtv.ui.home.modern.rememberPosterActionsState
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.music.modern.MusicCard
import org.jellyfin.androidtv.ui.music.modern.MusicCardKind
import org.jellyfin.androidtv.ui.music.modern.MusicSectionRow
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.search.composable.SearchVoiceInput
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.speech.rememberSpeechRecognizerAvailability
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject

/**
 * Search in the style of the modern screens: posters with every badge, and voice input.
 */
class ModernSearchFragment : Fragment() {
	companion object {
		const val ARGUMENT_MUSIC = "Music"
	}

	private val viewModel by viewModel<ModernSearchViewModel>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		viewModel.musicOnly = arguments?.getBoolean(ARGUMENT_MUSIC) == true
		return content { ModernSearchScreen(viewModel) }
	}
}

@Composable
fun ModernSearchScreen(viewModel: ModernSearchViewModel) {
	val state by viewModel.state.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val imageLoader = koinInject<ImageLoader>()
	val posterActions = rememberPosterActionsState()
	val fieldFocusRequester = remember { FocusRequester() }
	val backgroundState = remember { mutableStateOf<String?>(null) }
	val voiceAvailable = rememberSpeechRecognizerAvailability()
	// The field opens the keyboard (and the voice input of the remote) right away the first time
	var startEditing by remember { mutableStateOf(state.query.isEmpty()) }

	fun openDetails(item: BaseItemDto, targetId: java.util.UUID) {
		val destination = when (item.type) {
			BaseItemKind.BOX_SET -> Destinations.collectionBrowser(item)
			else -> Destinations.itemDetails(targetId)
		}
		navigationRepository.navigate(destination)
	}

	fun play(item: BaseItemDto) {
		scope.launch {
			val playableId = viewModel.resolvePlayableId(item)
			if (playableId != null) playbackHelper.retrieveAndPlay(playableId, false, context)
		}
	}

	fun onPosterClick(card: HomeCard) = openDetails(card.item, card.targetId)
	fun onPosterPlay(card: HomeCard) {
		val playId = card.playId
		if (playId != null) playbackHelper.retrieveAndPlay(playId, false, context) else play(card.item)
	}

	fun onMusicClick(card: MusicCard) {
		val item = card.item ?: return
		when (card.kind) {
			MusicCardKind.TRACK -> playbackHelper.retrieveAndPlay(item.id, false, context)
			else -> navigationRepository.navigate(Destinations.modernMusicItem(item.id))
		}
	}

	// Coming back to the page: the search box (the first time the keyboard opens by itself)
	LaunchedEffect(Unit) {
		if (startEditing) return@LaunchedEffect
		awaitFrames(2)
		fieldFocusRequester.tryRequestFocus()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background),
	) {
		AmbientBackground(urlState = backgroundState, imageLoader = imageLoader)

		LazyColumn(
			contentPadding = PaddingValues(top = 36.dp, bottom = 80.dp),
			modifier = Modifier.fillMaxSize(),
		) {
			item(key = "field") {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.padding(horizontal = ModernHomeDimens.ScreenPaddingHorizontal),
				) {
					SearchField(
						query = state.query,
						hint = stringResource(if (viewModel.musicOnly) R.string.search_hint_music else R.string.search_hint),
						startEditing = startEditing,
						onEditingStarted = { startEditing = false },
						onQueryChange = { viewModel.setQuery(it) },
						onSubmit = { viewModel.setQuery(state.query, immediate = true) },
						focusRequester = fieldFocusRequester,
						modifier = Modifier.weight(1f),
					)
					if (voiceAvailable) {
						Spacer(Modifier.width(14.dp))
						SearchVoiceInput(
							onQueryChange = { viewModel.setQuery(it) },
							onQuerySubmit = { viewModel.setQuery(viewModel.state.value.query, immediate = true) },
						)
					}
				}
				Text(
					text = stringResource(R.string.search_voice_hint),
					color = ModernHomeColors.TextMuted,
					fontSize = 12.sp,
					modifier = Modifier.padding(start = ModernHomeDimens.ScreenPaddingHorizontal + 4.dp, top = 8.dp, bottom = 4.dp),
				)
			}

			itemsIndexed(state.groups, key = { index, group -> "${group.title}_$index" }) { _, group ->
				when (group) {
					is SearchGroup.Posters -> HomeSectionRow(
						section = HomeSection(HomeSectionType.FAVORITES, CardStyle.POSTER, group.cards),
						title = stringResource(group.title),
						initialCardIndex = 0,
						focusRequesterFor = { _, _ -> null },
						onCardClick = ::onPosterClick,
						onCardLongClick = { card -> posterActions.open(card.menuTarget()) },
						onCardPlay = ::onPosterPlay,
						onCardFocused = { card -> card.backgroundUrl?.let { backgroundState.value = it } },
						modifier = Modifier.padding(top = ModernHomeDimens.SectionSpacing),
					)

					is SearchGroup.Music -> MusicSectionRow(
						title = stringResource(group.title),
						cards = group.cards,
						focusRequesterFor = { null },
						onFocused = { card -> card.backgroundUrl?.let { backgroundState.value = it } },
						onClick = ::onMusicClick,
						onLongClick = { card ->
							val item = card.item
							val albumId = item?.albumId
							if (card.kind == MusicCardKind.TRACK && albumId != null) navigationRepository.navigate(Destinations.modernMusicItem(albumId))
							else onMusicClick(card)
						},
						onPlay = { card -> card.item?.let { playbackHelper.retrieveAndPlay(it.id, false, context) } },
					)
				}
			}
		}

		when {
			state.searching -> CircularProgressIndicator(
				color = ModernHomeColors.Accent,
				modifier = Modifier
					.align(Alignment.Center)
					.size(44.dp),
			)

			state.searched && state.groups.isEmpty() -> Text(
				text = stringResource(R.string.search_no_results, state.query.trim()),
				color = ModernHomeColors.TextSecondary,
				fontSize = 16.sp,
				modifier = Modifier.align(Alignment.Center),
			)
		}

		PosterActionsDialogs(
			state = posterActions,
			onPlay = ::play,
			onOpen = { id -> navigationRepository.navigate(Destinations.itemDetails(id)) },
			onChanged = { viewModel.setQuery(viewModel.state.value.query, immediate = true) },
		)
	}
}

/**
 * Search box. OK opens the keyboard of the TV: on Fire TV, hold the microphone button of the
 * remote while the keyboard is open to dictate the search.
 */
@Composable
private fun SearchField(
	query: String,
	hint: String,
	startEditing: Boolean,
	onEditingStarted: () -> Unit,
	onQueryChange: (String) -> Unit,
	onSubmit: () -> Unit,
	focusRequester: FocusRequester,
	modifier: Modifier = Modifier,
) {
	val keyboard = LocalSoftwareKeyboardController.current
	var editing by remember { mutableStateOf(startEditing) }
	// Back on the box after typing (not when the focus left the text field on its own)
	var refocusBox by remember { mutableStateOf(false) }
	val textFocusRequester = remember { FocusRequester() }
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(16.dp)

	fun stopEditing(refocus: Boolean) {
		keyboard?.hide()
		refocusBox = refocus
		editing = false
	}

	LaunchedEffect(editing, refocusBox) {
		if (editing) {
			onEditingStarted()
			awaitFrames(1)
			textFocusRequester.tryRequestFocus()
			// Some keyboards (Amazon) don't open by themselves when the field gets the focus
			keyboard?.show()
		} else if (refocusBox) {
			refocusBox = false
			awaitFrames(1)
			focusRequester.tryRequestFocus()
		}
	}

	Box(modifier = modifier) {
		if (editing) {
			BasicTextField(
				value = query,
				onValueChange = onQueryChange,
				singleLine = true,
				textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
				cursorBrush = SolidColor(Color.White),
				keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search, autoCorrectEnabled = false, showKeyboardOnFocus = true),
				keyboardActions = KeyboardActions {
					stopEditing(refocus = true)
					onSubmit()
				},
				decorationBox = { inner ->
					SearchBoxFrame(style = SearchBoxStyle.EDITING, shape = shape) {
						Box {
							if (query.isEmpty()) Text(text = hint, color = ModernHomeColors.TextMuted, fontSize = 20.sp, maxLines = 1)
							inner()
						}
					}
				},
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(textFocusRequester)
					.onFocusChanged { if (!it.isFocused && editing) stopEditing(refocus = false) }
					// Up/down without the keyboard: back to the box, then the results
					.onPreviewKeyEvent { event ->
						if ((event.key == Key.DirectionDown || event.key == Key.DirectionUp) && event.type == KeyEventType.KeyDown) {
							stopEditing(refocus = true)
							true
						} else false
					},
			)
		} else {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(focusRequester)
					.clickable(interactionSource = interactionSource, indication = null) { editing = true }
					.onPreviewKeyEvent { event ->
						// Search key of some remotes
						if (event.key == Key.Search && event.type == KeyEventType.KeyDown) {
							editing = true
							true
						} else false
					},
			) {
				SearchBoxFrame(style = if (focused) SearchBoxStyle.FOCUSED else SearchBoxStyle.IDLE, shape = shape) {
					Text(
						text = query.ifEmpty { hint },
						color = when {
							focused && query.isNotEmpty() -> Color.Black
							focused -> Color.Black.copy(alpha = 0.6f)
							query.isEmpty() -> ModernHomeColors.TextMuted
							else -> ModernHomeColors.TextPrimary
						},
						fontSize = 20.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

private enum class SearchBoxStyle { IDLE, FOCUSED, EDITING }

@Composable
private fun SearchBoxFrame(
	style: SearchBoxStyle,
	shape: RoundedCornerShape,
	content: @Composable () -> Unit,
) {
	val focused = style == SearchBoxStyle.FOCUSED
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.background(
				when (style) {
					SearchBoxStyle.FOCUSED -> Color.White
					SearchBoxStyle.EDITING -> Color.White.copy(alpha = 0.16f)
					SearchBoxStyle.IDLE -> Color.White.copy(alpha = 0.1f)
				},
				shape,
			)
			.border(if (style == SearchBoxStyle.EDITING) 2.dp else 1.dp, if (style == SearchBoxStyle.EDITING) ModernHomeColors.Accent else ModernHomeColors.GlassBorder, shape)
			.padding(horizontal = 20.dp, vertical = 14.dp),
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_search),
			contentDescription = null,
			tint = if (focused) Color.Black else ModernHomeColors.TextSecondary,
			modifier = Modifier.size(22.dp),
		)
		Spacer(Modifier.width(14.dp))
		Column(modifier = Modifier.weight(1f)) { content() }
	}
}
