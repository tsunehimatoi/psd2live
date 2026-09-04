package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@Composable
fun ImageLightboxDialog(
	imageBytes: ByteArray,
	title: String? = null,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val bufferedImage: BufferedImage? = remember(imageBytes) {
		runCatching { ImageIO.read(ByteArrayInputStream(imageBytes)) }.getOrNull()
	}
	val bitmap = remember(bufferedImage) {
		bufferedImage?.toComposeImageBitmap()
	}

	fun copyImageToClipboard() {
		if (bufferedImage == null) return
		val transferable = object : Transferable {
			override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
			override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
			override fun getTransferData(flavor: DataFlavor): Any {
				if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
				return bufferedImage
			}
		}
		Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0xCC000000))
			.clickable(onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.widthIn(min = 360.dp, max = 800.dp)
				.heightIn(min = 280.dp, max = 680.dp)
				.background(colors.panelBackground, RoundedCornerShape(8.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
				.clickable(enabled = false) {}
				.padding(14.dp),
		) {
			// Title Bar
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Column {
					Text(
						text = title ?: "Image Preview",
						style = typography.title.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)
					if (bufferedImage != null) {
						Text(
							text = "${bufferedImage.width} × ${bufferedImage.height} px · ${(imageBytes.size / 1024).coerceAtLeast(1)} KB",
							style = typography.caption.copy(fontSize = 10.sp),
							color = colors.textMuted,
						)
					}
				}
				CompactIconButton(
					onClick = onDismiss,
					size = 22.dp,
				) {
					IconClose(tint = colors.textMuted)
				}
			}

			Spacer(Modifier.height(10.dp))

			// Image Container with Checkerboard Background
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.background(Color(0xFF18181B), RoundedCornerShape(4.dp))
					.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)),
				contentAlignment = Alignment.Center,
			) {
				CheckerboardBackground(modifier = Modifier.fillMaxSize())

				if (bitmap != null) {
					Image(
						bitmap = bitmap,
						contentDescription = title ?: "Preview",
						modifier = Modifier
							.fillMaxSize()
							.padding(8.dp),
						alignment = Alignment.Center,
					)
				} else {
					Text(
						text = "Failed to decode image",
						style = typography.body.copy(fontSize = 12.sp),
						color = colors.error,
					)
				}
			}

			Spacer(Modifier.height(12.dp))

			// Bottom Actions
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactButton(
					text = "Copy Image",
					onClick = ::copyImageToClipboard,
					height = 24.dp,
				)
				CompactButton(
					text = tr("dialog.ok"),
					onClick = onDismiss,
					isPrimary = true,
					height = 24.dp,
				)
			}
		}
	}
}

@Composable
fun CheckerboardBackground(
	modifier: Modifier = Modifier,
	squareSizePx: Float = 16f,
	lightColor: Color = Color(0xFF222226),
	darkColor: Color = Color(0xFF19191D),
) {
	Canvas(modifier = modifier) {
		val cols = (size.width / squareSizePx).toInt() + 1
		val rows = (size.height / squareSizePx).toInt() + 1
		for (r in 0 until rows) {
			for (c in 0 until cols) {
				val isLight = (r + c) % 2 == 0
				drawRect(
					color = if (isLight) lightColor else darkColor,
					topLeft = Offset(c * squareSizePx, r * squareSizePx),
					size = Size(squareSizePx, squareSizePx),
				)
			}
		}
	}
}

