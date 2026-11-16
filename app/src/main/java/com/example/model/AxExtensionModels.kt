package com.example.model

enum class AxPluginType(val label: String) {
  AX_MANAGER_PLUGIN("AxManager Plugin"),
  MAGISK_SHIZUKU_COMPAT("Magisk • Shizuku"),
  MAGISK_ROOT_ONLY("Magisk • Root"),
  UNSUPPORTED("Não Suportado"),
}

enum class AxCompatibilityStatus(val label: String) {
  COMPATIBLE("COMPATÍVEL"),
  PARTIAL("PARCIAL"),
  ROOT_REQUIRED("REQUER ROOT"),
  INCOMPATIBLE("INCOMPATÍVEL"),
}

data class AxPluginDependency(
  val name: String,
  val satisfied: Boolean,
  val detail: String,
)

data class AxScriptPreview(
  val path: String,
  val summary: String,
  val executableCommandsCount: Int,
  val contentPreview: String,
)

data class AxArchiveFileEntry(
  val path: String,
  val sizeBytes: Long,
  val isScript: Boolean,
  val isTextReadable: Boolean,
)

data class AxToggleableOption(
  val key: String,
  val title: String,
  val category: String,
  val enableCommand: String,
  val disableCommand: String,
  val description: String,
)

data class AxExtensionModule(
  val id: String,
  val name: String,
  val version: String,
  val versionCode: Int,
  val author: String,
  val description: String,
  val sourceZipName: String,
  val zipSizeBytes: Long = 0L,
  val installedDirName: String,
  val importedAtEpochMs: Long,
  val enabled: Boolean = false,
  val pluginType: AxPluginType,
  val compatibility: AxCompatibilityStatus,
  val compatibilityReason: String,
  val androidCompatLabel: String,
  val deviceCompatLabel: String,
  val requiresShizuku: Boolean,
  val requiresRoot: Boolean,
  val hasWebUi: Boolean,
  val webUiEntryPath: String? = null,
  val bannerPath: String? = null,
  val hasActionSh: Boolean = false,
  val minApi: Int? = null,
  val maxApi: Int? = null,
  val totalFilesCount: Int = 0,
  val allFiles: List<AxArchiveFileEntry> = emptyList(),
  val scriptFiles: List<AxScriptPreview> = emptyList(),
  val dependencies: List<AxPluginDependency> = emptyList(),
  val applyCommands: List<String> = emptyList(),
  val disableCommands: List<String> = emptyList(),
  val propOverrides: List<Pair<String, String>> = emptyList(),
  val activeOptionKeys: Set<String> = emptySet(),
  val lastExecutionLog: String = "",
) {
  val isSupportedForActivation: Boolean
    get() =
      compatibility != AxCompatibilityStatus.INCOMPATIBLE &&
        pluginType != AxPluginType.UNSUPPORTED &&
        (applyCommands.isNotEmpty() || propOverrides.isNotEmpty() || scriptFiles.isNotEmpty() || hasWebUi)
}
