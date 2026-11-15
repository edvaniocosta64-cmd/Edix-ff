package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.ReversalRed
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurfaceElevated
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DomainExpansionOverlay(
  voiceEnabled: Boolean,
  onStartIntro: () -> Unit,
  onDismiss: () -> Unit,
) {
  val convergence = remember { Animatable(0f) }
  val shockwave = remember { Animatable(0f) }
  val heroAlpha = remember { Animatable(0f) }

  val infinite = rememberInfiniteTransition(label = "domain_rings")
  val ringRotation by
    infinite.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
      label = "ring_rotation",
    )

  LaunchedEffect(Unit) {
    onStartIntro()
    launch {
      heroAlpha.animateTo(1f, animationSpec = tween(700, easing = FastOutSlowInEasing))
    }
    launch {
      convergence.animateTo(1f, animationSpec = tween(1750, easing = FastOutSlowInEasing))
      shockwave.animateTo(1f, animationSpec = tween(1100, easing = FastOutSlowInEasing))
    }
    delay(3400L)
    onDismiss()
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(VoidObsidian)
        .testTag("domain_expansion_overlay")
  ) {
    // Full-screen cosmic background
    Image(
      painter = painterResource(id = R.drawable.img_void_bg),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize().alpha(0.55f),
    )

    // Sorcerer Domain Expansion hero art
    Image(
      painter = painterResource(id = R.drawable.img_domain_hero),
      contentDescription = stringResource(R.string.domain_subtitle),
      contentScale = ContentScale.Crop,
      modifier =
        Modifier.fillMaxSize()
          .graphicsLayer {
            val s = 1.0f + convergence.value * 0.06f
            scaleX = s
            scaleY = s
            alpha = heroAlpha.value * 0.92f
          },
    )

    // Animated Domain Expansion Rings + Ao/Aka Collision into Hollow Purple
    Canvas(modifier = Modifier.fillMaxSize()) {
      val center = Offset(size.width / 2f, size.height * 0.44f)
      val maxRadius = size.minDimension * 0.46f

      // Outer Domain rings
      drawCircle(
        color = HollowPurpleBright.copy(alpha = 0.45f),
        radius = maxRadius * (0.65f + 0.12f * convergence.value),
        center = center,
        style = Stroke(width = 3.dp.toPx()),
      )
      drawCircle(
        color = LimitlessCyan.copy(alpha = 0.35f),
        radius = maxRadius * (0.48f + 0.1f * convergence.value),
        center = center,
        style = Stroke(width = 2.dp.toPx()),
      )

      // Converging Blue (Left) & Red (Right) Orbs -> Hollow Purple Core
      val spread = (1f - convergence.value) * (size.width * 0.30f)
      val cyanOrb = Offset(center.x - spread, center.y)
      val redOrb = Offset(center.x + spread, center.y)

      if (convergence.value < 0.96f) {
        drawCircle(
          brush =
            Brush.radialGradient(
              colors = listOf(LimitlessCyan.copy(alpha = 0.85f), Color.Transparent),
              center = cyanOrb,
              radius = 64.dp.toPx(),
            ),
          center = cyanOrb,
          radius = 64.dp.toPx(),
        )
        drawCircle(
          brush =
            Brush.radialGradient(
              colors = listOf(ReversalRed.copy(alpha = 0.85f), Color.Transparent),
              center = redOrb,
              radius = 64.dp.toPx(),
            ),
          center = redOrb,
          radius = 64.dp.toPx(),
        )
      }

      // Hollow Purple Fusion & Shockwave
      val purpleRadius =
        (36.dp.toPx() + maxRadius * 1.4f * shockwave.value) * convergence.value.coerceAtLeast(0.1f)
      drawCircle(
        brush =
          Brush.radialGradient(
            colors =
              listOf(
                Color.White.copy(alpha = (1f - shockwave.value * 0.65f) * 0.7f),
                HollowPurpleBright.copy(alpha = (1f - shockwave.value * 0.55f) * 0.65f),
                HollowPurple.copy(alpha = 0.15f),
                Color.Transparent,
              ),
            center = center,
            radius = purpleRadius,
          ),
        center = center,
        radius = purpleRadius,
      )

      // Bottom dark gradient for crisp Japanese & Portuguese captions
      drawRect(
        brush =
          Brush.verticalGradient(
            colors =
              listOf(
                VoidObsidian.copy(alpha = 0.45f),
                Color.Transparent,
                VoidObsidian.copy(alpha = 0.85f),
                VoidObsidian,
              ),
            startY = 0f,
            endY = size.height,
          )
      )
    }

    // Top bar with version pill and Skip button
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 20.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(50),
        color = VoidSurfaceElevated.copy(alpha = 0.85f),
        modifier = Modifier.border(1.dp, HollowPurpleBright.copy(alpha = 0.5f), RoundedCornerShape(50)),
      ) {
        Text(
          text = "INFINIT BOOSTER • APP EM TESTE",
          style = MaterialTheme.typography.labelMedium,
          color = HollowPurpleBright,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }

      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("skip_domain_intro_button"),
      ) {
        Text(
          text = stringResource(R.string.skip_intro) + " →",
          style = MaterialTheme.typography.labelLarge,
          color = ColdWhite,
        )
      }
    }

    // Bottom Japanese Domain Expansion captions
    Column(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(horizontal = 24.dp, vertical = 36.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (voiceEnabled) {
        Surface(
          shape = CircleShape,
          color = HollowPurple.copy(alpha = 0.25f),
          modifier = Modifier.border(1.dp, LimitlessCyan.copy(alpha = 0.5f), CircleShape),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.GraphicEq,
              contentDescription = null,
              tint = LimitlessCyan,
              modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = ringRotation * 0.1f },
            )
            Text(
              text = "VOZ JP: 「領域展開・無量空処」",
              style = MaterialTheme.typography.labelMedium,
              color = LimitlessCyan,
            )
          }
        }
        Spacer(modifier = Modifier.height(14.dp))
      }

      Text(
        text = stringResource(R.string.domain_kanji),
        style = MaterialTheme.typography.displaySmall.copy(fontSize = 24.sp),
        color = ColdWhite,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.domain_subtitle),
        style = MaterialTheme.typography.titleMedium,
        color = HollowPurpleBright,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Criador: edix ff",
        style = MaterialTheme.typography.bodyMedium,
        color = ColdWhite,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "App em teste — não está na versão completa",
        style = MaterialTheme.typography.labelSmall,
        color = WarningAmber,
        textAlign = TextAlign.Center,
      )
    }
  }
}
