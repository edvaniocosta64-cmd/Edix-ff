package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.VoidBorder
import com.example.ui.theme.VoidSurface
import com.example.ui.theme.VoidSurfaceElevated

@Composable
fun VoidGlassCard(
  modifier: Modifier = Modifier,
  borderColor: Color = VoidBorder,
  containerColor: Color = VoidSurface.copy(alpha = 0.86f),
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(20.dp),
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    tonalElevation = 4.dp,
  ) {
    Column(modifier = Modifier.padding(16.dp), content = content)
  }
}

@Composable
fun TelemetryPill(
  label: String,
  value: String,
  accentColor: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = VoidSurfaceElevated.copy(alpha = 0.85f),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MutedLavender,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.labelLarge,
        color = accentColor,
      )
    }
  }
}

@Composable
fun AppDrawableIcon(
  drawable: Drawable?,
  contentDescription: String,
  size: Dp = 48.dp,
) {
  val imageBitmap =
    remember(drawable) {
      drawable?.let { d ->
        try {
          if (d is BitmapDrawable && d.bitmap != null) {
            d.bitmap.asImageBitmap()
          } else {
            val w = d.intrinsicWidth.coerceAtLeast(96)
            val h = d.intrinsicHeight.coerceAtLeast(96)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, canvas.width, canvas.height)
            d.draw(canvas)
            bmp.asImageBitmap()
          }
        } catch (_: Exception) {
          null
        }
      }
    }

  if (imageBitmap != null) {
    Image(
      bitmap = imageBitmap,
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)),
    )
  } else {
    Box(
      modifier =
        Modifier.size(size)
          .clip(RoundedCornerShape(12.dp))
          .background(VoidSurfaceElevated),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Rounded.SportsEsports,
        contentDescription = contentDescription,
        tint = HollowPurpleBright,
        modifier = Modifier.size(size * 0.55f),
      )
    }
  }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onBackground,
    )
    if (subtitle != null) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MutedLavender,
      )
    }
  }
}
