package cn.tonglu.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Compatibility colors for remaining Material widgets; AppColors stays the single source. */
internal fun appMaterialColors(): ColorScheme = appMaterialColors(
    AppTheme.darkState, AppColors.CanvasOpaque, AppColors.SurfaceOpaque, AppColors.ChipIdle,
    AppColors.Accent, AppColors.OnAccent, AppColors.TextPrimary, AppColors.TextSecondary,
    AppColors.Divider, AppColors.Danger,
)

internal fun appMaterialColors(dark: Boolean, canvas: Color, surface: Color, chip: Color,
    accent: Color, onAccent: Color, text: Color, secondaryText: Color, divider: Color, danger: Color): ColorScheme {
    val accentContainer = accent.copy(alpha = .12f).compositeOver(surface)
    val errorContainer = danger.copy(alpha = .12f).compositeOver(surface)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentContainer, onPrimaryContainer = text,
        secondary = accent, onSecondary = onAccent, secondaryContainer = chip, onSecondaryContainer = text,
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = accentContainer, onTertiaryContainer = text,
        background = canvas, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = chip, onSurfaceVariant = secondaryText,
        surfaceTint = Color.Transparent, outline = secondaryText, outlineVariant = divider,
        surfaceContainerLowest = canvas, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = chip, surfaceDim = canvas, surfaceBright = surface,
        inverseSurface = text, inverseOnSurface = surface, inversePrimary = accent,
        error = danger, onError = onAccent, errorContainer = errorContainer, onErrorContainer = text,
        primaryFixed = accent, primaryFixedDim = accent, onPrimaryFixed = onAccent, onPrimaryFixedVariant = onAccent,
        secondaryFixed = accent, secondaryFixedDim = accent, onSecondaryFixed = onAccent, onSecondaryFixedVariant = onAccent,
        tertiaryFixed = accent, tertiaryFixedDim = accent, onTertiaryFixed = onAccent, onTertiaryFixedVariant = onAccent,
    )
}
