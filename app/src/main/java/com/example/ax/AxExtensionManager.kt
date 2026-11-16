package com.example.ax

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.model.AxArchiveFileEntry
import com.example.model.AxCompatibilityStatus
import com.example.model.AxExtensionModule
import com.example.model.AxPluginDependency
import com.example.model.AxPluginType
import com.example.model.AxScriptPreview
import com.example.model.AxToggleableOption
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

data class AxImportOutcome(
  val success: Boolean,
  val module: AxExtensionModule? = null,
  val message: String,
  val allModules: List<AxExtensionModule>,
)

class AxExtensionManager(private val context: Context) {

  companion object {
    private const val DIR_NAME = "ax_extensions"
    private const val CATALOG_FILE = "catalog.json"
    private const val MAX_UNPACKED_BYTES = 48L * 1024L * 1024L
    private const val MAX_TEXT_READ_BYTES = 256 * 1024

    fun formatSize(bytes: Long): String {
      if (bytes <= 0L) return "0 KB"
      val kb = bytes / 1024.0
      return if (kb >= 1024.0) {
        String.format(Locale("pt", "BR"), "%.2f MB", kb / 1024.0)
      } else {
        String.format(Locale("pt", "BR"), "%.2f KB", kb)
      }
    }
  }

  private val baseDir: File
    get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

  private val catalogFile: File
    get() = File(baseDir, CATALOG_FILE)

  fun loadInstalledPlugins(): List<AxExtensionModule> {
    if (!catalogFile.exists()) return emptyList()
    return try {
      val root = JSONArray(catalogFile.readText())
      buildList {
        for (i in 0 until root.length()) {
          val obj = root.optJSONObject(i) ?: continue
          add(parseModuleJson(obj))
        }
      }.sortedByDescending { it.importedAtEpochMs }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun importZipPlugin(
    uri: Uri,
    shizukuConnected: Boolean,
    isRootUid: Boolean,
    onlyAxManagerAllowed: Boolean,
  ): AxImportOutcome {
    val existing = loadInstalledPlugins().toMutableList()
    val (zipName, declaredZipSize) = resolveNameAndSize(uri)
    if (!zipName.endsWith(".zip", ignoreCase = true) && !looksLikeZipStream(uri)) {
      return AxImportOutcome(
        success = false,
        message = "Arquivo inválido: selecione um módulo ou plugin no formato .zip.",
        allModules = existing,
      )
    }

    val stagingDir = File(baseDir, "_staging_${System.currentTimeMillis()}")
    stagingDir.mkdirs()

    val fileContents = linkedMapOf<String, ByteArray>()
    val fileSizes = linkedMapOf<String, Long>()
    var totalFiles = 0
    var totalUnpackedBytes = 0L

    try {
      val inputStream = context.contentResolver.openInputStream(uri)
        ?: return AxImportOutcome(
          success = false,
          message = "Não foi possível abrir o arquivo .zip selecionado.",
          allModules = existing,
        )

      inputStream.use { raw ->
        ZipInputStream(raw.buffered()).use { zis ->
          val canonicalBase = stagingDir.canonicalPath + File.separator
          var entry = zis.nextEntry
          while (entry != null) {
            val rawName = entry.name.replace("\\", "/").trimStart('/')
            if (rawName.isNotBlank()) {
              val outFile = File(stagingDir, rawName)
              val canonicalOut = outFile.canonicalPath
              if (!canonicalOut.startsWith(canonicalBase)) {
                throw SecurityException("Entrada ZIP insegura detectada: $rawName")
              }
              if (entry.isDirectory) {
                outFile.mkdirs()
              } else {
                outFile.parentFile?.mkdirs()
                totalFiles++
                val buffer = ByteArray(8192)
                val mem = java.io.ByteArrayOutputStream()
                var entrySize = 0L
                FileOutputStream(outFile).use { fos ->
                  var read = zis.read(buffer)
                  while (read != -1) {
                    totalUnpackedBytes += read
                    entrySize += read
                    if (totalUnpackedBytes > MAX_UNPACKED_BYTES) {
                      throw IllegalStateException("ZIP excede o limite de segurança de 48 MB.")
                    }
                    fos.write(buffer, 0, read)
                    if (mem.size() < MAX_TEXT_READ_BYTES) {
                      mem.write(buffer, 0, read)
                    }
                    read = zis.read(buffer)
                  }
                }
                fileContents[rawName] = mem.toByteArray()
                fileSizes[rawName] = entrySize
              }
            }
            zis.closeEntry()
            entry = zis.nextEntry
          }
        }
      }
    } catch (e: Exception) {
      stagingDir.deleteRecursively()
      return AxImportOutcome(
        success = false,
        message = "Falha ao analisar ZIP: ${e.message ?: "arquivo corrompido"}",
        allModules = existing,
      )
    }

    if (totalFiles == 0) {
      stagingDir.deleteRecursively()
      return AxImportOutcome(
        success = false,
        message = "O arquivo .zip está vazio ou não contém arquivos de módulo.",
        allModules = existing,
      )
    }

    val effectiveSize = if (declaredZipSize > 0L) declaredZipSize else totalUnpackedBytes
    val (normalizedContents, normalizedSizes, wrapperPrefix) =
      normalizeTopFolderIfNeeded(fileContents, fileSizes)

    val analyzed =
      analyzeExtractedModule(
        zipName = zipName,
        zipSizeBytes = effectiveSize,
        totalFiles = totalFiles,
        fileContents = normalizedContents,
        fileSizes = normalizedSizes,
        shizukuConnected = shizukuConnected,
        isRootUid = isRootUid,
      )

    if (onlyAxManagerAllowed && analyzed.pluginType != AxPluginType.AX_MANAGER_PLUGIN) {
      stagingDir.deleteRecursively()
      return AxImportOutcome(
        success = false,
        module = analyzed,
        message =
          "Modo Só AxManager ativo: '${analyzed.name}' é ${analyzed.pluginType.label} e foi bloqueado. Desative o filtro para importar ZIPs Magisk genéricos.",
        allModules = existing,
      )
    }

    val targetDirName = sanitizeId(analyzed.id)
    val finalDir = File(baseDir, targetDirName)
    if (finalDir.exists()) {
      finalDir.deleteRecursively()
    }
    val sourceToMove =
      if (wrapperPrefix != null) File(stagingDir, wrapperPrefix.trimEnd('/')) else stagingDir
    if (!sourceToMove.renameTo(finalDir)) {
      sourceToMove.copyRecursively(finalDir, overwrite = true)
    }
    stagingDir.deleteRecursively()

    val finalModule = analyzed.copy(installedDirName = targetDirName)
    existing.removeAll { it.id == finalModule.id }
    existing.add(0, finalModule)
    saveCatalog(existing)

    val compatNote =
      when (finalModule.compatibility) {
        AxCompatibilityStatus.COMPATIBLE -> "Compatível com este Android e aparelho!"
        AxCompatibilityStatus.PARTIAL -> "Compatível parcialmente (verifique scripts/Shizuku)."
        AxCompatibilityStatus.ROOT_REQUIRED -> "Analisado • Requer acesso Root / Magisk completo."
        AxCompatibilityStatus.INCOMPATIBLE -> "Incompatível com este Android/dispositivo."
      }

    return AxImportOutcome(
      success = true,
      module = finalModule,
      message = "${finalModule.name} (${finalModule.pluginType.label}) importado • $compatNote",
      allModules = existing,
    )
  }

  fun updatePluginStatus(
    pluginId: String,
    enabled: Boolean,
    lastLog: String,
  ): List<AxExtensionModule> {
    val list = loadInstalledPlugins().toMutableList()
    val idx = list.indexOfFirst { it.id == pluginId }
    if (idx >= 0) {
      list[idx] = list[idx].copy(enabled = enabled, lastExecutionLog = lastLog)
      saveCatalog(list)
    }
    return list
  }

  fun toggleOptionKey(
    pluginId: String,
    optionKey: String,
    active: Boolean,
    lastLog: String,
  ): List<AxExtensionModule> {
    val list = loadInstalledPlugins().toMutableList()
    val idx = list.indexOfFirst { it.id == pluginId }
    if (idx >= 0) {
      val current = list[idx]
      val nextKeys =
        if (active) current.activeOptionKeys + optionKey
        else current.activeOptionKeys - optionKey
      list[idx] = current.copy(activeOptionKeys = nextKeys, lastExecutionLog = lastLog)
      saveCatalog(list)
    }
    return list
  }

  fun removePlugin(pluginId: String): List<AxExtensionModule> {
    val list = loadInstalledPlugins().toMutableList()
    val removed = list.firstOrNull { it.id == pluginId }
    if (removed != null) {
      runCatching {
        File(baseDir, removed.installedDirName).deleteRecursively()
      }
      list.removeAll { it.id == pluginId }
      saveCatalog(list)
    }
    return list
  }

  fun clearAllPlugins() {
    runCatching {
      baseDir.listFiles()?.forEach { child ->
        if (child.isDirectory) child.deleteRecursively() else child.delete()
      }
    }
  }

  fun verifyIntegrityAndCleanup(): Pair<List<AxExtensionModule>, Int> {
    val list = loadInstalledPlugins().toMutableList()
    var repairedCount = 0
    for (i in list.indices) {
      val item = list[i]
      val dir = File(baseDir, item.installedDirName)
      if (!dir.exists() && item.enabled) {
        list[i] =
          item.copy(
            enabled = false,
            lastExecutionLog = "Diretório do plugin ausente; desativado pela IA Guardião.",
          )
        repairedCount++
      }
    }
    if (repairedCount > 0) saveCatalog(list)
    return list to repairedCount
  }

  fun getPluginDirectory(module: AxExtensionModule): File = File(baseDir, module.installedDirName)

  fun getBannerFile(module: AxExtensionModule): File? {
    val path = module.bannerPath ?: return null
    val dir = getPluginDirectory(module)
    val file = resolveSafeChild(dir, path) ?: return null
    return if (file.exists() && file.isFile) file else null
  }

  fun getWebUiEntryFile(module: AxExtensionModule): File? {
    val path = module.webUiEntryPath ?: return null
    val dir = getPluginDirectory(module)
    val file = resolveSafeChild(dir, path) ?: return null
    return if (file.exists() && file.isFile) file else null
  }

  fun readPluginFileText(
    module: AxExtensionModule,
    relativePath: String,
    maxChars: Int = 64_000,
  ): String? {
    val dir = getPluginDirectory(module)
    val file = resolveSafeChild(dir, relativePath) ?: return null
    if (!file.exists() || !file.isFile) return null
    return try {
      val text = file.readText(Charsets.UTF_8)
      if (text.length > maxChars) text.take(maxChars) + "\n\n... (arquivo truncado para visualização)"
      else text
    } catch (_: Exception) {
      null
    }
  }

  private fun resolveSafeChild(base: File, relativePath: String): File? {
    return try {
      val clean = relativePath.replace("\\", "/").trimStart('/')
      val candidate = File(base, clean)
      val baseCanon = base.canonicalPath + File.separator
      val candCanon = candidate.canonicalPath
      if (candCanon.startsWith(baseCanon) || candCanon == base.canonicalPath) candidate else null
    } catch (_: Exception) {
      null
    }
  }

  fun buildToggleableOptions(module: AxExtensionModule): List<AxToggleableOption> {
    val options = mutableListOf<AxToggleableOption>()
    module.propOverrides.forEachIndexed { idx, (key, value) ->
      val safeKey = key.trim()
      val safeVal = value.trim()
      if (safeKey.isNotBlank()) {
        val cmdEnable =
          if (module.requiresRoot && !safeKey.startsWith("debug.") && !safeKey.startsWith("persist.") && !safeKey.startsWith("sys.")) {
            "su -c 'resetprop $safeKey \"$safeVal\"' 2>/dev/null || setprop $safeKey '$safeVal'"
          } else {
            "setprop $safeKey '$safeVal'"
          }
        val cmdDisable = "setprop $safeKey ''"
        options +=
          AxToggleableOption(
            key = "prop_${idx}_$safeKey",
            title = safeKey,
            category = "Propriedade system.prop",
            enableCommand = cmdEnable,
            disableCommand = cmdDisable,
            description = "Define $safeKey = $safeVal",
          )
      }
    }

    module.applyCommands.forEachIndexed { idx, cmd ->
      val trimmed = cmd.trim()
      if (trimmed.isBlank()) return@forEachIndexed
      val disableCmd = buildSingleCommandFallback(trimmed)
      val (title, category) = describeCommand(trimmed, idx)
      options +=
        AxToggleableOption(
          key = "cmd_${idx}_${trimmed.hashCode()}",
          title = title,
          category = category,
          enableCommand = trimmed,
          disableCommand = disableCmd,
          description = trimmed,
        )
    }
    return options.distinctBy { it.key }
  }

  private fun describeCommand(cmd: String, index: Int): Pair<String, String> {
    val tokens = cmd.split(Regex("\\s+"))
    return when {
      cmd.startsWith("settings put ") && tokens.size >= 5 ->
        "${tokens[3]} = ${tokens[4]}" to "Ajuste Android (${tokens[2]})"
      cmd.startsWith("setprop ") && tokens.size >= 3 ->
        "${tokens[1]} = ${tokens[2]}" to "Propriedade de Sistema"
      cmd.startsWith("cmd game ") ->
        cmd.removePrefix("cmd ").take(42) to "Game Mode"
      cmd.startsWith("cmd power ") ->
        "Controle de Energia / Fixed Perf" to "Sistema / CPU"
      cmd.startsWith("cmd thermalservice ") ->
        "Controle Térmico" to "Térmico"
      cmd.startsWith("cmd package compile ") ->
        "Otimização ART / Shader" to "Compilação"
      cmd.startsWith("wm ") ->
        cmd.take(42) to "Tela / DPI"
      else -> "Função #${index + 1}: ${cmd.take(36)}" to "Script"
    }
  }

  private fun buildSingleCommandFallback(cmd: String): String {
    val parts = cmd.split(Regex("\\s+"))
    return when {
      cmd.startsWith("settings put ") && parts.size >= 4 ->
        "settings delete ${parts[2]} ${parts[3]}"
      cmd.startsWith("setprop ") && parts.size >= 2 ->
        "setprop ${parts[1]} ''"
      cmd.startsWith("wm size ") -> "wm size reset"
      cmd.startsWith("wm density ") -> "wm density reset"
      cmd.startsWith("cmd thermalservice override-status") -> "cmd thermalservice reset"
      cmd.startsWith("cmd power set-fixed-performance-mode-enabled true") ->
        "cmd power set-fixed-performance-mode-enabled false"
      else -> ""
    }
  }

  private fun analyzeExtractedModule(
    zipName: String,
    zipSizeBytes: Long,
    totalFiles: Int,
    fileContents: Map<String, ByteArray>,
    fileSizes: Map<String, Long>,
    shizukuConnected: Boolean,
    isRootUid: Boolean,
  ): AxExtensionModule {
    val paths = fileContents.keys.toList()

    val modulePropBytes = findEntryIgnoreCase(fileContents, "module.prop")
    val axManifestBytes =
      findEntryIgnoreCase(fileContents, "axmanager.json")
        ?: findEntryIgnoreCase(fileContents, "ax_manifest.json")
        ?: findEntryIgnoreCase(fileContents, "module.json")

    val props = parseKeyValueProps(modulePropBytes?.toString(Charsets.UTF_8).orEmpty())
    val axJson =
      axManifestBytes?.let { bytes ->
        runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
      }

    val rawId =
      props["id"]
        ?: axJson?.optString("id")?.takeIf { it.isNotBlank() }
        ?: zipName.removeSuffix(".zip").lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
    val name =
      props["name"]
        ?: axJson?.optString("name")?.takeIf { it.isNotBlank() }
        ?: zipName.removeSuffix(".zip")
    val version =
      props["version"]
        ?: axJson?.optString("version")?.takeIf { it.isNotBlank() }
        ?: "1.0"
    val versionCode =
      props["versionCode"]?.toIntOrNull()
        ?: axJson?.optInt("versionCode", 1)
        ?: 1
    val author =
      props["author"]
        ?: axJson?.optString("author")?.takeIf { it.isNotBlank() }
        ?: "Comunidade"
    val description =
      props["description"]
        ?: axJson?.optString("description")?.takeIf { it.isNotBlank() }
        ?: "Módulo ZIP importado pelo AX Extension Center."

    val hasModuleProp = modulePropBytes != null
    val hasServiceSh = paths.any { it.equals("service.sh", true) }
    val hasActionSh = paths.any { it.equals("action.sh", true) }
    val hasSystemProp = paths.any { it.equals("system.prop", true) }

    val webUiEntryPath =
      paths.firstOrNull { it.equals("webroot/index.html", true) }
        ?: paths.firstOrNull { it.equals("webroot/index.htm", true) }
        ?: paths.firstOrNull { it.equals("webui.html", true) }
        ?: paths.firstOrNull { it.startsWith("webroot/", true) && it.endsWith(".html", true) }
    val hasWebUi = webUiEntryPath != null

    val propBanner = props["banner"]?.trim()?.trimStart('/')
    val bannerPath =
      propBanner?.let { target -> paths.firstOrNull { it.equals(target, true) } }
        ?: paths.firstOrNull { it.equals("banner.png", true) }
        ?: paths.firstOrNull { it.equals("banner.jpg", true) }
        ?: paths.firstOrNull { it.equals("banner.jpeg", true) }
        ?: paths.firstOrNull { it.equals("banner.webp", true) }
        ?: paths.firstOrNull {
          val lower = it.lowercase(Locale.US)
          (lower.endsWith("banner.png") || lower.endsWith("banner.jpg") || lower.endsWith("banner.webp"))
        }

    val hasSepolicy = paths.any { it.equals("sepolicy.rule", true) }
    val hasZygisk = paths.any { it.startsWith("zygisk/", true) || it.startsWith("riru/", true) }
    val hasSystemOverlay = paths.any { it.startsWith("system/", true) }
    val hasMagiskInstaller = paths.any { it.contains("META-INF/com/google/android/update-binary", true) }

    val axMarkerInProp =
      props.keys.any { it.startsWith("ax", true) } ||
        (props["axmanager"]?.equals("true", true) == true) ||
        (props["manager"]?.contains("ax", true) == true) ||
        name.contains("ax", ignoreCase = true) ||
        description.contains("axmanager", ignoreCase = true) ||
        description.contains("ADB", ignoreCase = true) ||
        description.contains("Shizuku", ignoreCase = true) ||
        axJson != null

    val allArchiveEntries =
      paths.sorted().map { path ->
        val lower = path.lowercase(Locale.US)
        val isScript = lower.endsWith(".sh")
        val isReadable =
          isScript ||
            lower.endsWith(".prop") ||
            lower.endsWith(".json") ||
            lower.endsWith(".html") ||
            lower.endsWith(".htm") ||
            lower.endsWith(".js") ||
            lower.endsWith(".mjs") ||
            lower.endsWith(".css") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".md") ||
            lower.endsWith(".conf") ||
            lower.endsWith(".xml") ||
            lower.endsWith(".rule")
        AxArchiveFileEntry(
          path = path,
          sizeBytes = fileSizes[path] ?: 0L,
          isScript = isScript,
          isTextReadable = isReadable,
        )
      }

    // Parse system.prop overrides
    val systemPropText =
      findEntryIgnoreCase(fileContents, "system.prop")?.toString(Charsets.UTF_8).orEmpty()
    val propOverrides = parseSystemPropLines(systemPropText)

    // Parse shell scripts (.sh)
    val scriptPreviews = mutableListOf<AxScriptPreview>()
    val extractedApplyCmds = mutableListOf<String>()
    val extractedDisableCmds = mutableListOf<String>()
    var requiresRootByCommand = false

    val scriptPaths =
      paths.filter { it.endsWith(".sh", ignoreCase = true) }
        .sortedBy {
          when {
            it.equals("action.sh", true) -> 0
            it.equals("service.sh", true) -> 1
            it.equals("post-fs-data.sh", true) -> 2
            it.equals("customize.sh", true) -> 3
            it.equals("uninstall.sh", true) -> 4
            else -> 5
          }
        }

    for (path in scriptPaths) {
      val content = fileContents[path]?.toString(Charsets.UTF_8).orEmpty()
      val isUninstall = path.endsWith("uninstall.sh", ignoreCase = true)
      val parsedCmds = extractExecutableCommands(content)
      if (content.contains("/data/adb", true) ||
        content.contains("resetprop", true) ||
        content.contains("/sys/devices", true) ||
        content.contains("/proc/sys", true) ||
        content.contains("mount ", true) ||
        content.contains("su -c", true)
      ) {
        requiresRootByCommand = true
      }

      if (isUninstall) {
        extractedDisableCmds += parsedCmds
      } else {
        extractedApplyCmds += parsedCmds
      }

      val cleanPreview =
        content.lineSequence()
          .take(24)
          .joinToString("\n")
          .take(1200)
      scriptPreviews +=
        AxScriptPreview(
          path = path,
          summary =
            if (parsedCmds.isNotEmpty()) "${parsedCmds.size} comando(s) suportado(s) detectado(s)"
            else if (content.isNotBlank()) "Script shell presente (${content.lines().size} linhas)"
            else "Script vazio",
          executableCommandsCount = parsedCmds.size,
          contentPreview = cleanPreview,
        )
    }

    for ((k, v) in propOverrides) {
      val safeKey = k.trim()
      val safeVal = v.trim()
      if (safeKey.startsWith("debug.") || safeKey.startsWith("persist.") || safeKey.startsWith("sys.")) {
        extractedApplyCmds += "setprop $safeKey '$safeVal'"
        extractedDisableCmds += "setprop $safeKey ''"
      } else {
        requiresRootByCommand = true
      }
    }

    val minApi =
      props["minApi"]?.toIntOrNull()
        ?: props["minSdk"]?.toIntOrNull()
        ?: axJson?.optInt("minApi", 0)?.takeIf { it > 0 }
        ?: detectMinApiFromScript(fileContents["customize.sh"]?.toString(Charsets.UTF_8).orEmpty())
    val maxApi =
      props["maxApi"]?.toIntOrNull()
        ?: props["maxSdk"]?.toIntOrNull()
        ?: axJson?.optInt("maxApi", 0)?.takeIf { it > 0 }

    val deviceAbis = Build.SUPPORTED_ABIS.toSet()
    val nativeArchDirs =
      paths.mapNotNull { p ->
        when {
          p.contains("/arm64-v8a/") || p.contains("arm64-v8a.so") -> "arm64-v8a"
          p.contains("/armeabi-v7a/") || p.contains("armeabi-v7a.so") -> "armeabi-v7a"
          p.contains("/x86_64/") || p.contains("x86_64.so") -> "x86_64"
          p.contains("/x86/") || p.contains("x86.so") -> "x86"
          else -> null
        }
      }.distinct()
    val abiMismatch = nativeArchDirs.isNotEmpty() && nativeArchDirs.none { it in deviceAbis }

    val sdkMismatchMin = minApi != null && Build.VERSION.SDK_INT < minApi
    val sdkMismatchMax = maxApi != null && Build.VERSION.SDK_INT > maxApi

    val isPureAxPlugin =
      axMarkerInProp ||
        (hasWebUi && !hasZygisk && !hasSystemOverlay) ||
        (hasActionSh && !hasSystemOverlay && !hasZygisk)

    val hasValidModuleStructure =
      hasModuleProp || axJson != null || scriptPreviews.isNotEmpty() || hasSystemProp || hasWebUi || hasMagiskInstaller

    val requiresRoot = hasZygisk || hasSepolicy || hasSystemOverlay || requiresRootByCommand
    val requiresShizuku = extractedApplyCmds.isNotEmpty() || hasSystemProp || hasServiceSh || hasActionSh || hasWebUi

    val pluginType =
      when {
        !hasValidModuleStructure -> AxPluginType.UNSUPPORTED
        isPureAxPlugin -> AxPluginType.AX_MANAGER_PLUGIN
        !requiresRoot && (extractedApplyCmds.isNotEmpty() || propOverrides.isNotEmpty()) ->
          AxPluginType.MAGISK_SHIZUKU_COMPAT
        requiresRoot && extractedApplyCmds.isNotEmpty() ->
          AxPluginType.MAGISK_SHIZUKU_COMPAT
        else -> AxPluginType.MAGISK_ROOT_ONLY
      }

    val dependencies = mutableListOf<AxPluginDependency>()
    dependencies +=
      AxPluginDependency(
        name = "Android SDK (${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})",
        satisfied = !sdkMismatchMin && !sdkMismatchMax,
        detail =
          when {
            sdkMismatchMin -> "Exige API $minApi ou superior (seu aparelho: API ${Build.VERSION.SDK_INT})"
            sdkMismatchMax -> "Suportado até API $maxApi (seu aparelho: API ${Build.VERSION.SDK_INT})"
            minApi != null -> "Exige API $minApi+ • Atendido"
            else -> "Compatível com API ${Build.VERSION.SDK_INT}"
          },
      )

    dependencies +=
      AxPluginDependency(
        name = "Arquitetura CPU (${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"})",
        satisfied = !abiMismatch,
        detail =
          if (nativeArchDirs.isEmpty()) "Scripts/Props multiplataforma (sem binário nativo restrito)"
          else if (!abiMismatch) "Binário compatível (${nativeArchDirs.joinToString(", ")})"
          else "Binários só para ${nativeArchDirs.joinToString(", ")} (incompatível com este aparelho)",
      )

    if (requiresShizuku) {
      dependencies +=
        AxPluginDependency(
          name = "Shizuku / Shell Privilegiado",
          satisfied = shizukuConnected,
          detail =
            if (shizukuConnected) "Shizuku conectado e pronto para executar comandos e WebUI"
            else "Conecte o Shizuku para aplicar ações e funções da WebUI no sistema",
        )
    }

    if (requiresRoot) {
      dependencies +=
        AxPluginDependency(
          name = "Acesso Root / Magisk Kernel",
          satisfied = isRootUid,
          detail =
            when {
              isRootUid -> "Sessão UID 0 (Root) ativa"
              extractedApplyCmds.isNotEmpty() || hasWebUi ->
                "Sem root: modo ADB/Shizuku executará as funções compatíveis"
              else -> "Requer Root / Zygisk / montagem /system via Magisk"
            },
        )
    }

    if (hasWebUi) {
      dependencies +=
        AxPluginDependency(
          name = "AxManager WebUI ($webUiEntryPath)",
          satisfied = true,
          detail = "Interface interativa pronta para abrir dentro do Infinit Booster",
        )
    }

    val compatibility: AxCompatibilityStatus
    val compatReason: String
    when {
      !hasValidModuleStructure -> {
        compatibility = AxCompatibilityStatus.INCOMPATIBLE
        compatReason = "ZIP não possui module.prop, scripts .sh ou WebUI AxManager válida."
      }
      sdkMismatchMin || sdkMismatchMax || abiMismatch -> {
        compatibility = AxCompatibilityStatus.INCOMPATIBLE
        compatReason =
          when {
            abiMismatch -> "Arquitetura incompatível (${nativeArchDirs.joinToString()} vs ${Build.SUPPORTED_ABIS.firstOrNull()})."
            sdkMismatchMin -> "Exige Android API $minApi+ (aparelho está na API ${Build.VERSION.SDK_INT})."
            else -> "Limite máximo de Android excedido (máx API $maxApi)."
          }
      }
      requiresRoot && !isRootUid && extractedApplyCmds.isEmpty() && !hasWebUi -> {
        compatibility = AxCompatibilityStatus.ROOT_REQUIRED
        compatReason =
          "Módulo modifica /system, Zygisk ou sepolicy. Exige Root/Magisk ativo."
      }
      requiresRoot && !isRootUid && (extractedApplyCmds.isNotEmpty() || hasWebUi) -> {
        compatibility = AxCompatibilityStatus.PARTIAL
        compatReason =
          "Contém partes Root/Magisk, mas WebUI e ${extractedApplyCmds.size} função(ões) podem rodar via Shizuku."
      }
      else -> {
        compatibility = AxCompatibilityStatus.COMPATIBLE
        compatReason =
          if (isPureAxPlugin) "Plugin AxManager 100% compatível com Android ${Build.VERSION.RELEASE} e ${Build.MODEL}."
          else "Módulo analisado e pronto para execução via Shizuku (${extractedApplyCmds.size} comandos)."
      }
    }

    val autoDisable =
      if (extractedDisableCmds.isNotEmpty()) {
        extractedDisableCmds.distinct()
      } else {
        buildAutoDisableFallbacks(extractedApplyCmds)
      }

    return AxExtensionModule(
      id = rawId,
      name = name,
      version = version,
      versionCode = versionCode,
      author = author,
      description = description,
      sourceZipName = zipName,
      zipSizeBytes = zipSizeBytes,
      installedDirName = sanitizeId(rawId),
      importedAtEpochMs = System.currentTimeMillis(),
      enabled = false,
      pluginType = pluginType,
      compatibility = compatibility,
      compatibilityReason = compatReason,
      androidCompatLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
      deviceCompatLabel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} • ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"}",
      requiresShizuku = requiresShizuku,
      requiresRoot = requiresRoot,
      hasWebUi = hasWebUi,
      webUiEntryPath = webUiEntryPath,
      bannerPath = bannerPath,
      hasActionSh = hasActionSh,
      minApi = minApi,
      maxApi = maxApi,
      totalFilesCount = totalFiles,
      allFiles = allArchiveEntries,
      scriptFiles = scriptPreviews,
      dependencies = dependencies,
      applyCommands = extractedApplyCmds.distinct().take(60),
      disableCommands = autoDisable.take(60),
      propOverrides = propOverrides.take(40),
      activeOptionKeys = emptySet(),
      lastExecutionLog = "Importado (${formatSize(zipSizeBytes)}, $totalFiles arquivos, ${scriptPreviews.size} scripts).",
    )
  }

  private fun normalizeTopFolderIfNeeded(
    contents: Map<String, ByteArray>,
    sizes: Map<String, Long>,
  ): Triple<Map<String, ByteArray>, Map<String, Long>, String?> {
    val keys = contents.keys
    if (keys.isEmpty()) return Triple(contents, sizes, null)
    val hasRootMarker =
      keys.any {
        it.equals("module.prop", true) ||
          it.equals("axmanager.json", true) ||
          it.startsWith("webroot/", true)
      }
    if (hasRootMarker) return Triple(contents, sizes, null)
    val firstSegments = keys.map { it.substringBefore("/") }.distinct()
    if (firstSegments.size == 1 && keys.all { it.contains("/") }) {
      val prefix = firstSegments.first() + "/"
      val strippedContents = contents.entries.associate { (k, v) -> k.removePrefix(prefix) to v }
      val strippedSizes = sizes.entries.associate { (k, v) -> k.removePrefix(prefix) to v }
      return Triple(strippedContents, strippedSizes, prefix)
    }
    return Triple(contents, sizes, null)
  }

  private fun findEntryIgnoreCase(map: Map<String, ByteArray>, target: String): ByteArray? =
    map.entries.firstOrNull { it.key.equals(target, ignoreCase = true) }?.value

  private fun parseKeyValueProps(text: String): Map<String, String> {
    val map = linkedMapOf<String, String>()
    text.lineSequence().forEach { rawLine ->
      val line = rawLine.trim()
      if (line.isBlank() || line.startsWith("#")) return@forEach
      val idx = line.indexOf('=')
      if (idx > 0) {
        val k = line.substring(0, idx).trim()
        val v = line.substring(idx + 1).trim()
        if (k.isNotBlank()) map[k] = v
      }
    }
    return map
  }

  private fun parseSystemPropLines(text: String): List<Pair<String, String>> =
    parseKeyValueProps(text).entries.map { it.key to it.value }

  private fun extractExecutableCommands(scriptText: String): List<String> {
    val safePrefixes =
      listOf(
        "settings put ",
        "settings delete ",
        "setprop ",
        "cmd game ",
        "cmd power ",
        "cmd thermalservice ",
        "cmd package compile ",
        "cmd appops ",
        "am kill-all",
        "am set-standby-bucket ",
        "pm trim-caches ",
        "dumpsys deviceidle ",
        "device_config ",
        "wm size ",
        "wm density ",
        "resetprop ",
      )
    val result = mutableListOf<String>()
    scriptText.lineSequence().forEach { rawLine ->
      val cleaned = rawLine.substringBefore("#").trim().trimEnd(';')
      if (cleaned.isBlank()) return@forEach
      cleaned.split("&&", ";").forEach { part ->
        val stmt = part.trim()
        if (safePrefixes.any { stmt.startsWith(it, ignoreCase = false) }) {
          result += stmt
        }
      }
    }
    return result
  }

  private fun buildAutoDisableFallbacks(applyCommands: List<String>): List<String> {
    val fallbacks = mutableListOf<String>()
    applyCommands.forEach { cmd ->
      val fb = buildSingleCommandFallback(cmd)
      if (fb.isNotBlank()) fallbacks += fb
    }
    return fallbacks.distinct()
  }

  private fun detectMinApiFromScript(script: String): Int? {
    val match = Regex("MIN_?SDK\\s*=\\s*(\\d+)|API\\s*-lt\\s*(\\d+)").find(script)
    val valStr = match?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
    return valStr?.toIntOrNull()
  }

  private fun sanitizeId(raw: String): String =
    raw.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_").ifBlank { "ax_plugin_${System.currentTimeMillis()}" }

  private fun resolveNameAndSize(uri: Uri): Pair<String, Long> {
    var name = "modulo.zip"
    var size = 0L
    try {
      context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIdx >= 0) {
            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
          }
          val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
            size = cursor.getLong(sizeIdx)
          }
        }
      }
    } catch (_: Exception) {
      // ignore
    }
    return name to size
  }

  private fun looksLikeZipStream(uri: Uri): Boolean {
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        val header = ByteArray(4)
        val n = stream.read(header)
        n >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
      } ?: false
    } catch (_: Exception) {
      false
    }
  }

  private fun saveCatalog(modules: List<AxExtensionModule>) {
    val arr = JSONArray()
    modules.forEach { m ->
      arr.put(serializeModuleJson(m))
    }
    catalogFile.writeText(arr.toString(2))
  }

  private fun serializeModuleJson(m: AxExtensionModule): JSONObject {
    return JSONObject().apply {
      put("id", m.id)
      put("name", m.name)
      put("version", m.version)
      put("versionCode", m.versionCode)
      put("author", m.author)
      put("description", m.description)
      put("sourceZipName", m.sourceZipName)
      put("zipSizeBytes", m.zipSizeBytes)
      put("installedDirName", m.installedDirName)
      put("importedAtEpochMs", m.importedAtEpochMs)
      put("enabled", m.enabled)
      put("pluginType", m.pluginType.name)
      put("compatibility", m.compatibility.name)
      put("compatibilityReason", m.compatibilityReason)
      put("androidCompatLabel", m.androidCompatLabel)
      put("deviceCompatLabel", m.deviceCompatLabel)
      put("requiresShizuku", m.requiresShizuku)
      put("requiresRoot", m.requiresRoot)
      put("hasWebUi", m.hasWebUi)
      if (m.webUiEntryPath != null) put("webUiEntryPath", m.webUiEntryPath)
      if (m.bannerPath != null) put("bannerPath", m.bannerPath)
      put("hasActionSh", m.hasActionSh)
      if (m.minApi != null) put("minApi", m.minApi)
      if (m.maxApi != null) put("maxApi", m.maxApi)
      put("totalFilesCount", m.totalFilesCount)
      put("lastExecutionLog", m.lastExecutionLog)
      put("activeOptionKeys", JSONArray(m.activeOptionKeys.toList()))

      put(
        "allFiles",
        JSONArray().apply {
          m.allFiles.forEach { f ->
            put(
              JSONObject().apply {
                put("path", f.path)
                put("sizeBytes", f.sizeBytes)
                put("isScript", f.isScript)
                put("isTextReadable", f.isTextReadable)
              }
            )
          }
        },
      )

      put(
        "scriptFiles",
        JSONArray().apply {
          m.scriptFiles.forEach { s ->
            put(
              JSONObject().apply {
                put("path", s.path)
                put("summary", s.summary)
                put("executableCommandsCount", s.executableCommandsCount)
                put("contentPreview", s.contentPreview)
              }
            )
          }
        },
      )

      put(
        "dependencies",
        JSONArray().apply {
          m.dependencies.forEach { d ->
            put(
              JSONObject().apply {
                put("name", d.name)
                put("satisfied", d.satisfied)
                put("detail", d.detail)
              }
            )
          }
        },
      )

      put("applyCommands", JSONArray(m.applyCommands))
      put("disableCommands", JSONArray(m.disableCommands))
      put(
        "propOverrides",
        JSONArray().apply {
          m.propOverrides.forEach { (k, v) ->
            put(JSONObject().apply { put("k", k); put("v", v) })
          }
        },
      )
    }
  }

  private fun parseModuleJson(obj: JSONObject): AxExtensionModule {
    val files = mutableListOf<AxArchiveFileEntry>()
    obj.optJSONArray("allFiles")?.let { a ->
      for (i in 0 until a.length()) {
        val f = a.optJSONObject(i) ?: continue
        files +=
          AxArchiveFileEntry(
            path = f.optString("path"),
            sizeBytes = f.optLong("sizeBytes", 0L),
            isScript = f.optBoolean("isScript", false),
            isTextReadable = f.optBoolean("isTextReadable", false),
          )
      }
    }

    val scripts = mutableListOf<AxScriptPreview>()
    obj.optJSONArray("scriptFiles")?.let { scriptArr ->
      for (i in 0 until scriptArr.length()) {
        val s = scriptArr.optJSONObject(i) ?: continue
        scripts +=
          AxScriptPreview(
            path = s.optString("path"),
            summary = s.optString("summary"),
            executableCommandsCount = s.optInt("executableCommandsCount", 0),
            contentPreview = s.optString("contentPreview"),
          )
      }
    }

    val deps = mutableListOf<AxPluginDependency>()
    obj.optJSONArray("dependencies")?.let { depArr ->
      for (i in 0 until depArr.length()) {
        val d = depArr.optJSONObject(i) ?: continue
        deps +=
          AxPluginDependency(
            name = d.optString("name"),
            satisfied = d.optBoolean("satisfied", false),
            detail = d.optString("detail"),
          )
      }
    }

    val applyCmds = mutableListOf<String>()
    obj.optJSONArray("applyCommands")?.let { a ->
      for (i in 0 until a.length()) applyCmds += a.optString(i)
    }

    val disableCmds = mutableListOf<String>()
    obj.optJSONArray("disableCommands")?.let { a ->
      for (i in 0 until a.length()) disableCmds += a.optString(i)
    }

    val activeOpts = mutableSetOf<String>()
    obj.optJSONArray("activeOptionKeys")?.let { a ->
      for (i in 0 until a.length()) {
        val k = a.optString(i)
        if (k.isNotBlank()) activeOpts += k
      }
    }

    val props = mutableListOf<Pair<String, String>>()
    obj.optJSONArray("propOverrides")?.let { a ->
      for (i in 0 until a.length()) {
        val p = a.optJSONObject(i) ?: continue
        props += p.optString("k") to p.optString("v")
      }
    }

    val typeName = obj.optString("pluginType", AxPluginType.AX_MANAGER_PLUGIN.name)
    val compatName = obj.optString("compatibility", AxCompatibilityStatus.COMPATIBLE.name)

    return AxExtensionModule(
      id = obj.optString("id"),
      name = obj.optString("name"),
      version = obj.optString("version", "1.0"),
      versionCode = obj.optInt("versionCode", 1),
      author = obj.optString("author", "Comunidade"),
      description = obj.optString("description"),
      sourceZipName = obj.optString("sourceZipName"),
      zipSizeBytes = obj.optLong("zipSizeBytes", 0L),
      installedDirName = obj.optString("installedDirName"),
      importedAtEpochMs = obj.optLong("importedAtEpochMs", 0L),
      enabled = obj.optBoolean("enabled", false),
      pluginType = runCatching { AxPluginType.valueOf(typeName) }.getOrDefault(AxPluginType.AX_MANAGER_PLUGIN),
      compatibility = runCatching { AxCompatibilityStatus.valueOf(compatName) }.getOrDefault(AxCompatibilityStatus.COMPATIBLE),
      compatibilityReason = obj.optString("compatibilityReason"),
      androidCompatLabel = obj.optString("androidCompatLabel"),
      deviceCompatLabel = obj.optString("deviceCompatLabel"),
      requiresShizuku = obj.optBoolean("requiresShizuku", false),
      requiresRoot = obj.optBoolean("requiresRoot", false),
      hasWebUi = obj.optBoolean("hasWebUi", false),
      webUiEntryPath = obj.optString("webUiEntryPath").takeIf { it.isNotBlank() },
      bannerPath = obj.optString("bannerPath").takeIf { it.isNotBlank() },
      hasActionSh = obj.optBoolean("hasActionSh", false),
      minApi = if (obj.has("minApi")) obj.optInt("minApi") else null,
      maxApi = if (obj.has("maxApi")) obj.optInt("maxApi") else null,
      totalFilesCount = obj.optInt("totalFilesCount", 0),
      allFiles = files,
      scriptFiles = scripts,
      dependencies = deps,
      applyCommands = applyCmds.filter { it.isNotBlank() },
      disableCommands = disableCmds.filter { it.isNotBlank() },
      propOverrides = props,
      activeOptionKeys = activeOpts,
      lastExecutionLog = obj.optString("lastExecutionLog"),
    )
  }
}
