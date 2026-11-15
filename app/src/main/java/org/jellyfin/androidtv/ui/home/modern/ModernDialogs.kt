package org.jellyfin.androidtv.ui.home.modern

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.dialog.DialogBase

private val dialogShape = RoundedCornerShape(18.dp)
private val MENU_MAX_HEIGHT = 330.dp

/**
 * Dark glass panel used by the modern screens for small dialogs.
 */
@Composable
internal fun ModernDialog(
	visible: Boolean,
	title: String,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	maxWidth: Dp = 520.dp,
	content: @Composable ColumnScope.() -> Unit,
) {
	// Dialogs opened by holding OK: the key is still down when the dialog appears, so its repeats
	// and its release must not select the first option. Only a new press counts
	var armed by remember(visible) { mutableStateOf(false) }

	DialogBase(
		visible = visible,
		onDismissRequest = onDismissRequest,
	) {
		Column(
			modifier = modifier
				.onPreviewKeyEvent { event ->
					if (armed || !event.isConfirmKey) return@onPreviewKeyEvent false
					if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
						armed = true
						false
					} else {
						// Repeats and the release of the press that opened the dialog
						if (event.type == KeyEventType.KeyUp) armed = true
						true
					}
				}
				.widthIn(min = 320.dp, max = maxWidth)
				.background(Color(0xF2121722), dialogShape)
				.border(1.dp, ModernHomeColors.GlassBorder, dialogShape)
				.padding(28.dp),
		) {
			Text(
				text = title,
				color = ModernHomeColors.TextPrimary,
				fontSize = 22.sp,
				fontWeight = FontWeight.Bold,
			)
			Spacer(Modifier.height(12.dp))
			content()
		}
	}
}

/**
 * Confirmation dialog with a primary and a secondary button.
 */
@Composable
internal fun ModernConfirmDialog(
	visible: Boolean,
	title: String,
	message: String,
	confirmText: String,
	dismissText: String,
	onConfirm: () -> Unit,
	onDismissRequest: () -> Unit,
) {
	ModernDialog(
		visible = visible,
		title = title,
		onDismissRequest = onDismissRequest,
	) {
		Text(
			text = message,
			color = ModernHomeColors.TextSecondary,
			fontSize = 14.sp,
			lineHeight = 20.sp,
		)
		Spacer(Modifier.height(24.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			HeroButton(text = confirmText, icon = null, primary = true, onClick = onConfirm)
			HeroButton(text = dismissText, icon = null, primary = false, onClick = onDismissRequest)
		}
	}
}

/**
 * Option of a [ModernMenuDialog].
 */
internal data class ModernMenuOption(
	val text: String,
	val icon: Int?,
	val onClick: () -> Unit,
)

/**
 * Pop-up menu with a list of options, e.g. when holding OK on a "Continue watching" card.
 * The dialog closes before the selected option runs.
 */
@Composable
internal fun ModernMenuDialog(
	visible: Boolean,
	title: String,
	subtitle: String?,
	options: List<ModernMenuOption>,
	onDismissRequest: () -> Unit,
) {
	ModernDialog(
		visible = visible,
		title = title,
		onDismissRequest = onDismissRequest,
	) {
		if (subtitle != null) {
			Text(
				text = subtitle,
				color = ModernHomeColors.TextSecondary,
				fontSize = 14.sp,
				maxLines = 1,
			)
			Spacer(Modifier.height(16.dp))
		}

		// Long lists (genres, age ratings...) scroll with the focus
		LazyColumn(
			verticalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT),
		) {
			items(options) { option ->
				MenuOptionRow(
					option = option,
					onClick = {
						onDismissRequest()
						option.onClick()
					},
				)
			}
		}
	}
}

@Composable
private fun MenuOptionRow(
	option: ModernMenuOption,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val contentColor = if (focused) Color.Black else ModernHomeColors.TextPrimary
	val shape = RoundedCornerShape(12.dp)

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.background(if (focused) Color.White else Color.White.copy(alpha = 0.06f), shape)
			.padding(horizontal = 18.dp, vertical = 13.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (option.icon != null) {
			Icon(
				imageVector = ImageVector.vectorResource(option.icon),
				contentDescription = null,
				tint = contentColor,
				modifier = Modifier.size(20.dp),
			)
			Spacer(Modifier.width(14.dp))
		}

		Text(
			text = option.text,
			color = contentColor,
			fontSize = 16.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
		)
	}
}

private val KeyEvent.isConfirmKey: Boolean
	get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
