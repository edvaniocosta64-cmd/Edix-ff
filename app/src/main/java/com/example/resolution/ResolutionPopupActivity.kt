package com.example.resolution

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.BoosterPreferencesRepository
import com.example.data.DeviceTelemetryManager
import com.example.model.NativeScreenSpec
import com.example.shizuku.ShizukuBridge
import com.example.shizuku.ShizukuConnectionState
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.InfinitBoosterTheme
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.NeonSuccess
import com.example.ui.theme.VoidBorder
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurface
import com.example.ui.theme.VoidSurfaceElevated
import com.example.ui.theme.WarningAmber
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class ResolutionPopupActivity : ComponentActivity() {

  private lateinit var shizukuBridge: ShizukuBridge
  private lateinit var telemetryManager: DeviceTelemetryManager
  private lateinit var repository: BoosterPreferencesRepository

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    shizukuBridge = ShizukuBridge(this)
    telemetryManager = DeviceTelemetryManager(this)
    repository = BoosterPreferencesRepository(this)
    val screenSpec = telemetryManager.readNativeScreenSpec()

    setContent {
      InfinitBoosterTheme {
        ResolutionQuickPopupContent(
          screenSpec = screenSpec,
          shizukuBridge = shizukuBridge,
          repository = repository,
          onClose = { finish() },
        )
      }
    }
  }

  override fun onDestroy() {
    shizukuBridge.release()
    super.onDestroy()
  }
}

@Composable
private fun ResolutionQuickPopupContent(
  screenSpec: NativeScreenSpec,
  shizukuBridge: ShizukuBridge,
  repository: BoosterPreferencesRepository,
  onClose: () -> Unit,
) {
  val shizukuStatus by shizukuBridge.status.collectAsState()
  val scope = rememberCoroutineScope()

  var scalePercent by remember { mutableFloatStateOf(75f) }
  var lockRatio by remember { mutableStateOf(true) }
  val initialComputed = ResolutionHelper.computeFromPercent(screenSpec, 75)
  var widthInput by remember { mutableStateOf(initialComputed.widthPx.toString()) }
  var heightInput by remember { mutableStateOf(initialComputed.heightPx.toString()) }
  var feedback by remember {
    mutableStateOf("Proporção detectada: ${screenSpec.aspectRatioLabel} (${screenSpec.widthPx}x${screenSpec.heightPx})")
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(Color.Black.copy(alpha = 0.4f))
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onClose,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      modifier =
        Modifier.padding(16.dp)
          .fillMaxWidth()
          .widthIn(max = 460.dp)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
          ),
      shape = RoundedCornerShape(24.dp),
      color = VoidSurface.copy(alpha = 0.97f),
      border = BorderStroke(1.5.dp, HollowPurpleBright),
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(imageVector = Icons.Rounded.AspectRatio, contentDescription = null, tint = LimitlessCyan)
            Column {
              Text("Resolução Rápida no Jogo", style = MaterialTheme.typography.titleLarge, color = ColdWhite)
              Text(
                text =
                  if (shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED)
                    "Shizuku Conectado • Proporção ${screenSpec.aspectRatioLabel}"
                  else "Shizuku desconectado (${shizukuStatus.connectionState.name})",
                style = MaterialTheme.typography.labelSmall,
                color = if (shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED) NeonSuccess else WarningAmber,
              )
            }
          }
          IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Fechar", tint = MutedLavender)
          }
        }

        // Quick Proportional Presets
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          listOf(50, 65, 75, 88, 100).forEach { pct ->
            val selected = scalePercent.roundToInt() == pct
            Surface(
              modifier =
                Modifier.weight(1f).clickable {
                  scalePercent = pct.toFloat()
                  val c = ResolutionHelper.computeFromPercent(screenSpec, pct)
                  widthInput = c.widthPx.toString()
                  heightInput = c.heightPx.toString()
                },
              shape = RoundedCornerShape(10.dp),
              color = if (selected) HollowPurple else VoidSurfaceElevated,
              border = BorderStroke(1.dp, if (selected) LimitlessCyan else VoidBorder),
            ) {
              Text(
                text = "${pct}%",
                style = MaterialTheme.typography.labelLarge,
                color = ColdWhite,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
              )
            }
          }
        }

        // Scale Slider
        Text(
          text = "Escala Proporcional: ${scalePercent.roundToInt()}%",
          style = MaterialTheme.typography.labelMedium,
          color = LimitlessCyan,
        )
        Slider(
          value = scalePercent,
          onValueChange = { value ->
            scalePercent = value
            val c = ResolutionHelper.computeFromPercent(screenSpec, value.roundToInt())
            widthInput = c.widthPx.toString()
            heightInput = c.heightPx.toString()
          },
          valueRange = 45f..120f,
          colors = SliderDefaults.colors(thumbColor = LimitlessCyan, activeTrackColor = HollowPurpleBright),
        )

        // Custom Width x Height
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = widthInput,
            onValueChange = { raw ->
              val digits = raw.filter { it.isDigit() }.take(4)
              widthInput = digits
              val w = digits.toIntOrNull()
              if (lockRatio && w != null && w >= 360) {
                val c = ResolutionHelper.computeFromWidth(screenSpec, w)
                heightInput = c.heightPx.toString()
                scalePercent = c.scalePercent.toFloat().coerceIn(45f, 120f)
              }
            },
            label = { Text("Largura") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LimitlessCyan,
                unfocusedBorderColor = VoidBorder,
                focusedTextColor = ColdWhite,
                unfocusedTextColor = ColdWhite,
              ),
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = heightInput,
            onValueChange = { raw ->
              if (!lockRatio) {
                heightInput = raw.filter { it.isDigit() }.take(4)
              }
            },
            enabled = !lockRatio,
            label = { Text("Altura") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LimitlessCyan,
                unfocusedBorderColor = VoidBorder,
                focusedTextColor = ColdWhite,
                unfocusedTextColor = ColdWhite,
                disabledTextColor = MutedLavender,
                disabledBorderColor = VoidBorder.copy(alpha = 0.5f),
              ),
            modifier = Modifier.weight(1f),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Travar proporção (${screenSpec.aspectRatioLabel})",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedLavender,
          )
          Switch(
            checked = lockRatio,
            onCheckedChange = { lockRatio = it },
            colors = SwitchDefaults.colors(checkedThumbColor = VoidObsidian, checkedTrackColor = LimitlessCyan),
          )
        }

        Text(text = feedback, style = MaterialTheme.typography.labelMedium, color = MutedLavender)

        Button(
          onClick = {
            val w = widthInput.toIntOrNull()?.coerceIn(360, 4320) ?: screenSpec.widthPx
            val h = heightInput.toIntOrNull()?.coerceIn(480, 7680) ?: screenSpec.heightPx
            val dpi = ResolutionHelper.computeProportionalDpi(screenSpec, w)
            scope.launch {
              val msg = shizukuBridge.applyCustomResolution(w, h, dpi)
              repository.setResolutionConfig(scalePercent.roundToInt(), w, h, lockRatio)
              feedback = msg ?: "Autorize o Shizuku no app para aplicar ${w}x${h}."
            }
          },
          modifier = Modifier.fillMaxWidth().height(48.dp),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
        ) {
          Text("Aplicar Agora", color = ColdWhite)
        }
      }
    }
  }
}
