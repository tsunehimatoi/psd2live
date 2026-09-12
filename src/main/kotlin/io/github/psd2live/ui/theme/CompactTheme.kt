package io.github.psd2live.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Immutable
data class ToolColors(
	val windowBackground: Color = Color(0xFF1E1F22),
	val panelBackground: Color = Color(0xFF2B2D30),
	val panelElevated: Color = Color(0xFF323438),
	val inputBackground: Color = Color(0xFF1E1F22),
	val controlBackground: Color = Color(0xFF393B40),
	val controlHover: Color = Color(0xFF45484F),
	val controlActive: Color = Color(0xFF4E5158),
	val border: Color = Color(0xFF3E4147),
	val borderHover: Color = Color(0xFF5E626B),
	val divider: Color = Color(0xFF36383D),
	val accent: Color = Color(0xFF4B7EE8),
	val accentHover: Color = Color(0xFF5C8FF0),
	val accentText: Color = Color(0xFFFFFFFF),
	val selection: Color = Color(0xFF2E436E),
	val selectionText: Color = Color(0xFFA8C7FA),
	val textPrimary: Color = Color(0xFFDFE1E5),
	val textMuted: Color = Color(0xFF868A91),
	val textDisabled: Color = Color(0xFF5A5D63),
	val success: Color = Color(0xFF57A64A),
	val warning: Color = Color(0xFFD69D36),
	val error: Color = Color(0xFFE05252),
	val checkerLight: Color = Color(0xFF3A3D42),
	val checkerDark: Color = Color(0xFF303236),
)

@Immutable
data class ToolTypography(
	val title: TextStyle = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.SemiBold,
		fontSize = 13.5.sp,
		color = Color(0xFFDFE1E5),
	),
	val header: TextStyle = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Medium,
		fontSize = 12.5.sp,
		color = Color(0xFFDFE1E5),
	),
	val body: TextStyle = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Normal,
		fontSize = 12.5.sp,
		color = Color(0xFFDFE1E5),
	),
	val caption: TextStyle = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Normal,
		fontSize = 11.5.sp,
		color = Color(0xFF868A91),
	),
	val mono: TextStyle = TextStyle(
		fontFamily = FontFamily.Monospace,
		fontWeight = FontWeight.Normal,
		fontSize = 11.5.sp,
		color = Color(0xFFDFE1E5),
	),
	val monoSmall: TextStyle = TextStyle(
		fontFamily = FontFamily.Monospace,
		fontWeight = FontWeight.Normal,
		fontSize = 10.5.sp,
		color = Color(0xFF868A91),
	),
)

val LocalToolColors = staticCompositionLocalOf { ToolColors() }
val LocalToolTypography = staticCompositionLocalOf { ToolTypography() }

@Composable
fun CompactToolTheme(
	colors: ToolColors = ToolColors(),
	typography: ToolTypography = ToolTypography(),
	uiScale: Float = 1.0f,
	fontScale: Float = 1.0f,
	content: @Composable () -> Unit,
) {
	val currentDensity = LocalDensity.current
	val effectiveDensity = remember(currentDensity, uiScale, fontScale) {
		Density(
			density = currentDensity.density * uiScale,
			fontScale = currentDensity.fontScale * fontScale,
		)
	}
	CompositionLocalProvider(
		LocalDensity provides effectiveDensity,
		LocalToolColors provides colors,
		LocalToolTypography provides typography,
		content = content,
	)
}


