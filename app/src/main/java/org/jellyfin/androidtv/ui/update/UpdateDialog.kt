package org.jellyfin.androidtv.ui.update

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun UpdateDialog(
	viewModel: UpdateDialogViewModel,
	onDismiss: () -> Unit
) {
	val state by viewModel.state.collectAsState()

	if (state.isVisible) {
		Dialog(
			onDismissRequest = {},
			properties = DialogProperties(
				usePlatformDefaultWidth = false,
				dismissOnBackPress = false,
				dismissOnClickOutside = false,
			),
		) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = 0.7f)),
				contentAlignment = Alignment.Center,
			) {
				UpdateDialogContent(
					state = state,
					onInstall = { viewModel.startUpdate() },
					onDismiss = {
						viewModel.dismiss()
						onDismiss()
					},
				)
			}
		}
	}
}

@Composable
private fun UpdateDialogContent(
	state: UpdateDialogState,
	onInstall: () -> Unit,
	onDismiss: () -> Unit
) {
	val installButtonFocusRequester = remember { FocusRequester() }
	val cancelButtonFocusRequester = remember { FocusRequester() }
	val okButtonFocusRequester = remember { FocusRequester() }

	LaunchedEffect(state.updatePhase) {
		when (state.updatePhase) {
			UpdatePhase.AVAILABLE -> installButtonFocusRequester.requestFocus()
			UpdatePhase.UP_TO_DATE, UpdatePhase.ERROR -> okButtonFocusRequester.requestFocus()
			else -> Unit
		}
	}

	Column(
		modifier = Modifier
			.width(600.dp)
			.background(
				color = Color(0xFF1E1E1E),
				shape = RoundedCornerShape(16.dp)
			)
			.padding(32.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		// Titel
		Text(
			text = when (state.updatePhase) {
				UpdatePhase.CHECKING -> stringResource(R.string.jellyseerr_update_checking)
				UpdatePhase.AVAILABLE -> stringResource(R.string.jellyseerr_update_available_title)
				UpdatePhase.DOWNLOADING -> stringResource(R.string.jellyseerr_update_downloading)
				UpdatePhase.INSTALLING -> stringResource(R.string.jellyseerr_update_installing)
				UpdatePhase.UP_TO_DATE -> stringResource(R.string.jellyseerr_update_current_title)
				UpdatePhase.ERROR -> stringResource(R.string.jellyseerr_update_error_title)
			},
			fontSize = 28.sp,
			color = Color.White,
			textAlign = TextAlign.Center
		)

		Spacer(modifier = Modifier.height(24.dp))

		// Content basierend auf Phase
		when (state.updatePhase) {
			UpdatePhase.CHECKING -> {
				SimpleCircularProgressIndicator(
					modifier = Modifier.padding(32.dp).size(48.dp),
					color = Color(0xFF00A4DC)
				)
			}

			UpdatePhase.AVAILABLE -> {
				state.releaseInfo?.let { info ->
					Column(
						modifier = Modifier.fillMaxWidth(),
						horizontalAlignment = Alignment.CenterHorizontally
					) {
						Text(
							text = stringResource(R.string.jellyseerr_update_available_message, info.version),
							fontSize = 20.sp,
							color = Color.White.copy(alpha = 0.9f),
							textAlign = TextAlign.Center
						)

						info.changelog?.let { changelog ->
							Spacer(modifier = Modifier.height(16.dp))
							Text(
								text = changelog.take(300) + if (changelog.length > 300) "..." else "",
								fontSize = 16.sp,
								color = Color.White.copy(alpha = 0.7f),
								textAlign = TextAlign.Start,
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 8.dp)
							)
						}
					}
				}
			}

			UpdatePhase.DOWNLOADING -> {
				Column(
					modifier = Modifier.fillMaxWidth(),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					if (state.downloadProgress in 0f..1f) {
						SimpleLinearProgressIndicator(
							progress = state.downloadProgress,
							modifier = Modifier
								.fillMaxWidth()
								.height(8.dp),
							color = Color(0xFF00A4DC),
							trackColor = Color.White.copy(alpha = 0.2f)
						)
						Spacer(modifier = Modifier.height(16.dp))
						Text(
							text = "${(state.downloadProgress * 100).toInt()}%",
							fontSize = 20.sp,
							color = Color.White
						)
					} else {
						SimpleCircularProgressIndicator(
							modifier = Modifier.padding(32.dp).size(48.dp),
							color = Color(0xFF00A4DC)
						)
					}
				}
			}

			UpdatePhase.INSTALLING -> {
				Column(
					modifier = Modifier.fillMaxWidth(),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					SimpleCircularProgressIndicator(
						modifier = Modifier.padding(32.dp).size(48.dp),
						color = Color(0xFF00A4DC)
					)
					Text(
						text = stringResource(R.string.jellyseerr_update_installing_message),
						fontSize = 18.sp,
						color = Color.White.copy(alpha = 0.8f),
						textAlign = TextAlign.Center
					)
				}
			}

			UpdatePhase.UP_TO_DATE -> {
				Text(
					text = stringResource(R.string.jellyseerr_update_current_message),
					fontSize = 20.sp,
					color = Color.White.copy(alpha = 0.9f),
					textAlign = TextAlign.Center
				)
			}

			UpdatePhase.ERROR -> {
				state.errorMessage?.let { error ->
					Text(
						text = error,
						fontSize = 18.sp,
						color = Color(0xFFFF6B6B),
						textAlign = TextAlign.Center
					)
				}
			}
		}

		Spacer(modifier = Modifier.height(32.dp))

		// Buttons
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.Center,
			verticalAlignment = Alignment.CenterVertically
		) {
			when (state.updatePhase) {
				UpdatePhase.AVAILABLE -> {
					SimpleButton(
						text = stringResource(R.string.lbl_cancel),
						onClick = onDismiss,
						modifier = Modifier.focusRequester(cancelButtonFocusRequester),
						backgroundColor = Color.Transparent,
						contentColor = Color.White
					)

					Spacer(modifier = Modifier.width(16.dp))

					SimpleButton(
						text = stringResource(R.string.jellyseerr_update_install_button),
						onClick = onInstall,
						modifier = Modifier.focusRequester(installButtonFocusRequester),
						backgroundColor = Color(0xFF00A4DC),
						contentColor = Color.White
					)
				}

				UpdatePhase.UP_TO_DATE, UpdatePhase.ERROR -> {
					SimpleButton(
						text = stringResource(R.string.lbl_ok),
						onClick = onDismiss,
						modifier = Modifier.focusRequester(okButtonFocusRequester),
						backgroundColor = Color(0xFF00A4DC),
						contentColor = Color.White
					)
				}

				UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING -> {
					// Keine Buttons während dieser Phasen
				}
			}
		}
	}
}

@Composable
private fun SimpleButton(
	text: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	backgroundColor: Color = Color.Transparent,
	contentColor: Color = Color.White
) {
	val interactionSource = remember { MutableInteractionSource() }
	val isFocused by interactionSource.collectIsFocusedAsState()

	Box(
		modifier = modifier
			.clip(RoundedCornerShape(8.dp))
			.background(if (isFocused) backgroundColor.copy(alpha = 0.8f) else backgroundColor)
			.border(
				width = if (isFocused) 3.dp else 1.dp,
				color = if (isFocused) Color.White else contentColor.copy(alpha = 0.5f),
				shape = RoundedCornerShape(8.dp)
			)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick
			)
			.focusable(interactionSource = interactionSource)
			.padding(horizontal = 24.dp, vertical = 12.dp),
		contentAlignment = Alignment.Center
	) {
		Text(
			text = text,
			fontSize = 18.sp,
			color = contentColor
		)
	}
}

@Composable
private fun SimpleLinearProgressIndicator(
	progress: Float,
	modifier: Modifier = Modifier,
	color: Color = Color(0xFF00A4DC),
	trackColor: Color = Color.White.copy(alpha = 0.2f)
) {
	Box(
		modifier = modifier
			.clip(RoundedCornerShape(4.dp))
			.background(trackColor)
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(progress.coerceIn(0f, 1f))
				.height(8.dp)
				.background(color)
		)
	}
}

@Composable
private fun SimpleCircularProgressIndicator(
	modifier: Modifier = Modifier,
	color: Color = Color(0xFF00A4DC)
) {
	// Einfacher rotierender Kreis
	Canvas(modifier = modifier) {
		val canvasWidth = size.width
		val canvasHeight = size.height
		val radius = minOf(canvasWidth, canvasHeight) / 2f - 4.dp.toPx()

		// Zeichne einen rotierenden Bogen
		drawArc(
			color = color,
			startAngle = 0f,
			sweepAngle = 270f,
			useCenter = false,
			style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
			topLeft = Offset(
				x = (canvasWidth - radius * 2) / 2,
				y = (canvasHeight - radius * 2) / 2
			),
			size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
		)
	}
}
