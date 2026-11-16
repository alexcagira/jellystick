package org.jellyfin.androidtv.ui.collections

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.home.modern.EditableField
import org.jellyfin.androidtv.ui.home.modern.HeroButton
import org.jellyfin.androidtv.ui.home.modern.ModernDialog
import org.jellyfin.androidtv.ui.home.modern.ModernHomeColors
import org.jellyfin.androidtv.ui.home.modern.awaitFrames
import org.jellyfin.androidtv.ui.home.modern.tryRequestFocus
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject
import timber.log.Timber

private sealed interface ServiceStep {
	data object Reading : ServiceStep
	data class Services(val scan: ServiceScan, val rules: Map<String, ServiceCollectionRule>) : ServiceStep
	data class Configure(val scan: ServiceScan, val rules: Map<String, ServiceCollectionRule>, val service: StreamingServices.Service) : ServiceStep
	data class Working(val update: Boolean) : ServiceStep
	data object Failed : ServiceStep
}

/**
 * Collections of a TV show library by streaming service (Netflix, HBO Max, Prime Video...) or TV
 * network, from the networks in the metadata of the series. [onChanged] after making one.
 */
@Composable
fun ServiceCollectionsDialog(
	visible: Boolean,
	library: BaseItemDto,
	onDismissRequest: () -> Unit,
	onChanged: () -> Unit = {},
) {
	val repository = koinInject<ServiceCollectionsRepository>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var step by remember(visible) { mutableStateOf<ServiceStep>(ServiceStep.Reading) }
	val kindName = stringResource(R.string.language_collections_kind_series)

	LaunchedEffect(visible) {
		if (!visible) return@LaunchedEffect
		step = try {
			ServiceStep.Services(repository.scan(library), repository.rulesFor(library.id))
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.w(err, "Unable to read the networks of ${library.name}")
			ServiceStep.Failed
		}
	}

	fun finish(configure: ServiceStep.Configure, name: String, poster: CollectionPoster) {
		val rule = configure.rules[configure.service.key]
		step = ServiceStep.Working(update = rule != null)
		scope.launch {
			val message = try {
				val updated = rule != null && repository.update(rule, configure.scan, poster, kindName)
				if (updated) context.getString(R.string.language_collections_updated, rule!!.name)
				else {
					repository.create(library, configure.scan, configure.service, name, poster, kindName)
					context.getString(R.string.language_collections_created, name)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: PosterUploadException) {
				context.getString(R.string.language_collections_poster_failed)
			} catch (err: Exception) {
				Timber.w(err, "Unable to make the service collection $name")
				context.getString(R.string.language_collections_failed)
			}
			Toast.makeText(context, message, Toast.LENGTH_LONG).show()
			onChanged()
			onDismissRequest()
		}
	}

	ModernDialog(
		visible = visible,
		title = stringResource(R.string.service_collections_title),
		// Back on the poster choice goes back to the services
		onDismissRequest = {
			when (val current = step) {
				is ServiceStep.Configure -> step = ServiceStep.Services(current.scan, current.rules)
				is ServiceStep.Working -> Unit
				else -> onDismissRequest()
			}
		},
		maxWidth = 860.dp,
	) {
		when (val current = step) {
			ServiceStep.Reading -> CollectionProgress(stringResource(R.string.service_collections_reading))

			is ServiceStep.Working -> CollectionProgress(
				stringResource(if (current.update) R.string.language_collections_updating else R.string.language_collections_creating)
			)

			ServiceStep.Failed -> Text(
				text = stringResource(R.string.language_collections_failed),
				color = ModernHomeColors.TextSecondary,
				fontSize = 15.sp,
			)

			is ServiceStep.Services -> ServiceList(
				step = current,
				onSelect = { service -> step = ServiceStep.Configure(current.scan, current.rules, service) },
			)

			is ServiceStep.Configure -> ConfigureServiceCollection(
				step = current,
				kindName = kindName,
				onConfirm = { name, poster -> finish(current, name, poster) },
			)
		}
	}
}

@Composable
private fun ServiceList(
	step: ServiceStep.Services,
	onSelect: (StreamingServices.Service) -> Unit,
) {
	Text(
		text = stringResource(R.string.service_collections_hint),
		color = ModernHomeColors.TextSecondary,
		fontSize = 14.sp,
	)
	Spacer(Modifier.height(14.dp))

	if (step.scan.services.isEmpty()) {
		Text(text = stringResource(R.string.service_collections_none), color = ModernHomeColors.TextMuted, fontSize = 14.sp)
		return
	}

	val (streaming, networks) = remember(step.scan) { step.scan.services.partition { it.streaming } }

	LazyColumn(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.heightIn(max = 430.dp),
	) {
		if (streaming.isNotEmpty()) {
			item(key = "streaming_header") { ListHeader(stringResource(R.string.service_collections_streaming)) }
			items(streaming, key = { it.key }) { service -> ServiceRow(service, step, onSelect) }
		}
		if (networks.isNotEmpty()) {
			item(key = "networks_header") { ListHeader(stringResource(R.string.service_collections_networks)) }
			items(networks, key = { it.key }) { service -> ServiceRow(service, step, onSelect) }
		}
	}
}

@Composable
private fun ListHeader(text: String) {
	Text(
		text = text.uppercase(),
		color = ModernHomeColors.TextMuted,
		fontSize = 11.sp,
		fontWeight = FontWeight.Bold,
		letterSpacing = 1.sp,
		modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
	)
}

@Composable
private fun ServiceRow(
	service: StreamingServices.Service,
	step: ServiceStep.Services,
	onSelect: (StreamingServices.Service) -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val shape = RoundedCornerShape(12.dp)
	val count = step.scan.seriesByService[service.key].orEmpty().size

	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(interactionSource = interactionSource, indication = null, onClick = { onSelect(service) })
			.background(if (focused) Color.White else Color.White.copy(alpha = 0.06f), shape)
			.padding(horizontal = 18.dp, vertical = 12.dp),
	) {
		Text(
			text = service.name,
			color = if (focused) Color.Black else ModernHomeColors.TextPrimary,
			fontSize = 16.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		if (service.key in step.rules) {
			Text(
				text = stringResource(R.string.language_collections_created_badge),
				color = if (focused) Color.Black else ModernHomeColors.Accent,
				fontSize = 12.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
			)
			Spacer(Modifier.width(14.dp))
		}
		Text(
			text = seriesCount(count),
			color = if (focused) Color.Black.copy(alpha = 0.7f) else ModernHomeColors.TextMuted,
			fontSize = 13.sp,
			maxLines = 1,
		)
	}
}

@Composable
private fun ConfigureServiceCollection(
	step: ServiceStep.Configure,
	kindName: String,
	onConfirm: (name: String, poster: CollectionPoster) -> Unit,
) {
	val api = koinInject<ApiClient>()
	val imageLoader = koinInject<ImageLoader>()
	val service = step.service
	val rule = step.rules[service.key]
	val defaultName = stringResource(R.string.service_collections_default_name, service.name)
	var name by remember(service.key) { mutableStateOf(rule?.name ?: defaultName) }
	var poster by remember(service.key) { mutableStateOf(if (rule != null) CollectionPoster.Keep else CollectionPoster.Generated) }
	val confirmFocusRequester = remember { FocusRequester() }

	val generated = remember(service.key, kindName) { LanguagePoster.drawService(service.name, kindName) }
	val seriesPosters = remember(step.scan, service.key) {
		step.scan.seriesByService[service.key].orEmpty()
			.mapNotNull { id -> step.scan.items[id] }
			.mapNotNull { item -> item.itemImages[ImageType.PRIMARY]?.let { image -> item.id to image.getUrl(api, maxWidth = 260) } }
			.take(MAX_POSTER_CHOICES)
	}

	LaunchedEffect(service.key) {
		awaitFrames(2)
		confirmFocusRequester.tryRequestFocus()
	}

	Text(
		text = "${service.name} · ${seriesCount(step.scan.seriesByService[service.key].orEmpty().size)}",
		color = ModernHomeColors.TextSecondary,
		fontSize = 14.sp,
	)
	Spacer(Modifier.height(14.dp))

	Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		if (rule == null) {
			EditableField(
				label = stringResource(R.string.language_collections_name),
				value = name,
				onValueChange = { name = it },
				onSubmit = {},
				modifier = Modifier.weight(1f),
			)
		} else {
			// Existing collection: renaming is done in the web app
			Text(
				text = rule.name,
				color = ModernHomeColors.TextPrimary,
				fontSize = 18.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
		}
		HeroButton(
			text = stringResource(if (rule != null) R.string.language_collections_update else R.string.language_collections_create),
			icon = R.drawable.ic_check,
			primary = true,
			onClick = { onConfirm(name.trim().ifEmpty { defaultName }, poster) },
			modifier = Modifier.focusRequester(confirmFocusRequester),
		)
	}

	Spacer(Modifier.height(18.dp))
	Text(
		text = stringResource(R.string.language_collections_poster),
		color = ModernHomeColors.TextMuted,
		fontSize = 11.sp,
		fontWeight = FontWeight.SemiBold,
	)
	Spacer(Modifier.height(6.dp))

	LazyRow(
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
	) {
		if (rule != null) {
			item(key = "keep") {
				PosterChoice(
					label = stringResource(R.string.language_collections_poster_keep),
					selected = poster == CollectionPoster.Keep,
					onClick = { poster = CollectionPoster.Keep },
				) {
					AsyncImage(
						model = api.imageApi.getItemImageUrl(itemId = rule.collectionId, imageType = ImageType.PRIMARY, maxWidth = 260),
						contentDescription = null,
						imageLoader = imageLoader,
						contentScale = ContentScale.Crop,
						modifier = Modifier.fillMaxSize(),
					)
				}
			}
		}
		item(key = "generated") {
			PosterChoice(
				label = stringResource(R.string.service_collections_poster_generated),
				selected = poster == CollectionPoster.Generated,
				onClick = { poster = CollectionPoster.Generated },
			) {
				Image(bitmap = generated, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
			}
		}
		items(seriesPosters, key = { it.first.toString() }) { (itemId, url) ->
			PosterChoice(
				label = step.scan.items[itemId]?.name.orEmpty(),
				selected = (poster as? CollectionPoster.FromItem)?.itemId == itemId,
				onClick = { poster = CollectionPoster.FromItem(itemId) },
			) {
				AsyncImage(
					model = url,
					contentDescription = null,
					imageLoader = imageLoader,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}
}

@Composable
private fun seriesCount(count: Int): String =
	LocalContext.current.resources.getQuantityString(R.plurals.language_collections_count_series, count, count)

private const val MAX_POSTER_CHOICES = 40
