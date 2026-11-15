package com.example.resolution

import com.example.model.NativeScreenSpec
import kotlin.math.roundToInt

data class ComputedResolution(
  val widthPx: Int,
  val heightPx: Int,
  val densityDpi: Int,
  val scalePercent: Int,
)

object ResolutionHelper {

  fun computeFromPercent(spec: NativeScreenSpec, percent: Int): ComputedResolution {
    val clamped = percent.coerceIn(40, 125)
    val factor = clamped / 100f
    val w = evenPixel((spec.widthPx * factor).roundToInt().coerceAtLeast(360))
    val h = evenPixel((spec.heightPx * factor).roundToInt().coerceAtLeast(640))
    val dpi = (spec.densityDpi * factor).roundToInt().coerceIn(140, 640)
    return ComputedResolution(widthPx = w, heightPx = h, densityDpi = dpi, scalePercent = clamped)
  }

  fun computeFromWidth(spec: NativeScreenSpec, targetWidth: Int): ComputedResolution {
    val safeW = evenPixel(targetWidth.coerceIn(360, (spec.widthPx * 1.3f).roundToInt()))
    val factor = safeW.toFloat() / spec.widthPx.coerceAtLeast(1).toFloat()
    val h = evenPixel((spec.heightPx * factor).roundToInt().coerceAtLeast(480))
    val dpi = (spec.densityDpi * factor).roundToInt().coerceIn(140, 640)
    val pct = (factor * 100f).roundToInt().coerceIn(40, 130)
    return ComputedResolution(widthPx = safeW, heightPx = h, densityDpi = dpi, scalePercent = pct)
  }

  fun computeProportionalDpi(spec: NativeScreenSpec, widthPx: Int): Int {
    val factor = widthPx.toFloat() / spec.widthPx.coerceAtLeast(1).toFloat()
    return (spec.densityDpi * factor).roundToInt().coerceIn(140, 640)
  }

  private fun evenPixel(value: Int): Int = if (value % 2 == 0) value else value + 1
}
