package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.IphoneTouchConfig
import com.example.model.IphoneTouchPreset
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.NeonSuccess
import com.example.ui.theme.ReversalRed
import com.example.ui.theme.VoidBorder
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurface
import com.example.ui.theme.VoidSurfaceElevated
import com.example.ui.theme.WarningAmber
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IphoneTouchModuleSheet(
  config: IphoneTouchConfig,
  shizukuConnected: Boolean,
  moduleLog: String?,
  shizukuLogs: List<String>,
  onUpdateConfig: (IphoneTouchConfig) -> Unit,
  onApplyNow: () -> Unit,
  onRestoreDefaults: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = VoidSurface,
    contentColor = ColdWhite,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(max = 640.dp)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // AxManager Module Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = HollowPurple.copy(alpha = 0.28f),
            border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.7f)),
          ) {
            Icon(
              imageVector = Icons.Rounded.PhoneIphone,
              contentDescription = null,
              tint = LimitlessCyan,
              modifier = Modifier.padding(10.dp).size(24.dp),
            )
          }
          Column {
            Text(
              text = "iPhone Touch Engine",
              style = MaterialTheme.typography.titleLarge,
              color = ColdWhite,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = "AxManager WebUI • Módulo #14 • Curva iOS",
              style = MaterialTheme.typography.labelSmall,
              color = LimitlessCyan,
            )
          }
        }
        Surface(
          shape = RoundedCornerShape(50),
          color = (if (config.enabled) NeonSuccess else WarningAmber).copy(alpha = 0.18f),
          border = BorderStroke(1.dp, if (config.enabled) NeonSuccess else WarningAmber),
        ) {
          Text(
            text = if (config.enabled) "ATIVO" else "PAUSADO",
            style = MaterialTheme.typography.labelSmall,
            color = if (config.enabled) NeonSuccess else WarningAmber,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }

      // Authentic Internal Module Animation Canvas (iOS Touch Radar + Linear Sampling Wave)
      IphoneTouchModuleAnimationStage(
        active = config.enabled,
        sensitivity = config.pointerSpeed,
        preset = config.preset,
      )

      // Master Active Toggle inside the module
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(VoidSurfaceElevated)
            .clickable { onUpdateConfig(config.copy(enabled = !config.enabled)) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Ativar Módulo iPhone Touch",
            style = MaterialTheme.typography.titleMedium,
            color = ColdWhite,
          )
          Text(
            text =
              if (shizukuConnected) "Injeta resposta tátil linear iOS no sistema via Shizuku"
              else "Salva o perfil e ativa no sistema assim que conectar o Shizuku",
            style = MaterialTheme.typography.bodySmall,
            color = MutedLavender,
          )
        }
        Switch(
          checked = config.enabled,
          onCheckedChange = { onUpdateConfig(config.copy(enabled = it)) },
          modifier = Modifier.testTag("iphone_module_master_switch"),
          colors =
            SwitchDefaults.colors(
              checkedThumbColor = VoidObsidian,
              checkedTrackColor = NeonSuccess,
            ),
        )
      }

      // Preset Modes inside the AxManager WebUI module
      Text(
        text = "Perfil de Resposta (Curva iOS)",
        style = MaterialTheme.typography.titleSmall,
        color = ColdWhite,
      )
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IphoneTouchPreset.entries.forEach { preset ->
          val selected = config.preset == preset
          Surface(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                  onUpdateConfig(
                    config.copy(
                      enabled = true,
                      preset = preset,
                      pointerSpeed = preset.pointerSpeed,
                      longPressMs = preset.longPressMs,
                    )
                  )
                }
                .testTag("iphone_preset_${preset.id}"),
            shape = RoundedCornerShape(12.dp),
            color =
              if (selected) HollowPurple.copy(alpha = 0.30f) else VoidSurfaceElevated.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, if (selected) LimitlessCyan else VoidBorder),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.TouchApp,
                contentDescription = null,
                tint = if (selected) LimitlessCyan else MutedLavender,
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = preset.label,
                  style = MaterialTheme.typography.titleSmall,
                  color = ColdWhite,
                )
                Text(
                  text = preset.subtitle,
                  style = MaterialTheme.typography.bodySmall,
                  color = MutedLavender,
                )
              }
              Text(
                text = "Nível ${preset.pointerSpeed}",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) LimitlessCyan else MutedLavender,
              )
            }
          }
        }
      }

      // Sliders & Active Options inside the module
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = VoidSurfaceElevated.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, VoidBorder),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Sensibilidade de Deslize (Pointer Speed): ${config.pointerSpeed}/7",
            style = MaterialTheme.typography.labelLarge,
            color = LimitlessCyan,
          )
          Slider(
            value = config.pointerSpeed.toFloat(),
            onValueChange = { v ->
              onUpdateConfig(config.copy(pointerSpeed = v.roundToInt().coerceIn(1, 7)))
            },
            valueRange = 1f..7f,
            steps = 5,
            colors =
              SliderDefaults.colors(
                thumbColor = LimitlessCyan,
                activeTrackColor = HollowPurpleBright,
              ),
          )

          Text(
            text = "Tempo de Resposta ao Toque (Long Press): ${config.longPressMs} ms",
            style = MaterialTheme.typography.labelLarge,
            color = LimitlessCyan,
          )
          Slider(
            value = config.longPressMs.toFloat(),
            onValueChange = { v ->
              onUpdateConfig(config.copy(longPressMs = ((v / 10f).roundToInt() * 10).coerceIn(120, 350)))
            },
            valueRange = 120f..350f,
            colors =
              SliderDefaults.colors(
                thumbColor = LimitlessCyan,
                activeTrackColor = HollowPurpleBright,
              ),
          )

          ModuleOptionSwitch(
            title = "Zero Input-Lag (SurfaceFlinger)",
            subtitle = "Desativa backpressure e ativa latch_unsignaled para resposta imediata",
            checked = config.zeroBackpressure,
            onCheckedChange = { onUpdateConfig(config.copy(zeroBackpressure = it)) },
          )
          ModuleOptionSwitch(
            title = "Deslize Linear iOS (Fling/HWUI)",
            subtitle = "Ativa debug.performance.tuning e buffer age reduzindo arrasto",
            checked = config.smoothFlingScroll,
            onCheckedChange = { onUpdateConfig(config.copy(smoothFlingScroll = it)) },
          )
          ModuleOptionSwitch(
            title = "Prioridade Multi-Touch nos Jogos",
            subtitle = "Coloca seus jogos em GameMode Performance e Standby Active",
            checked = config.multiTouchGameBoost,
            onCheckedChange = { onUpdateConfig(config.copy(multiTouchGameBoost = it)) },
          )
        }
      }

      // Live WebUI Output Console
      val consoleLines =
        shizukuLogs.ifEmpty {
          listOfNotNull(moduleLog).ifEmpty {
            listOf("Pronto • Toque em 'Aplicar Módulo Agora' para injetar via Shizuku.")
          }
        }
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = VoidObsidian,
        border = BorderStroke(1.dp, LimitlessCyan.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = consoleLines.takeLast(5).joinToString("\n") { "> $it" },
          style = MaterialTheme.typography.labelSmall,
          color = NeonSuccess,
          modifier = Modifier.padding(10.dp),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = onApplyNow,
          modifier =
            Modifier.weight(1.3f)
              .height(48.dp)
              .testTag("apply_iphone_module_button"),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
        ) {
          Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, tint = ColdWhite)
          Spacer(modifier = Modifier.size(6.dp))
          Text("Aplicar Módulo Agora", color = ColdWhite)
        }
        OutlinedButton(
          onClick = onRestoreDefaults,
          modifier =
            Modifier.weight(0.9f)
              .minimumInteractiveComponentSize()
              .height(48.dp)
              .testTag("restore_iphone_module_button"),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, ReversalRed.copy(alpha = 0.7f)),
        ) {
          Icon(imageVector = Icons.Rounded.RestartAlt, contentDescription = null, tint = ReversalRed)
          Spacer(modifier = Modifier.size(4.dp))
          Text("Restaurar", color = ColdWhite)
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}

@Composable
private fun IphoneTouchModuleAnimationStage(
  active: Boolean,
  sensitivity: Int,
  preset: IphoneTouchPreset,
) {
  val infinite = rememberInfiniteTransition(label = "iphone_touch_anim")
  val wavePhase by
    infinite.animateFloat(
      initialValue = 0f,
      targetValue = (2f * PI).toFloat(),
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = if (active) 1100 else 2800, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "wave_phase",
    )
  val pulseRadius by
    infinite.animateFloat(
      initialValue = 0.25f,
      targetValue = 1.0f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = if (active) 1300 else 2400, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "pulse_radius",
    )

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = VoidObsidian,
    border =
      BorderStroke(
        1.dp,
        if (active) LimitlessCyan.copy(alpha = 0.75f) else HollowPurpleBright.copy(alpha = 0.4f),
      ),
    modifier = Modifier.fillMaxWidth().height(132.dp),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.2f, h * 0.5f)
        val maxR = h * 0.42f

        // Subtle iOS Touch Radar rings
        drawCircle(
          brush =
            Brush.radialGradient(
              colors =
                listOf(
                  (if (active) LimitlessCyan else HollowPurpleBright).copy(alpha = 0.35f),
                  Color.Transparent,
                ),
              center = center,
              radius = maxR * 1.25f,
            ),
          center = center,
          radius = maxR * 1.25f,
        )

        val ringColor = if (active) LimitlessCyan else HollowPurpleBright
        drawCircle(
          color = ringColor.copy(alpha = (1f - pulseRadius) * 0.8f),
          radius = maxR * pulseRadius,
          center = center,
          style = Stroke(width = 2.5.dp.toPx()),
        )
        drawCircle(
          color = NeonSuccess.copy(alpha = if (active) 0.85f else 0.35f),
          radius = 7.dp.toPx(),
          center = center,
        )

        // Animated iOS Linear Touch Response Waveform
        val startX = w * 0.38f
        val endX = w * 0.94f
        val midY = h * 0.56f
        val amp = (if (active) 10.dp.toPx() else 4.dp.toPx()) + (sensitivity * 1.6f)
        val path = Path()
        val steps = 48
        for (i in 0..steps) {
          val t = i.toFloat() / steps.toFloat()
          val x = startX + (endX - startX) * t
          val y = midY + sin((t * 4f * PI.toFloat()) + wavePhase) * amp * (0.45f + 0.55f * t)
          if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
          path = path,
          color = if (active) LimitlessCyan else HollowPurpleBright.copy(alpha = 0.65f),
          style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
      }

      Column(
        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        horizontalAlignment = Alignment.End,
      ) {
        Surface(
          shape = CircleShape,
          color = (if (active) NeonSuccess else MutedLavender).copy(alpha = 0.16f),
        ) {
          Text(
            text = if (active) "${preset.tapTimeoutMs / 12f}ms • 240Hz Touch Curve" else "Pré-visualização iOS",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) NeonSuccess else MutedLavender,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun ModuleOptionSwitch(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .clickable { onCheckedChange(!checked) }
        .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.labelLarge, color = ColdWhite)
      Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MutedLavender)
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = VoidObsidian,
          checkedTrackColor = LimitlessCyan,
        ),
    )
  }
}
