package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.model.WallpaperTheme
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.ReversalRed
import com.example.ui.theme.VoidObsidian
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VoidBackground(
  boostActive: Boolean,
  targetHz: Int,
  wallpaperTheme: WallpaperTheme = WallpaperTheme.VAZIO_ROXO,
  wallpaperRevision: Int = 0,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val customBitmap by
    produceState<ImageBitmap?>(
      initialValue = null,
      key1 = wallpaperTheme,
      key2 = wallpaperRevision,
    ) {
      value =
        if (wallpaperTheme == WallpaperTheme.GALERIA_CUSTOM) {
          withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "custom_wallpaper.jpg")
            if (file.exists() && file.length() > 0L) {
              runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
            } else {
              null
            }
          }
        } else {
          null
        }
    }

  val transition = rememberInfiniteTransition(label = "void_bg_transition")
  val orbitPhase by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = (2f * PI).toFloat(),
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = if (boostActive) 5500 else 11000, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "orbit_phase",
    )
  val pulseScale by
    transition.animateFloat(
      initialValue = 1.0f,
      targetValue = if (boostActive) 1.07f else 1.03f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = if (targetHz >= 90) 3800 else 4800),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "pulse_scale",
    )

  val primaryGlowColor =
    when (wallpaperTheme) {
      WallpaperTheme.REVERSAO_VERMELHA -> ReversalRed
      WallpaperTheme.EXPANSAO_DOMINIO -> LimitlessCyan
      else -> HollowPurpleBright
    }

  Box(modifier = modifier.fillMaxSize().background(VoidObsidian)) {
    val activeCustomBitmap = customBitmap
    if (wallpaperTheme == WallpaperTheme.GALERIA_CUSTOM && activeCustomBitmap != null) {
      Image(
        bitmap = activeCustomBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.fillMaxSize()
            .graphicsLayer {
              scaleX = pulseScale
              scaleY = pulseScale
            }
            .alpha(if (boostActive) 0.72f else 0.58f),
      )
    } else if (wallpaperTheme == WallpaperTheme.EXPANSAO_DOMINIO) {
      Image(
        painter = painterResource(id = R.drawable.img_domain_hero),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.fillMaxSize()
            .graphicsLayer {
              scaleX = pulseScale
              scaleY = pulseScale
            }
            .alpha(if (boostActive) 0.65f else 0.48f),
      )
    } else {
      Image(
        painter = painterResource(id = R.drawable.img_void_bg),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.fillMaxSize()
            .graphicsLayer {
              scaleX = pulseScale
              scaleY = pulseScale
            }
            .alpha(
              when {
                wallpaperTheme == WallpaperTheme.REVERSAO_VERMELHA -> 0.45f
                boostActive -> 0.62f
                else -> 0.42f
              }
            ),
      )

      // Subtle sorcerer silhouette aura on right-top edge for Hollow Purple / Crimson themes
      Image(
        painter = painterResource(id = R.drawable.img_domain_hero),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.fillMaxSize()
            .alpha(if (boostActive) 0.16f else 0.08f),
      )
    }

    // Dynamic themed energy convergence Canvas + dark readability scrim
    Canvas(modifier = Modifier.fillMaxSize()) {
      val center = Offset(size.width * 0.5f, size.height * 0.28f)
      val radius = size.minDimension * 0.42f

      if (wallpaperTheme == WallpaperTheme.REVERSAO_VERMELHA) {
        drawRect(color = ReversalRed.copy(alpha = 0.14f))
      }

      drawCircle(
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                primaryGlowColor.copy(alpha = if (boostActive) 0.24f else 0.12f),
                HollowPurple.copy(alpha = 0.06f),
                Color.Transparent,
              ),
            center = center,
            radius = radius * 1.4f,
          ),
        center = center,
        radius = radius * 1.4f,
      )

      // Converging Limitless Cyan (Ao) and Reversal Red (Aka) wisps
      val cyanOffset =
        Offset(
          x = center.x + cos(orbitPhase) * radius * 0.55f,
          y = center.y + sin(orbitPhase * 1.2f) * radius * 0.28f,
        )
      val redOffset =
        Offset(
          x = center.x + cos(orbitPhase + PI.toFloat()) * radius * 0.55f,
          y = center.y + sin(orbitPhase * 1.2f + PI.toFloat()) * radius * 0.28f,
        )

      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(LimitlessCyan.copy(alpha = 0.22f), Color.Transparent),
            center = cyanOffset,
            radius = radius * 0.5f,
          ),
        center = cyanOffset,
        radius = radius * 0.5f,
      )

      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(ReversalRed.copy(alpha = 0.20f), Color.Transparent),
            center = redOffset,
            radius = radius * 0.5f,
          ),
        center = redOffset,
        radius = radius * 0.5f,
      )

      // Dark readability scrim so UI text & cards stay high-contrast over any wallpaper
      val scrimAlphaTop = if (wallpaperTheme == WallpaperTheme.GALERIA_CUSTOM) 0.40f else 0.0f
      drawRect(
        brush =
          Brush.verticalGradient(
            colors =
              listOf(
                VoidObsidian.copy(alpha = scrimAlphaTop),
                VoidObsidian.copy(alpha = if (wallpaperTheme == WallpaperTheme.GALERIA_CUSTOM) 0.65f else 0.55f),
                VoidObsidian.copy(alpha = 0.92f),
              ),
            startY = size.height * 0.14f,
            endY = size.height,
          )
      )
    }
  }
}
