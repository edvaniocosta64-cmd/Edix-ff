package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val InfinitColorScheme =
  darkColorScheme(
    primary = HollowPurpleBright,
    onPrimary = VoidObsidian,
    primaryContainer = HollowPurpleDeep,
    onPrimaryContainer = ColdWhite,
    secondary = LimitlessCyan,
    onSecondary = VoidObsidian,
    secondaryContainer = VoidSurfaceElevated,
    onSecondaryContainer = LimitlessCyan,
    tertiary = ReversalRed,
    onTertiary = ColdWhite,
    tertiaryContainer = VoidSurfaceElevated,
    onTertiaryContainer = ReversalRed,
    background = VoidObsidian,
    onBackground = ColdWhite,
    surface = VoidSurface,
    onSurface = ColdWhite,
    surfaceVariant = VoidSurfaceElevated,
    onSurfaceVariant = MutedLavender,
    outline = VoidBorder,
    outlineVariant = VoidBorder,
    error = CriticalCrimson,
    onError = ColdWhite,
  )

private val InfinitShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun InfinitBoosterTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = InfinitColorScheme,
    typography = InfinitTypography,
    shapes = InfinitShapes,
    content = content,
  )
}
