package com.example.ax

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.model.AxCompatibilityStatus
import com.example.model.AxExtensionModule
import com.example.model.AxPluginDependency
import com.example.model.AxPluginType
import com.example.model.AxScriptPreview
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
    private const val MAX_UNPACKED_BYTES = 32L * 1024L * 1024L
    private const val MAX_TEXT_READ_BYTES = 256 * 1024
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
    val zipName = resolveDisplayName(uri)
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
    var totalFiles = 0
    var totalBytes = 0L

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
                FileOutputStream(outFile).use { fos ->
                  var read = zis.read(buffer)
                  while (read != -1) {
                    totalBytes += read
                    if (totalBytes > MAX_UNPACKED_BYTES) {
                      throw IllegalStateException("ZIP excede o limite de segurança de 32 MB.")
                    }
                    fos.write(buffer, 0, read)
                    if (mem.size() < MAX_TEXT_READ_BYTES) {
                      mem.write(buffer, 0, read)
                    }
                    read = zis.read(buffer)
                  }
                }
                fileContents[rawName] = mem.toByteArray()
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

    val analyzed =
      analyzeExtractedModule(
        zipName = zipName,
        totalFiles = totalFiles,
        fileContents = fileContents,
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
    if (!stagingDir.renameTo(finalDir)) {
      stagingDir.copyRecursively(finalDir, overwrite = true)
      stagingDir.deleteRecursively()
    }

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

  private fun analyzeExtractedModule(
    zipName: String,
    totalFiles: Int,
    fileContents: Map<String, ByteArray>,
    shizukuConnected: Boolean,
    isRootUid: Boolean,
  ): AxExtensionModule {
    // Handle ZIPs that wrap everything inside a single top-level folder
    val normalizedMap = normalizeTopFolderIfNeeded(fileContents)
    val paths = normalizedMap.keys.toList()

    val modulePropBytes = findEntryIgnoreCase(normalizedMap, "module.prop")
    val axManifestBytes =
      findEntryIgnoreCase(normalizedMap, "axmanager.json")
        ?: findEntryIgnoreCase(normalizedMap, "ax_manifest.json")
        ?: findEntryIgnoreCase(normalizedMap, "module.json")

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
    val hasCustomizeSh = paths.any { it.equals("customize.sh", true) }
    val hasServiceSh = paths.any { it.equals("service.sh", true) }
    val hasPostFsSh = paths.any { it.equals("post-fs-data.sh", true) }
    val hasActionSh = paths.any { it.equals("action.sh", true) }
    val hasUninstallSh = paths.any { it.equals("uninstall.sh", true) }
    val hasSystemProp = paths.any { it.equals("system.prop", true) }
    val hasWebUi =
      paths.any { it.startsWith("webroot/", true) || it.equals("webui.html", true) }
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
        axJson != null

    // Parse system.prop overrides
    val systemPropText =
      findEntryIgnoreCase(normalizedMap, "system.prop")?.toString(Charsets.UTF_8).orEmpty()
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
      val content = normalizedMap[path]?.toString(Charsets.UTF_8).orEmpty()
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
          .take(18)
          .joinToString("\n")
          .take(900)
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

    // Add prop overrides into executable commands too
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

    // Parse minApi / maxApi from module.prop or axJson or customize.sh
    val minApi =
      props["minApi"]?.toIntOrNull()
        ?: props["minSdk"]?.toIntOrNull()
        ?: axJson?.optInt("minApi", 0)?.takeIf { it > 0 }
        ?: detectMinApiFromScript(normalizedMap["customize.sh"]?.toString(Charsets.UTF_8).orEmpty())
    val maxApi =
      props["maxApi"]?.toIntOrNull()
        ?: props["maxSdk"]?.toIntOrNull()
        ?: axJson?.optInt("maxApi", 0)?.takeIf { it > 0 }

    // Check CPU ABI compatibility inside zygisk/ or libs/
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
    val abiMismatch =
      nativeArchDirs.isNotEmpty() && nativeArchDirs.none { it in deviceAbis }

    val sdkMismatchMin = minApi != null && Build.VERSION.SDK_INT < minApi
    val sdkMismatchMax = maxApi != null && Build.VERSION.SDK_INT > maxApi

    // Determine Plugin Type
    val isPureAxPlugin =
      axMarkerInProp ||
        (hasWebUi && !hasZygisk && !hasSystemOverlay) ||
        (hasActionSh && !hasSystemOverlay && !hasZygisk)

    val hasValidModuleStructure =
      hasModuleProp || axJson != null || scriptPreviews.isNotEmpty() || hasSystemProp || hasWebUi || hasMagiskInstaller

    val requiresRoot = hasZygisk || hasSepolicy || hasSystemOverlay || requiresRootByCommand
    val requiresShizuku = extractedApplyCmds.isNotEmpty() || hasSystemProp || hasServiceSh || hasActionSh

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

    // Build Dependency Checklist
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
            if (shizukuConnected) "Shizuku conectado e pronto para executar comandos"
            else "Conecte o Shizuku para ativar e aplicar ajustes no sistema",
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
              extractedApplyCmds.isNotEmpty() ->
                "Sem root: modo ADB/Shizuku executará apenas comandos compatíveis"
              else -> "Requer Root / Zygisk / montagem /system via Magisk"
            },
        )
    }

    if (hasWebUi) {
      dependencies +=
        AxPluginDependency(
          name = "AxManager WebUI / webroot",
          satisfied = true,
          detail = "Interface WebUI detectada dentro do ZIP",
        )
    }

    // Overall Compatibility Verdict
    val compatibility: AxCompatibilityStatus
    val compatReason: String
    when {
      !hasValidModuleStructure -> {
        compatibility = AxCompatibilityStatus.INCOMPATIBLE
        compatReason = "ZIP não possui module.prop, scripts .sh ou manifesto AxManager válido."
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
      requiresRoot && !isRootUid && extractedApplyCmds.isEmpty() -> {
        compatibility = AxCompatibilityStatus.ROOT_REQUIRED
        compatReason =
          "Módulo modifica /system, Zygisk ou sepolicy. Exige Root/Magisk ativo; sem comandos ADB/Shizuku livres."
      }
      requiresRoot && !isRootUid && extractedApplyCmds.isNotEmpty() -> {
        compatibility = AxCompatibilityStatus.PARTIAL
        compatReason =
          "Contém partes Root/Magisk, mas ${extractedApplyCmds.size} comando(s) compatíveis podem rodar via Shizuku."
      }
      else -> {
        compatibility = AxCompatibilityStatus.COMPATIBLE
        compatReason =
          if (isPureAxPlugin) "Plugin AxManager 100% compatível com Android ${Build.VERSION.RELEASE} e ${Build.MODEL}."
          else "Módulo analisado e pronto para execução via Shizuku (${extractedApplyCmds.size} comandos)."
      }
    }

    // Generate clean auto-disable commands for settings put / setprop if uninstall.sh was empty
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
      minApi = minApi,
      maxApi = maxApi,
      totalFilesCount = totalFiles,
      scriptFiles = scriptPreviews,
      dependencies = dependencies,
      applyCommands = extractedApplyCmds.distinct().take(40),
      disableCommands = autoDisable.take(40),
      propOverrides = propOverrides.take(40),
      lastExecutionLog = "Importado e analisado (${totalFiles} arquivos, ${scriptPreviews.size} scripts).",
    )
  }

  private fun normalizeTopFolderIfNeeded(map: Map<String, ByteArray>): Map<String, ByteArray> {
    val keys = map.keys
    if (keys.isEmpty()) return map
    val hasRootModuleProp = keys.any { it.equals("module.prop", true) || it.equals("axmanager.json", true) }
    if (hasRootModuleProp) return map
    val firstSegments = keys.map { it.substringBefore("/") }.distinct()
    if (firstSegments.size == 1 && keys.all { it.contains("/") }) {
      val prefix = firstSegments.first() + "/"
      return map.entries.associate { (k, v) -> k.removePrefix(prefix) to v }
    }
    return map
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

  private fun parseSystemPropLines(text: String): List<Pair<String, String>> {
    return parseKeyValueProps(text).entries.map { it.key to it.value }
  }

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
      when {
        cmd.startsWith("settings put ") -> {
          val parts = cmd.split(Regex("\\s+"))
          if (parts.size >= 4) {
            fallbacks += "settings delete ${parts[2]} ${parts[3]}"
          }
        }
        cmd.startsWith("setprop ") -> {
          val parts = cmd.split(Regex("\\s+"))
          if (parts.size >= 2) {
            fallbacks += "setprop ${parts[1]} ''"
          }
        }
        cmd.startsWith("wm size ") -> fallbacks += "wm size reset"
        cmd.startsWith("wm density ") -> fallbacks += "wm density reset"
        cmd.startsWith("cmd thermalservice override-status") -> fallbacks += "cmd thermalservice reset"
        cmd.startsWith("cmd power set-fixed-performance-mode-enabled true") ->
          fallbacks += "cmd power set-fixed-performance-mode-enabled false"
      }
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

  private fun resolveDisplayName(uri: Uri): String {
    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
          } else null
        } ?: "modulo.zip"
    } catch (_: Exception) {
      "modulo.zip"
    }
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
      if (m.minApi != null) put("minApi", m.minApi)
      if (m.maxApi != null) put("maxApi", m.maxApi)
      put("totalFilesCount", m.totalFilesCount)
      put("lastExecutionLog", m.lastExecutionLog)

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
    val scripts = mutableListOf<AxScriptPreview>()
    val scriptArr = obj.optJSONArray("scriptFiles")
    if (scriptArr != null) {
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
    val depArr = obj.optJSONArray("dependencies")
    if (depArr != null) {
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
      minApi = if (obj.has("minApi")) obj.optInt("minApi") else null,
      maxApi = if (obj.has("maxApi")) obj.optInt("maxApi") else null,
      totalFilesCount = obj.optInt("totalFilesCount", 0),
      scriptFiles = scripts,
      dependencies = deps,
      applyCommands = applyCmds.filter { it.isNotBlank() },
      disableCommands = disableCmds.filter { it.isNotBlank() },
      propOverrides = props,
      lastExecutionLog = obj.optString("lastExecutionLog"),
    )
  }
}
