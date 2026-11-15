package com.example.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.InstalledAppItem
import com.example.ui.BoosterUiState
import com.example.ui.components.AppDrawableIcon
import com.example.ui.components.SectionHeader
import com.example.ui.components.VoidGlassCard
import com.example.ui.theme.ColdWhite
import com.example.ui.theme.HollowPurple
import com.example.ui.theme.HollowPurpleBright
import com.example.ui.theme.LimitlessCyan
import com.example.ui.theme.MutedLavender
import com.example.ui.theme.NeonSuccess
import com.example.ui.theme.VoidBorder
import com.example.ui.theme.VoidObsidian
import com.example.ui.theme.VoidSurface
import com.example.ui.theme.VoidSurfaceElevated

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesLauncherScreen(
  state: BoosterUiState,
  loadIcon: (String) -> Drawable?,
  onAddGame: (String) -> Unit,
  onRemoveGame: (String) -> Unit,
  onLaunchGame: (InstalledAppItem) -> Unit,
  onRefreshApps: () -> Unit,
) {
  var showPickerSheet by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SectionHeader(
          title = "Biblioteca de Jogos",
          subtitle =
            "Perfil ativo: ${state.settings.graphicsBackend.title} • ${state.settings.targetRefreshRate.label}",
        )
        Button(
          onClick = {
            onRefreshApps()
            showPickerSheet = true
          },
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("add_game_button"),
        ) {
          Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = ColdWhite)
          Spacer(modifier = Modifier.size(6.dp))
          Text("Adicionar", style = MaterialTheme.typography.labelLarge, color = ColdWhite)
        }
      }
    }

    if (state.addedGames.isEmpty()) {
      item {
        VoidGlassCard(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.SportsEsports,
              contentDescription = null,
              tint = HollowPurpleBright,
              modifier = Modifier.size(54.dp),
            )
            Text(
              text = "Nenhum jogo adicionado ainda",
              style = MaterialTheme.typography.titleLarge,
              color = ColdWhite,
              textAlign = TextAlign.Center,
            )
            Text(
              text =
                "Adicione seus jogos instalados para aplicar o Vazio Roxo, ${state.settings.graphicsBackend.title} e ${state.settings.targetRefreshRate.label} automaticamente ao abrir.",
              style = MaterialTheme.typography.bodyMedium,
              color = MutedLavender,
              textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
              onClick = {
                onRefreshApps()
                showPickerSheet = true
              },
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
              modifier = Modifier.minimumInteractiveComponentSize().testTag("empty_add_game_button"),
            ) {
              Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = ColdWhite)
              Spacer(modifier = Modifier.size(6.dp))
              Text("Selecionar Jogos do Aparelho", color = ColdWhite)
            }
          }
        }
      }
    } else {
      items(state.addedGames, key = { it.packageName }) { game ->
        val icon = remember(game.packageName) { loadIcon(game.packageName) }
        VoidGlassCard(
          modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
          borderColor = HollowPurpleBright.copy(alpha = 0.45f),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            AppDrawableIcon(drawable = icon, contentDescription = game.label, size = 54.dp)

            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = game.label,
                style = MaterialTheme.typography.titleMedium,
                color = ColdWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text =
                  "${state.settings.graphicsBackend.title} • ${state.settings.targetRefreshRate.label}",
                style = MaterialTheme.typography.labelMedium,
                color = LimitlessCyan,
              )
            }

            IconButton(
              onClick = { onRemoveGame(game.packageName) },
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Remover ${game.label}",
                tint = MutedLavender,
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))
          Button(
            onClick = { onLaunchGame(game) },
            modifier =
              Modifier.fillMaxWidth()
                .height(48.dp)
                .testTag("launch_game_${game.packageName}"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HollowPurple),
          ) {
            Icon(
              imageVector = Icons.Rounded.PlayArrow,
              contentDescription = null,
              tint = ColdWhite,
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
              text = "Boost & Entrar no Jogo",
              style = MaterialTheme.typography.titleMedium,
              color = ColdWhite,
            )
          }
        }
      }
    }
  }

  if (showPickerSheet) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { showPickerSheet = false },
      sheetState = sheetState,
      containerColor = VoidSurface,
      contentColor = ColdWhite,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Adicionar Jogos ao Booster",
          style = MaterialTheme.typography.titleLarge,
          color = ColdWhite,
        )

        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          placeholder = { Text("Buscar jogo ou aplicativo...", color = MutedLavender) },
          leadingIcon = {
            Icon(imageVector = Icons.Rounded.Search, contentDescription = null, tint = LimitlessCyan)
          },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedBorderColor = HollowPurpleBright,
              unfocusedBorderColor = VoidBorder,
              focusedTextColor = ColdWhite,
              unfocusedTextColor = ColdWhite,
              focusedContainerColor = VoidObsidian,
              unfocusedContainerColor = VoidObsidian,
            ),
          modifier = Modifier.fillMaxWidth().testTag("search_apps_input"),
        )

        val filtered =
          remember(state.installedApps, searchQuery) {
            if (searchQuery.isBlank()) state.installedApps
            else
              state.installedApps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                  it.packageName.contains(searchQuery, ignoreCase = true)
              }
          }

        LazyColumn(
          modifier = Modifier.fillMaxWidth().height(380.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(filtered, key = { it.packageName }) { app ->
            val added = app.packageName in state.settings.addedGamePackages
            val icon = remember(app.packageName) { loadIcon(app.packageName) }
            Surface(
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(14.dp))
                  .clickable {
                    if (added) onRemoveGame(app.packageName) else onAddGame(app.packageName)
                  },
              shape = RoundedCornerShape(14.dp),
              color = if (added) HollowPurple.copy(alpha = 0.24f) else VoidSurfaceElevated,
              border =
                BorderStroke(
                  1.dp,
                  if (added) NeonSuccess.copy(alpha = 0.7f) else VoidBorder,
                ),
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                AppDrawableIcon(drawable = icon, contentDescription = app.label, size = 44.dp)
                Column(modifier = Modifier.weight(1f)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    Text(
                      text = app.label,
                      style = MaterialTheme.typography.titleMedium,
                      color = ColdWhite,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    if (app.isGameCategory) {
                      Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = LimitlessCyan.copy(alpha = 0.16f),
                      ) {
                        Text(
                          text = "JOGO",
                          style = MaterialTheme.typography.labelSmall,
                          color = LimitlessCyan,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                    }
                  }
                  Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedLavender,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                Icon(
                  imageVector = if (added) Icons.Rounded.Check else Icons.Rounded.Add,
                  contentDescription = if (added) "Adicionado" else "Adicionar",
                  tint = if (added) NeonSuccess else HollowPurpleBright,
                )
              }
            }
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}
