package com.example.ui.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.NeonSuccess
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurfaceElevated
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay

private enum class DomainIntroStage {
  SIGN_PREVIEW,
  DOMAIN_BURST,
}

@Composable
fun DomainExpansionOverlay(
  voiceEnabled: Boolean,
  onStartIntro: () -> Unit,
  onTriggerBurst: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var stage by remember { mutableStateOf(DomainIntroStage.SIGN_PREVIEW) }
  var signHoldReady by remember { mutableStateOf(false) }
  var videoFallbackError by remember { mutableStateOf(false) }
  var surfaceRef by remember { mutableStateOf<Surface?>(null) }
  var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
  var videoWidth by remember { mutableIntStateOf(1280) }
  var videoHeight by remember { mutableIntStateOf(720) }
  var dismissedOnce by remember { mutableStateOf(false) }
  var signPrepared by remember { mutableStateOf(false) }
  var burstPrepared by remember { mutableStateOf(false) }
  var burstStarted by remember { mutableStateOf(false) }

  val triggerDismiss = remember(onDismiss) {
    {
      if (!dismissedOnce) {
        dismissedOnce = true
        onDismiss()
      }
    }
  }

  val burstShockwave = remember { Animatable(0f) }
  val infinite = rememberInfiniteTransition(label = "domain_rings")
  val ringPulse by
    infinite.animateFloat(
      initialValue = 0.92f,
      targetValue = 1.08f,
      animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "ring_pulse",
    )
  val ringRotation by
    infinite.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
      label = "ring_rotation",
    )

  val audioAttrs = remember {
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
      .build()
  }

  val signPlayer = remember { MediaPlayer() }
  val burstPlayer = remember { MediaPlayer() }

  LaunchedEffect(Unit) {
    onStartIntro()
    // Request transient media audio focus so system ducking/other players don't interfere
    try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val req =
          AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttrs)
            .build()
        am.requestAudioFocus(req)
      } else {
        @Suppress("DEPRECATION")
        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      }
    } catch (_: Exception) {}

    // Pre-prepare Stage 1 (Sign) and Stage 2 (Domain Expansion Burst) in parallel for zero-lag transition
    try {
      signPlayer.setAudioAttributes(audioAttrs)
      signPlayer.setDataSource(
        context,
        Uri.parse("android.resource://${context.packageName}/${R.raw.domain_intro_sign}"),
      )
      val vol = if (voiceEnabled) 1f else 0f
      signPlayer.setVolume(vol, vol)
      signPlayer.setOnVideoSizeChangedListener { _, w, h ->
        if (w > 0 && h > 0) {
          videoWidth = w
          videoHeight = h
          textureViewRef?.let { tv -> updateFullScreenCropMatrix(tv, w, h) }
        }
      }
      signPlayer.setOnPreparedListener {
        signPrepared = true
      }
      signPlayer.setOnCompletionListener {
        signHoldReady = true
      }
      signPlayer.setOnErrorListener { _, _, _ ->
        videoFallbackError = true
        signHoldReady = true
        true
      }
      signPlayer.prepareAsync()
    } catch (_: Exception) {
      videoFallbackError = true
      signHoldReady = true
    }

    try {
      burstPlayer.setAudioAttributes(audioAttrs)
      burstPlayer.setDataSource(
        context,
        Uri.parse("android.resource://${context.packageName}/${R.raw.domain_intro_burst}"),
      )
      val vol = if (voiceEnabled) 1f else 0f
      burstPlayer.setVolume(vol, vol)
      burstPlayer.setOnVideoSizeChangedListener { _, w, h ->
        if (w > 0 && h > 0) {
          videoWidth = w
          videoHeight = h
          textureViewRef?.let { tv -> updateFullScreenCropMatrix(tv, w, h) }
        }
      }
      burstPlayer.setOnPreparedListener {
        burstPrepared = true
      }
      burstPlayer.setOnCompletionListener {
        triggerDismiss()
      }
      burstPlayer.setOnErrorListener { _, _, _ ->
        videoFallbackError = true
        triggerDismiss()
        true
      }
      burstPlayer.prepareAsync()
    } catch (_: Exception) {
      // Fallback timer will dismiss cleanly if burst fails
    }
  }

  // Keep player volumes synchronized if voice toggle changes
  LaunchedEffect(voiceEnabled) {
    val vol = if (voiceEnabled) 1f else 0f
    runCatching { signPlayer.setVolume(vol, vol) }
    runCatching { burstPlayer.setVolume(vol, vol) }
  }

  // Start Stage 1 playback immediately when Surface & signPlayer are ready
  LaunchedEffect(stage, surfaceRef, signPrepared) {
    val activeSurface = surfaceRef ?: return@LaunchedEffect
    if (stage == DomainIntroStage.SIGN_PREVIEW && signPrepared && !signHoldReady) {
      try {
        signPlayer.setSurface(activeSurface)
        textureViewRef?.let { tv -> updateFullScreenCropMatrix(tv, videoWidth, videoHeight) }
        if (!signPlayer.isPlaying) {
          signPlayer.start()
        }
      } catch (_: Exception) {
        videoFallbackError = true
        signHoldReady = true
      }
    }
  }

  // Switch instantaneously to pre-prepared Stage 2 playback when ENTRAR is pressed
  LaunchedEffect(stage, surfaceRef, burstPrepared) {
    if (stage != DomainIntroStage.DOMAIN_BURST) return@LaunchedEffect
    val activeSurface = surfaceRef
    if (activeSurface != null && burstPrepared && !burstStarted) {
      burstStarted = true
      try {
        if (signPlayer.isPlaying) signPlayer.stop()
      } catch (_: Exception) {}
      try {
        signPlayer.setSurface(null)
      } catch (_: Exception) {}
      try {
        burstPlayer.setSurface(activeSurface)
        textureViewRef?.let { tv -> updateFullScreenCropMatrix(tv, videoWidth, videoHeight) }
        burstPlayer.start()
      } catch (_: Exception) {
        videoFallbackError = true
      }
    }
  }

  // Visual shockwave + safety completion guard for Stage 2
  LaunchedEffect(stage, videoFallbackError) {
    if (stage == DomainIntroStage.DOMAIN_BURST) {
      burstShockwave.snapTo(0f)
      burstShockwave.animateTo(1f, animationSpec = tween(1600, easing = FastOutSlowInEasing))
      delay(if (videoFallbackError) 1200L else 6200L)
      triggerDismiss()
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      runCatching {
        if (signPlayer.isPlaying) signPlayer.stop()
      }
      runCatching { signPlayer.release() }
      runCatching {
        if (burstPlayer.isPlaying) burstPlayer.stop()
      }
      runCatching { burstPlayer.release() }
      runCatching { surfaceRef?.release() }
    }
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(VoidObsidian)
        .testTag("domain_expansion_overlay")
  ) {
    if (!videoFallbackError) {
      AndroidView(
        factory = { ctx ->
          TextureView(ctx).apply {
            isOpaque = true
            surfaceTextureListener =
              object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                  surfaceTexture: SurfaceTexture,
                  width: Int,
                  height: Int,
                ) {
                  textureViewRef = this@apply
                  updateFullScreenCropMatrix(this@apply, videoWidth, videoHeight)
                  surfaceRef = Surface(surfaceTexture)
                }

                override fun onSurfaceTextureSizeChanged(
                  surfaceTexture: SurfaceTexture,
                  width: Int,
                  height: Int,
                ) {
                  updateFullScreenCropMatrix(this@apply, videoWidth, videoHeight)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                  runCatching { signPlayer.setSurface(null) }
                  runCatching { burstPlayer.setSurface(null) }
                  surfaceRef?.release()
                  surfaceRef = null
                  textureViewRef = null
                  return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
              }
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Image(
        painter = painterResource(id = R.drawable.img_domain_hero),
        contentDescription = stringResource(R.string.domain_subtitle),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }

    // Light edge readability scrim + synchronized Domain Expansion aura
    Canvas(modifier = Modifier.fillMaxSize()) {
      drawRect(
        brush =
          Brush.verticalGradient(
            colors =
              listOf(
                VoidObsidian.copy(alpha = 0.45f),
                Color.Transparent,
                Color.Transparent,
                VoidObsidian.copy(alpha = 0.72f),
              ),
            startY = 0f,
            endY = size.height,
          )
      )

      val center = Offset(size.width / 2f, size.height * 0.46f)
      val baseR = size.minDimension * 0.42f

      if (signHoldReady && stage == DomainIntroStage.SIGN_PREVIEW) {
        drawCircle(
          color = LimitlessCyan.copy(alpha = 0.24f),
          radius = baseR * ringPulse,
          center = center,
          style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
          color = HollowPurpleBright.copy(alpha = 0.20f),
          radius = baseR * (1.18f / ringPulse),
          center = center,
          style = Stroke(width = 2.dp.toPx()),
        )
      }

      if (stage == DomainIntroStage.DOMAIN_BURST) {
        val wave = burstShockwave.value
        val expandRadius = 40.dp.toPx() + size.maxDimension * 0.75f * wave
        drawCircle(
          brush =
            Brush.radialGradient(
              colors =
                listOf(
                  Color.White.copy(alpha = (1f - wave) * 0.45f),
                  HollowPurpleBright.copy(alpha = (1f - wave) * 0.40f),
                  LimitlessCyan.copy(alpha = (1f - wave) * 0.22f),
                  Color.Transparent,
                ),
              center = center,
              radius = expandRadius,
            ),
          center = center,
          radius = expandRadius,
        )
      }
    }

    // Top status pill & optional Skip
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 18.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(50),
        color = VoidSurfaceElevated.copy(alpha = 0.78f),
        modifier = Modifier.border(1.dp, HollowPurpleBright.copy(alpha = 0.55f), RoundedCornerShape(50)),
      ) {
        Text(
          text = "INFINIT BOOSTER • APP EM TESTE",
          style = MaterialTheme.typography.labelMedium,
          color = HollowPurpleBright,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }

      TextButton(
        onClick = { triggerDismiss() },
        modifier = Modifier.testTag("skip_domain_intro_button"),
      ) {
        Text(
          text = stringResource(R.string.skip_intro) + " →",
          style = MaterialTheme.typography.labelLarge,
          color = ColdWhite,
        )
      }
    }

    // Bottom interactive Entrance Controls + Domain Activation Feedback
    Column(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 24.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        shape = CircleShape,
        color = (if (stage == DomainIntroStage.DOMAIN_BURST) LimitlessCyan else HollowPurple).copy(alpha = 0.32f),
        modifier = Modifier.border(1.dp, LimitlessCyan.copy(alpha = 0.65f), CircleShape),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector =
              if (stage == DomainIntroStage.DOMAIN_BURST) Icons.Rounded.AutoAwesome
              else Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = LimitlessCyan,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = ringRotation * 0.12f },
          )
          Text(
            text =
              if (stage == DomainIntroStage.DOMAIN_BURST)
                "EXPANSÃO DE DOMÍNIO ATIVADA • 無量空処"
              else if (signHoldReady)
                "SINAL PRONTO • TOQUE EM ENTRAR"
              else
                "領域展開 • PREPARANDO DOMÍNIO",
            style = MaterialTheme.typography.labelMedium,
            color = LimitlessCyan,
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Text(
        text = stringResource(R.string.domain_kanji),
        style = MaterialTheme.typography.displaySmall.copy(fontSize = 23.sp),
        color = ColdWhite,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = "Criador: edix ff  •  App em teste",
        style = MaterialTheme.typography.labelMedium,
        color = WarningAmber,
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(14.dp))

      AnimatedVisibility(
        visible = stage == DomainIntroStage.SIGN_PREVIEW,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
      ) {
        Button(
          onClick = {
            if (stage == DomainIntroStage.SIGN_PREVIEW) {
              stage = DomainIntroStage.DOMAIN_BURST
              onTriggerBurst()
            }
          },
          modifier =
            Modifier.fillMaxWidth()
              .widthIn(max = 320.dp)
              .height(54.dp)
              .testTag("enter_domain_button"),
          shape = RoundedCornerShape(16.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = HollowPurple,
              contentColor = ColdWhite,
            ),
          border = BorderStroke(1.5.dp, LimitlessCyan),
        ) {
          Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = LimitlessCyan,
            modifier = Modifier.size(24.dp),
          )
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text = "ENTRAR",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = ColdWhite,
          )
        }
      }

      if (stage == DomainIntroStage.DOMAIN_BURST) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = VoidSurfaceElevated.copy(alpha = 0.85f),
          border = BorderStroke(1.dp, NeonSuccess.copy(alpha = 0.7f)),
        ) {
          Text(
            text = "Ativando Vazio Infinito...",
            style = MaterialTheme.typography.labelLarge,
            color = NeonSuccess,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
          )
        }
      }
    }
  }
}

private fun updateFullScreenCropMatrix(
  view: TextureView,
  videoWidth: Int,
  videoHeight: Int,
) {
  if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0 || view.height <= 0) return
  val viewW = view.width.toFloat()
  val viewH = view.height.toFloat()
  val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
  val viewAspect = viewW / viewH

  val scaleX: Float
  val scaleY: Float
  if (videoAspect > viewAspect) {
    // True full-screen CENTER_CROP: fill 100% height edge-to-edge, center horizontally
    scaleX = videoAspect / viewAspect
    scaleY = 1f
  } else {
    scaleX = 1f
    scaleY = viewAspect / videoAspect
  }

  val matrix = Matrix()
  matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
  view.setTransform(matrix)
}
