package org.jellyfin.androidtv.ui.library.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.CardArtwork
import org.jellyfin.androidtv.ui.home.modern.FocusableCardSurface
import org.jellyfin.androidtv.ui.home.modern.QualityBadges
import org.jellyfin.androidtv.ui.home.modern.LanguageFlags
import org.jellyfin.androidtv.ui.home.modern.NewContentBadge
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.ModernHomeDimens
import org.jellyfin.androidtv.ui.home.modern.ModernHomeRepository
import org.jellyfin.androidtv.ui.home.modern.ModernMenuDialog
import org.jellyfin.androidtv.ui.home.modern.ModernMenuOption
import org.jellyfin.androidtv.ui.home.modern.PosterActionsDialogs
import org.jellyfin.androidtv.ui.home.modern.PosterMenuTarget
import org.jellyfin.androidtv.ui.home.modern.ProvideKeepInViewScrolling
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.rememberPosterActionsState
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

private const val PAGE_SIZE = 60
private const val FOCUS_ATTEMPTS = 15
private const val COLUMNS = 7
/** Header and tag rows before the first row of posters */
private const val HEADER_ITEMS = 2
private val LETTERS = listOf('#') + ('A'..'Z')

data class BrowseState(
	val filter: BrowseFilter,
	/** null while the first page loads */
	val total: Int? = null,
	/** Grows when the filter changes: the grid goes back to the top */
	val version: Int = 0,
	val failed: Boolean = false,
	val genres: List<BrowseChip> = emptyList(),
	val tags: List<String> = emptyList(),
	val ratings: List<String> = emptyList(),
	/** Item to scroll to and focus (letter jump), with a counter so the same index works twice */
	val jumpIndex: Int? = null,
	val jumpCount: Int = 0,
)

class ModernBrowseViewModel(
	private val repository: LibraryBrowseRepository,
	private val libraryRepository: ModernLibraryRepository,
	private val homeRepository: ModernHomeRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(BrowseState(BrowseFilter(title = "")))
	val state: StateFlow<BrowseState> = _state.asStateFlow()

	/** Loaded pages of posters (read by the grid, so they are snapshot state) */
	val pages = mutableStateMapOf<Int, List<BrowseCard>>()
	private val loadingPages = HashSet<Int>()
	private var randomIds: List<UUID>? = null
	private var loadJob: Job? = null
	private var initialized = false

	/** Last focused poster, restored when coming back */
	var lastFocusIndex: Int? = null

	fun init(filter: BrowseFilter) {
		if (initialized) return
		initialized = true
		_state.value = BrowseState(filter)
		loadOptions(filter)
		reload()
	}

	fun setFilter(change: BrowseFilter.() -> BrowseFilter) {
		val filter = _state.value.filter.change()
		if (filter == _state.value.filter) return
		lastFocusIndex = null
		_state.update { it.copy(filter = filter, total = null, failed = false, version = it.version + 1, jumpIndex = null) }
		reload()
	}

	private fun loadOptions(filter: BrowseFilter) {
		val base = filter.copy(genre = null, tag = null, officialRating = null, played = PlayedFilter.ALL)
		viewModelScope.launch {
			val genres = runCatching { libraryRepository.genres(base.libraryId, base.kind) }.getOrDefault(emptyList())
			_state.update { it.copy(genres = genres) }
		}
		viewModelScope.launch {
			val tags = runCatching { libraryRepository.popularTags(base.libraryId, base.kind) }.getOrDefault(emptyList())
			_state.update { it.copy(tags = tags) }
		}
		viewModelScope.launch {
			val ratings = runCatching { repository.officialRatings(base) }.getOrDefault(emptyList())
			_state.update { it.copy(ratings = ratings) }
		}
	}

	private fun reload() {
		loadJob?.cancel()
		pages.clear()
		loadingPages.clear()
		randomIds = null

		val filter = _state.value.filter
		val version = _state.value.version
		loadJob = viewModelScope.launch {
			try {
				if (filter.sort == BrowseSort.RANDOM) {
					val ids = repository.randomIds(filter)
					randomIds = ids
					pages[0] = repository.cards(ids.take(PAGE_SIZE))
					_state.update { if (it.version == version) it.copy(total = ids.size) else it }
				} else {
					val page = repository.page(filter, 0, PAGE_SIZE)
					pages[0] = page.cards
					_state.update { if (it.version == version) it.copy(total = page.total) else it }
				}
				addSeriesBadges(0, version)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to browse ${filter.title}")
				_state.update { if (it.version == version) it.copy(total = 0, failed = true) else it }
			}
		}
	}

	/** Load the page of an item that became visible */
	fun ensurePage(page: Int) {
		if (pages.containsKey(page) || !loadingPages.add(page)) return
		val filter = _state.value.filter
		val version = _state.value.version
		viewModelScope.launch {
			try {
				val cards = randomIds?.let { ids ->
					repository.cards(ids.drop(page * PAGE_SIZE).take(PAGE_SIZE))
				} ?: repository.page(filter, page * PAGE_SIZE, PAGE_SIZE).cards
				if (_state.value.version == version) {
					pages[page] = cards
					addSeriesBadges(page, version)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Unable to load page $page")
			} finally {
				loadingPages.remove(page)
			}
		}
	}

	fun cardAt(index: Int): BrowseCard? = pages[index / PAGE_SIZE]?.getOrNull(index % PAGE_SIZE)

	/**
	 * Series have no media streams: their quality, HDR, Dolby and audio flags come from one of their
	 * episodes, added to the page once known, with the "New episode/season/series" labels.
	 */
	private fun addSeriesBadges(page: Int, version: Int) {
		val cards = pages[page] ?: return
		val seriesIds = cards.filter { it.item.type == BaseItemKind.SERIES }.map { it.item.id }
		if (seriesIds.isEmpty()) return

		viewModelScope.launch {
			val badges = homeRepository.seriesBadges(seriesIds)
			val labels = runCatching { homeRepository.newContentLabels() }.getOrDefault(emptyMap())
			if (_state.value.version != version) return@launch
			pages[page] = pages[page].orEmpty().map { card ->
				val id = card.item.id
				if (card.item.type != BaseItemKind.SERIES) card
				else card.copy(
					quality = card.quality ?: badges[id]?.first,
					languages = card.languages.ifEmpty { badges[id]?.second.orEmpty() },
					newLabel = labels[id],
				)
			}
		}
	}

	/** Letter of the rail: sorts by name when needed and jumps to the first title with it */
	fun jumpTo(letter: Char) {
		viewModelScope.launch {
			// Sorted by name first, once the new list is there
			if (_state.value.filter.sort != BrowseSort.NAME) {
				setFilter { copy(sort = BrowseSort.NAME) }
				loadJob?.join()
			}
			val filter = _state.value.filter
			val index = runCatching { repository.indexOfLetter(filter, letter) }
				.onFailure { Timber.w(it, "Unable to jump to $letter") }
				.getOrNull() ?: return@launch
			val total = _state.value.total ?: index
			val target = index.coerceAtMost((total - 1).coerceAtLeast(0))
			ensurePage(target / PAGE_SIZE)
			_state.update { it.copy(jumpIndex = target, jumpCount = it.jumpCount + 1) }
		}
	}

	suspend fun randomItem(): BaseItemDto? = runCatching { repository.randomItem(_state.value.filter) }.getOrNull()

	suspend fun resolvePlayableId(item: BaseItemDto): UUID? = homeRepository.resolvePlayableId(item)
}

class ModernBrowseFragment : Fragment() {
	companion object {
		const val ARGUMENT_FILTER = "BrowseFilter"
	}

	private val viewModel by viewModel<ModernBrowseViewModel>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val filter = arguments?.getString(ARGUMENT_FILTER)
			?.let { json -> runCatching { Json.decodeFromString<BrowseFilter>(json) }.getOrNull() }
			?: BrowseFilter(title = getString(R.string.library_browse))
		viewModel.init(filter)
		return content { ModernBrowseScreen(viewModel) }
	}
}

/**
 * Every title of a library (or of a genre, a tag...) in a grid, like the web: watched filter,
 * genres, age rating, sort, popular tags, shuffle, and the # / A-Z rail on the left.
 */
@Composable
fun ModernBrowseScreen(viewModel: ModernBrowseViewModel) {
	val state by viewModel.state.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val posterActions = rememberPosterActionsState()
	val filter = state.filter
	val total = state.total

	val listState = rememberLazyListState(
		initialFirstVisibleItemIndex = viewModel.lastFocusIndex?.let { it / COLUMNS + HEADER_ITEMS } ?: 0,
	)
	val firstFocusRequester = remember { FocusRequester() }
	val tagsFocusRequester = remember { FocusRequester() }
	val cellFocusRequester = remember { FocusRequester() }
	var focusIndex by remember { mutableStateOf(viewModel.lastFocusIndex) }
	// Poster that has the focus (null: filters, tags or letters)
	var focusedIndex by remember { mutableStateOf<Int?>(null) }
	// The letters can only take the focus once the poster of before has it again
	var settled by remember { mutableStateOf(false) }
	var userMoved by remember { mutableStateOf(false) }
	val hasTags = state.tags.isNotEmpty() && !filter.collections
	var currentLetter by remember { mutableStateOf<Char?>(null) }
	var dialog by remember { mutableStateOf<BrowseDialog?>(null) }
	var lastDialog by remember { mutableStateOf<BrowseDialog?>(null) }

	fun openDetails(id: UUID) = navigationRepository.navigate(Destinations.itemDetails(id))
	fun play(item: BaseItemDto) {
		scope.launch {
			val playableId = if (item.type == BaseItemKind.SERIES) viewModel.resolvePlayableId(item) else item.id
			if (playableId != null && item.type != BaseItemKind.BOX_SET) playbackHelper.retrieveAndPlay(playableId, false, context)
			else openDetails(item.id)
		}
	}
	fun showDialog(value: BrowseDialog) {
		lastDialog = value
		dialog = value
	}

	// Focus: the poster focused before, otherwise the first one (the filters when empty). Later
	// filter changes leave the focus where it is
	var initialFocusDone by remember { mutableStateOf(false) }
	LaunchedEffect(total != null) {
		if (total == null || initialFocusDone) return@LaunchedEffect
		initialFocusDone = true
		if (total > 0 && (focusIndex ?: total) >= total) focusIndex = 0

		// Coming back from a title the page fades in, and the system can give the focus to the
		// first element a moment later: check for a while that the poster keeps it
		for (attempt in 0 until FOCUS_ATTEMPTS) {
			awaitFrames(2)
			if (userMoved) break
			if (total == 0) firstFocusRequester.tryRequestFocus()
			else if (focusedIndex == null || focusedIndex != focusIndex) cellFocusRequester.tryRequestFocus()
		}
		settled = true
	}

	fun focusCell(index: Int) {
		focusIndex = index
		scope.launch {
			awaitFrames(1)
			cellFocusRequester.tryRequestFocus()
		}
	}

	// Filters and tags: the page goes back to the very top
	fun onTopFocused() {
		focusedIndex = null
		scope.launch { listState.animateScrollToItem(0) }
	}

	// New filter: back to the top (not when coming back to the page, the position is restored then)
	val shownVersion = remember { state.version }
	LaunchedEffect(state.version) {
		if (state.version == shownVersion) return@LaunchedEffect
		focusIndex = null
		listState.scrollToItem(0)
	}

	// Letter jump
	val shownJump = remember { state.jumpCount }
	LaunchedEffect(state.jumpCount) {
		if (state.jumpCount == shownJump) return@LaunchedEffect
		val index = state.jumpIndex ?: return@LaunchedEffect
		focusIndex = index
		listState.scrollToItem(index / COLUMNS + HEADER_ITEMS)
		awaitFrames(2)
		cellFocusRequester.tryRequestFocus()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(ModernHomeColors.Background)
			.onPreviewKeyEvent { event ->
				if (event.type == KeyEventType.KeyDown) userMoved = true
				false
			},
	) {
		Row(modifier = Modifier.fillMaxSize()) {
			LetterRail(
				current = currentLetter,
				enabled = settled,
				onFocused = { focusedIndex = null },
				onLetter = viewModel::jumpTo,
				modifier = Modifier
					.fillMaxHeight()
					.padding(start = 12.dp),
			)

			ProvideKeepInViewScrolling(margin = 36.dp) {
				LazyColumn(
					state = listState,
					contentPadding = PaddingValues(start = 12.dp, end = ModernHomeDimens.ScreenPaddingHorizontal, top = 24.dp, bottom = 48.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp),
					modifier = Modifier.weight(1f),
				) {
					item(key = "header") {
						BrowseHeader(
							filter = filter,
							total = total,
							firstFocusRequester = firstFocusRequester,
							modifier = Modifier
								.onFocusChanged { if (it.hasFocus) onTopFocused() }
								// Down: the tags (they were skipped), otherwise the posters
								.onPreviewKeyEvent { event ->
									if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
									if (event.type == KeyEventType.KeyDown) {
										when {
											hasTags && tagsFocusRequester.tryRequestFocus() -> Unit
											(total ?: 0) > 0 -> focusCell(0)
										}
									}
									true
								},
							onPlayed = { played -> viewModel.setFilter { copy(played = played) } },
							onGenres = { showDialog(BrowseDialog.GENRES) },
							onRatings = { showDialog(BrowseDialog.RATINGS) },
							onSort = { showDialog(BrowseDialog.SORT) },
							onShuffle = {
								scope.launch { viewModel.randomItem()?.let(::play) }
							},
						)
					}

					item(key = "tags") {
						if (hasTags) {
							TagPills(
								tags = state.tags,
								selected = filter.tag,
								onTag = { tag -> viewModel.setFilter { copy(tag = if (this.tag == tag) null else tag) } },
								modifier = Modifier
									.focusRequester(tagsFocusRequester)
									.onFocusChanged { if (it.hasFocus) onTopFocused() }
									.onPreviewKeyEvent { event ->
										val down = event.type == KeyEventType.KeyDown
										when (event.key) {
											Key.DirectionUp -> {
												if (down) firstFocusRequester.tryRequestFocus()
												true
											}

											Key.DirectionDown -> {
												if (down && (total ?: 0) > 0) focusCell(0)
												true
											}

											else -> false
										}
									}
									.focusRestorer()
									.focusGroup(),
							)
						} else {
							Spacer(Modifier.height(1.dp))
						}
					}

					when {
						total == null -> item(key = "loading") {
							Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
								CircularProgressIndicator(color = ModernHomeColors.Accent, modifier = Modifier.size(42.dp))
							}
						}

						total == 0 -> item(key = "empty") {
							Text(
								text = stringResource(if (state.failed) R.string.modern_details_failed else R.string.browse_empty),
								color = ModernHomeColors.TextSecondary,
								fontSize = 16.sp,
								textAlign = TextAlign.Center,
								modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
							)
						}

						else -> {
							val rows = (total + COLUMNS - 1) / COLUMNS
							items(count = rows, key = { row -> "row_${state.version}_$row" }) { row ->
								Row(
									horizontalArrangement = Arrangement.spacedBy(14.dp),
									// First row, Up: the tags (or the filters)
									modifier = if (row != 0) Modifier else Modifier.onPreviewKeyEvent { event ->
										if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
										if (event.type == KeyEventType.KeyDown) {
											if (!(hasTags && tagsFocusRequester.tryRequestFocus())) firstFocusRequester.tryRequestFocus()
										}
										true
									},
								) {
									for (column in 0 until COLUMNS) {
										val index = row * COLUMNS + column
										if (index >= total) {
											Spacer(Modifier.weight(1f))
											continue
										}
										val card = viewModel.cardAt(index)
										if (card == null) LaunchedEffect(state.version, index / PAGE_SIZE) { viewModel.ensurePage(index / PAGE_SIZE) }

										BrowsePoster(
											card = card,
											focusRequester = if (index == focusIndex) cellFocusRequester else null,
											onFocused = {
												viewModel.lastFocusIndex = index
												focusedIndex = index
												currentLetter = card?.let { letterOf(it.item) }
											},
											onClick = {
												card?.let {
													// Collections: the grid of their movies
													if (it.item.type == BaseItemKind.BOX_SET) navigationRepository.navigate(Destinations.collectionBrowser(it.item))
													else openDetails(it.item.id)
												}
											},
											onLongClick = {
												card?.let {
													posterActions.open(PosterMenuTarget(item = it.item, title = it.title, subtitle = it.subtitle, playsOnClick = false))
												}
											},
											onPlay = { card?.let { play(it.item) } },
											modifier = Modifier.weight(1f),
										)
									}
								}
							}
						}
					}
				}
			}
		}

		// Genres, age ratings and sort
		val shown = lastDialog
		ModernMenuDialog(
			visible = dialog != null,
			title = when (shown) {
				BrowseDialog.GENRES -> stringResource(R.string.browse_genres)
				BrowseDialog.RATINGS -> stringResource(R.string.browse_age_rating)
				BrowseDialog.SORT -> stringResource(R.string.browse_sort)
				null -> ""
			},
			subtitle = null,
			options = when (shown) {
				BrowseDialog.GENRES -> buildList {
					add(ModernMenuOption(stringResource(R.string.browse_all_genres), if (filter.genre == null) R.drawable.ic_check else null) {
						viewModel.setFilter { copy(genre = null, genreNames = emptyList()) }
					})
					for (genre in state.genres) {
						add(ModernMenuOption(genre.name, if (filter.genre == genre.name) R.drawable.ic_check else null) {
							viewModel.setFilter { copy(genre = genre.name, genreNames = genre.names) }
						})
					}
				}

				BrowseDialog.RATINGS -> buildList {
					add(ModernMenuOption(stringResource(R.string.browse_all_ratings), if (filter.officialRating == null) R.drawable.ic_check else null) {
						viewModel.setFilter { copy(officialRating = null) }
					})
					for (rating in state.ratings) {
						add(ModernMenuOption(LibraryBrowseRepository.ratingLabel(rating), if (filter.officialRating == rating) R.drawable.ic_check else null) {
							viewModel.setFilter { copy(officialRating = rating) }
						})
					}
				}

				BrowseDialog.SORT -> BrowseSort.entries.map { sort ->
					ModernMenuOption(stringResource(sort.label), if (filter.sort == sort) R.drawable.ic_check else null) {
						viewModel.setFilter { copy(sort = sort) }
					}
				}

				null -> emptyList()
			},
			onDismissRequest = { dialog = null },
		)

		PosterActionsDialogs(
			state = posterActions,
			onPlay = ::play,
			onOpen = ::openDetails,
			onChanged = {},
		)
	}
}

private enum class BrowseDialog { GENRES, RATINGS, SORT }

private val BrowseSort.label: Int
	get() = when (this) {
		BrowseSort.NAME -> R.string.browse_sort_name
		BrowseSort.RANDOM -> R.string.browse_sort_random
		BrowseSort.DATE_ADDED -> R.string.browse_sort_date_added
		BrowseSort.RELEASE_DATE -> R.string.browse_sort_release_date
		BrowseSort.RATING -> R.string.browse_sort_rating
	}

private val PlayedFilter.label: Int
	get() = when (this) {
		PlayedFilter.ALL -> R.string.browse_all
		PlayedFilter.UNWATCHED -> R.string.browse_unwatched
		PlayedFilter.WATCHED -> R.string.browse_watched
		PlayedFilter.FAVORITES -> R.string.browse_favorites
	}

/** '#' for titles starting with a number or a symbol, otherwise their letter */
private fun letterOf(item: BaseItemDto): Char {
	val first = (item.sortName ?: item.name)?.trim()?.firstOrNull()?.uppercaseChar() ?: return '#'
	return if (first in 'A'..'Z') first else '#'
}

@Composable
private fun BrowseHeader(
	filter: BrowseFilter,
	total: Int?,
	firstFocusRequester: FocusRequester,
	modifier: Modifier = Modifier,
	onPlayed: (PlayedFilter) -> Unit,
	onGenres: () -> Unit,
	onRatings: () -> Unit,
	onSort: () -> Unit,
	onShuffle: () -> Unit,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		Row(verticalAlignment = Alignment.Bottom) {
			Text(
				text = filter.title,
				color = ModernHomeColors.TextPrimary,
				fontSize = 30.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f, fill = false),
			)
			if (total != null && total > 0) {
				Spacer(Modifier.width(12.dp))
				Text(
					text = total.toString(),
					color = ModernHomeColors.TextMuted,
					fontSize = 14.sp,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier.padding(bottom = 5.dp),
				)
			}
		}
		Spacer(Modifier.height(12.dp))

		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = modifier,
		) {
			// All / Unwatched / Watched / Favorites
			Row(
				horizontalArrangement = Arrangement.spacedBy(2.dp),
				modifier = Modifier
					.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
					.padding(3.dp),
			) {
				for (played in PlayedFilter.entries) {
					FilterPill(
						text = stringResource(played.label),
						selected = filter.played == played,
						onClick = { onPlayed(played) },
						modifier = if (played == PlayedFilter.ALL) Modifier.focusRequester(firstFocusRequester) else Modifier,
					)
				}
			}

			Spacer(Modifier.width(4.dp))
			if (!filter.collections) {
				FilterPill(
					text = filter.genre ?: stringResource(R.string.browse_genres),
					icon = R.drawable.ic_filter,
					selected = filter.genre != null,
					onClick = onGenres,
					outlined = true,
				)
				FilterPill(
					text = filter.officialRating?.let(LibraryBrowseRepository::ratingLabel) ?: stringResource(R.string.browse_age_rating),
					icon = R.drawable.ic_age_rating,
					selected = filter.officialRating != null,
					onClick = onRatings,
					outlined = true,
				)
			}
			FilterPill(
				text = stringResource(filter.sort.label),
				icon = R.drawable.ic_sort,
				selected = false,
				onClick = onSort,
				outlined = true,
			)
			FilterPill(
				text = stringResource(R.string.browse_shuffle),
				icon = R.drawable.ic_shuffle,
				selected = false,
				onClick = onShuffle,
				outlined = true,
			)
		}
	}
}

@Composable
internal fun FilterPill(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	icon: Int? = null,
	outlined: Boolean = false,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(8.dp)
	val background = when {
		focused -> Color.White
		selected -> Color.White.copy(alpha = 0.22f)
		outlined -> Color.White.copy(alpha = 0.06f)
		else -> Color.Transparent
	}
	val content = if (focused) Color.Black else if (selected) ModernHomeColors.TextPrimary else ModernHomeColors.TextSecondary

	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(background, shape)
			.then(if (outlined && !focused) Modifier.border(1.dp, ModernHomeColors.GlassBorder, shape) else Modifier)
			.padding(horizontal = 12.dp, vertical = 7.dp),
	) {
		if (icon != null) {
			Icon(ImageVector.vectorResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
			Spacer(Modifier.width(6.dp))
		}
		Text(text = text, color = content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
	}
}

@Composable
private fun TagPills(
	tags: List<String>,
	selected: String?,
	onTag: (String) -> Unit,
	modifier: Modifier = Modifier,
) {
	LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
		items(tags, key = { it }) { tag ->
			TagPill(text = tag, selected = tag == selected, onClick = { onTag(tag) })
		}
	}
}

/** Round pill of a tag (browse page, search) */
@Composable
internal fun TagPill(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "TagScale")

	Text(
		text = text,
		color = if (focused) Color.Black else ModernHomeColors.TextPrimary,
		fontSize = 12.sp,
		fontWeight = FontWeight.SemiBold,
		maxLines = 1,
		modifier = modifier
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.background(
				when {
					focused -> Color.White
					selected -> ModernHomeColors.Accent.copy(alpha = 0.55f)
					else -> Color.White.copy(alpha = 0.1f)
				},
				CircleShape,
			)
			.padding(horizontal = 14.dp, vertical = 7.dp),
	)
}

@Composable
private fun BrowsePoster(
	card: BrowseCard?,
	focusRequester: FocusRequester?,
	onFocused: () -> Unit,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onPlay: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier) {
		FocusableCardSurface(
			onClick = onClick,
			onLongClick = onLongClick,
			onFocused = onFocused,
			focusRequester = focusRequester,
			onPlay = onPlay,
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(2f / 3f),
		) { focused ->
			if (card != null) {
				CardArtwork(url = card.imageUrl, fallbackTitle = card.title)
				PosterBadges(card = card, focused = focused)
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(
			text = card?.title.orEmpty(),
			color = ModernHomeColors.TextPrimary,
			fontSize = 12.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = card?.subtitle.orEmpty(),
			color = ModernHomeColors.TextMuted,
			fontSize = 11.sp,
			maxLines = 1,
		)
	}
}

/** Quality (top left), audio flags (bottom left) and "New episode..." (bottom), like on Home */
@Composable
private fun BoxScope.PosterBadges(card: BrowseCard, focused: Boolean) {
	val quality = card.quality?.takeIf { !it.isEmpty }
	if (quality != null) {
		Box(
			modifier = Modifier
				.align(Alignment.TopCenter)
				.fillMaxWidth()
				.height(40.dp)
				.background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent))
		)
		QualityBadges(
			quality = quality,
			modifier = Modifier
				.align(Alignment.TopStart)
				.padding(start = 5.dp, top = 5.dp, end = 5.dp),
		)
	}

	val newLabel = card.newLabel?.takeIf { !focused }
	if (newLabel != null) NewContentBadge(label = newLabel, modifier = Modifier.align(Alignment.BottomCenter))

	LanguageFlags(
		languages = card.languages,
		modifier = Modifier
			.align(Alignment.BottomStart)
			.padding(start = 5.dp, bottom = if (newLabel != null) 23.dp else 5.dp),
	)
}

/** # and A-Z: OK jumps to the first title with that letter (sorting by name) */
@Composable
private fun LetterRail(
	current: Char?,
	enabled: Boolean,
	onFocused: () -> Unit,
	onLetter: (Char) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		verticalArrangement = Arrangement.SpaceEvenly,
		horizontalAlignment = Alignment.CenterHorizontally,
		modifier = modifier.padding(vertical = 14.dp),
	) {
		for (letter in LETTERS) {
			val interactionSource = remember { MutableInteractionSource() }
			val focused by interactionSource.collectIsFocusedAsState()
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier
					.size(width = 26.dp, height = 17.dp)
					.onFocusChanged { if (it.isFocused) onFocused() }
					.focusProperties { canFocus = enabled }
					.clickable(interactionSource = interactionSource, indication = null) { onLetter(letter) }
					.background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(5.dp)),
			) {
				Text(
					text = letter.toString(),
					color = when {
						focused -> Color.Black
						letter == current -> ModernHomeColors.Accent
						else -> ModernHomeColors.TextMuted
					},
					fontSize = 11.sp,
					fontWeight = if (letter == current || focused) FontWeight.Bold else FontWeight.SemiBold,
				)
			}
		}
	}
}
