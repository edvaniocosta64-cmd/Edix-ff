package com.example.ax

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.model.AxExtensionModule
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
import com.example.ui.theme.WarningAmber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AxWebUiActivity : ComponentActivity() {

  companion object {
    private const val EXTRA_PLUGIN_ID = "extra_plugin_id"
    private const val VIRTUAL_HOST = "mui.kernelsu.org"
    private const val VIRTUAL_ORIGIN = "https://$VIRTUAL_HOST"
    private const val BRIDGE_MODULE_PATH = "/infinit-kernelsu-bridge.js"

    fun createIntent(context: Context, pluginId: String): Intent =
      Intent(context, AxWebUiActivity::class.java).putExtra(EXTRA_PLUGIN_ID, pluginId)
  }

  private lateinit var extensionManager: AxExtensionManager
  private lateinit var shizukuBridge: ShizukuBridge
  private var webViewRef: WebView? = null
  private var activeModule: AxExtensionModule? = null
  private var webBaseDir: File? = null
  private var moduleRootDir: File? = null

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )

    extensionManager = AxExtensionManager(this)
    shizukuBridge = ShizukuBridge(this)

    val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
    val module = extensionManager.loadInstalledPlugins().firstOrNull { it.id == pluginId }
    if (module == null || !module.hasWebUi) {
      Toast.makeText(this, "WebUI não encontrada neste módulo.", Toast.LENGTH_SHORT).show()
      finish()
      return
    }
    activeModule = module
    val modDir = extensionManager.getPluginDirectory(module)
    moduleRootDir = modDir

    val entryRel = module.webUiEntryPath ?: "webroot/index.html"
    val entryFile = File(modDir, entryRel)
    val baseDir =
      if (entryRel.startsWith("webroot/", ignoreCase = true)) File(modDir, "webroot")
      else modDir
    webBaseDir = baseDir
    val relativeStartPath =
      if (entryRel.startsWith("webroot/", ignoreCase = true)) {
        entryRel.removePrefix("webroot/").removePrefix("WEBROOT/").ifBlank { "index.html" }
      } else {
        entryRel
      }

    if (!entryFile.exists()) {
      Toast.makeText(this, "Arquivo HTML da WebUI ausente no pacote.", Toast.LENGTH_SHORT).show()
      finish()
      return
    }

    // Sync workspace scripts & configs for Shizuku UID 2000 compatibility
    lifecycleScope.launch {
      if (shizukuBridge.status.value.connectionState == ShizukuConnectionState.CONNECTED) {
        shizukuBridge.syncPluginWorkspaceToTmp(module, modDir)
      }
    }

    val startUrl = "$VIRTUAL_ORIGIN/$relativeStartPath"

    setContent {
      InfinitBoosterTheme {
        val shizukuStatus by shizukuBridge.status.collectAsStateWithLifecycle()
        val shizukuConnected = shizukuStatus.connectionState == ShizukuConnectionState.CONNECTED
        var canGoBack by remember { mutableStateOf(false) }

        BackHandler(enabled = canGoBack) {
          webViewRef?.goBack()
        }

        Column(
          modifier =
            Modifier.fillMaxSize()
              .background(VoidObsidian)
              .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
          // Compact Host Bar
          Surface(
            modifier = Modifier.fillMaxWidth(),
            color = VoidSurface,
            border = BorderStroke(1.dp, VoidBorder),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = LimitlessCyan,
                modifier = Modifier.size(20.dp),
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = module.name,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = ColdWhite,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = "WebUI • v${module.version} • ${module.author}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MutedLavender,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }

              Surface(
                onClick = {
                  if (!shizukuConnected) {
                    shizukuBridge.requestOrConnect(this@AxWebUiActivity)
                  } else {
                    lifecycleScope.launch {
                      shizukuBridge.syncPluginWorkspaceToTmp(module, modDir)
                      Toast.makeText(
                        this@AxWebUiActivity,
                        "Shizuku conectado e sincronizado!",
                        Toast.LENGTH_SHORT,
                      ).show()
                    }
                  }
                },
                shape = RoundedCornerShape(10.dp),
                color = (if (shizukuConnected) NeonSuccess else WarningAmber).copy(alpha = 0.16f),
                border =
                  BorderStroke(
                    1.dp,
                    if (shizukuConnected) NeonSuccess.copy(alpha = 0.55f) else WarningAmber,
                  ),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("webui_shizuku_pill"),
              ) {
                Text(
                  text = if (shizukuConnected) "SHIZUKU ON" else "CONECTAR",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (shizukuConnected) NeonSuccess else WarningAmber,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
              }

              IconButton(
                onClick = { webViewRef?.reload() },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("webui_reload_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Refresh,
                  contentDescription = "Recarregar WebUI",
                  tint = HollowPurpleBright,
                )
              }

              IconButton(
                onClick = { finish() },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("webui_close_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = "Fechar WebUI",
                  tint = ColdWhite,
                )
              }
            }
          }

          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
              modifier = Modifier.fillMaxSize().testTag("ax_webui_webview"),
              factory = { ctx ->
                WebView(ctx).apply {
                  setBackgroundColor(Color.parseColor("#080812"))
                  settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                  }

                  val jsBridge = AxWebUiJsBridge(this)
                  addJavascriptInterface(jsBridge, "ksu")
                  addJavascriptInterface(jsBridge, "axmanager")
                  addJavascriptInterface(jsBridge, "AxManager")

                  webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                  }

                  webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                      view: WebView?,
                      request: WebResourceRequest?,
                    ): WebResourceResponse? {
                      val uri = request?.url ?: return null
                      val host = uri.host.orEmpty()
                      val scheme = uri.scheme.orEmpty()

                      if (scheme == "ksu" && host == "icon") {
                        val pkg = uri.path?.trimStart('/').orEmpty()
                        return serveAppIcon(pkg)
                      }

                      if (host == VIRTUAL_HOST || host == "ax.local") {
                        val path = uri.path.orEmpty()
                        if (path == BRIDGE_MODULE_PATH || path.endsWith("/kernelsu.js") || path == "/kernelsu.js") {
                          return WebResourceResponse(
                            "application/javascript",
                            "UTF-8",
                            200,
                            "OK",
                            corsHeaders(),
                            ByteArrayInputStream(KERNELS_ESM_SHIM.toByteArray(Charsets.UTF_8)),
                          )
                        }
                        if (path.startsWith("/icon/")) {
                          val pkg = path.removePrefix("/icon/").trim('/')
                          return serveAppIcon(pkg)
                        }
                        return serveLocalPluginAsset(path)
                      }
                      return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                      super.onPageStarted(view, url, favicon)
                      canGoBack = view?.canGoBack() == true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                      super.onPageFinished(view, url)
                      canGoBack = view?.canGoBack() == true
                      view?.evaluateJavascript(PAGE_READY_BRIDGE_PATCH, null)
                    }
                  }

                  webViewRef = this
                  loadUrl(startUrl)
                }
              },
            )
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    shizukuBridge.refreshState()
    val mod = activeModule
    val dir = moduleRootDir
    if (mod != null && dir != null && shizukuBridge.status.value.connectionState == ShizukuConnectionState.CONNECTED) {
      lifecycleScope.launch { shizukuBridge.syncPluginWorkspaceToTmp(mod, dir) }
    }
  }

  override fun onDestroy() {
    webViewRef?.destroy()
    webViewRef = null
    shizukuBridge.release()
    super.onDestroy()
  }

  private fun corsHeaders(): Map<String, String> =
    mapOf(
      "Access-Control-Allow-Origin" to "*",
      "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
      "Cache-Control" to "no-cache",
    )

  private fun serveLocalPluginAsset(rawPath: String): WebResourceResponse? {
    val base = webBaseDir ?: return null
    val modRoot = moduleRootDir
    val cleanRel = rawPath.trimStart('/').ifBlank { "index.html" }

    val candidate =
      resolveSafe(base, cleanRel)?.takeIf { it.exists() && it.isFile }
        ?: modRoot?.let { resolveSafe(it, cleanRel) }?.takeIf { it.exists() && it.isFile }
        ?: return null
    val mime = detectMimeType(candidate.name)

    if (mime == "text/html") {
      return try {
        val html = candidate.readText(Charsets.UTF_8)
        val injected = injectImportMapAndHelpers(html)
        WebResourceResponse(
          "text/html",
          "UTF-8",
          200,
          "OK",
          corsHeaders(),
          ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)),
        )
      } catch (_: Exception) {
        null
      }
    }

    return try {
      WebResourceResponse(
        mime,
        if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "UTF-8" else null,
        200,
        "OK",
        corsHeaders(),
        FileInputStream(candidate),
      )
    } catch (_: Exception) {
      null
    }
  }

  private fun serveAppIcon(packageName: String): WebResourceResponse? {
    if (packageName.isBlank()) return null
    return try {
      val drawable = packageManager.getApplicationIcon(packageName)
      val bitmap =
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
          drawable.bitmap
        } else {
          val w = drawable.intrinsicWidth.coerceIn(48, 128)
          val h = drawable.intrinsicHeight.coerceIn(48, 128)
          Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
          }
        }
      val baos = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
      WebResourceResponse(
        "image/png",
        null,
        200,
        "OK",
        corsHeaders(),
        ByteArrayInputStream(baos.toByteArray()),
      )
    } catch (_: Exception) {
      null
    }
  }

  private fun resolveSafe(base: File, rel: String): File? {
    return try {
      val target = File(base, rel)
      val baseCanon = base.canonicalPath + File.separator
      val targetCanon = target.canonicalPath
      if (targetCanon.startsWith(baseCanon) || targetCanon == base.canonicalPath) target else null
    } catch (_: Exception) {
      null
    }
  }

  private fun injectImportMapAndHelpers(html: String): String {
    if (html.contains(BRIDGE_MODULE_PATH)) return html
    val injection =
      """
      <script type="importmap">{"imports":{"kernelsu":"$BRIDGE_MODULE_PATH","axmanager":"$BRIDGE_MODULE_PATH"}}</script>
      <script>$PAGE_READY_BRIDGE_PATCH</script>
      """.trimIndent()
    return when {
      html.contains("<head>", ignoreCase = true) ->
        html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>\n$injection")
      html.contains("<html>", ignoreCase = true) ->
        html.replaceFirst(Regex("<html>", RegexOption.IGNORE_CASE), "<html><head>$injection</head>")
      else -> "$injection\n$html"
    }
  }

  private fun detectMimeType(fileName: String): String {
    val lower = fileName.lowercase(Locale.US)
    return when {
      lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
      lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
      lower.endsWith(".css") -> "text/css"
      lower.endsWith(".json") -> "application/json"
      lower.endsWith(".png") -> "image/png"
      lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
      lower.endsWith(".webp") -> "image/webp"
      lower.endsWith(".svg") -> "image/svg+xml"
      lower.endsWith(".woff2") -> "font/woff2"
      lower.endsWith(".woff") -> "font/woff"
      lower.endsWith(".ttf") -> "font/ttf"
      lower.endsWith(".sh") || lower.endsWith(".txt") || lower.endsWith(".prop") -> "text/plain"
      else -> "application/octet-stream"
    }
  }

  inner class AxWebUiJsBridge(private val webView: WebView) {

    @JavascriptInterface
    fun exec(cmd: String, optionsJson: String?, callbackFuncName: String?) {
      val modId = activeModule?.id
      lifecycleScope.launch(Dispatchers.IO) {
        val (errno, stdout, stderr) = shizukuBridge.runRawPluginCommand(cmd, modId)
        val cb = callbackFuncName?.takeIf { it.isNotBlank() } ?: return@launch
        val js = "window[${JSONObject.quote(cb)}]?.($errno, ${JSONObject.quote(stdout)}, ${JSONObject.quote(stderr)});"
        runOnUiThread {
          webView.evaluateJavascript(js, null)
        }
      }
    }

    @JavascriptInterface
    fun spawn(
      command: String,
      argsJson: String?,
      optionsJson: String?,
      callbackFuncName: String?,
    ) {
      val argsList =
        try {
          val arr = JSONArray(argsJson ?: "[]")
          buildList {
            for (i in 0 until arr.length()) add(arr.optString(i))
          }
        } catch (_: Exception) {
          emptyList()
        }
      val fullCmd =
        if (argsList.isEmpty()) command
        else "$command ${argsList.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }}"
      val modId = activeModule?.id
      lifecycleScope.launch(Dispatchers.IO) {
        val (errno, stdout, stderr) = shizukuBridge.runRawPluginCommand(fullCmd, modId)
        val cb = callbackFuncName?.takeIf { it.isNotBlank() } ?: return@launch
        runOnUiThread {
          if (stdout.isNotEmpty()) {
            webView.evaluateJavascript(
              "window[${JSONObject.quote(cb)}]?.emit('stdout', ${JSONObject.quote(stdout)});",
              null,
            )
          }
          if (stderr.isNotEmpty()) {
            webView.evaluateJavascript(
              "window[${JSONObject.quote(cb)}]?.emit('stderr', ${JSONObject.quote(stderr)});",
              null,
            )
          }
          webView.evaluateJavascript(
            "window[${JSONObject.quote(cb)}]?.emit('exit', $errno);",
            null,
          )
        }
      }
    }

    @JavascriptInterface
    fun toast(msg: String?) {
      val clean = msg ?: return
      runOnUiThread {
        Toast.makeText(this@AxWebUiActivity, clean, Toast.LENGTH_SHORT).show()
      }
    }

    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
      runOnUiThread {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
          controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
          controller.show(WindowInsetsCompat.Type.systemBars())
        }
      }
    }

    @JavascriptInterface
    fun moduleInfo(): String {
      val mod = activeModule ?: return "{}"
      return JSONObject().apply {
        put("id", mod.id)
        put("name", mod.name)
        put("version", mod.version)
        put("versionCode", mod.versionCode)
        put("author", mod.author)
        put("moduleDir", "/data/local/tmp/infinit_ax/${mod.id}")
      }.toString()
    }

    @JavascriptInterface
    fun listPackages(type: String?): String {
      val pm = packageManager
      val apps =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
          @Suppress("DEPRECATION")
          pm.getInstalledApplications(0)
        }
      val mode = type?.lowercase(Locale.US) ?: "all"
      val filtered =
        apps.filter { info ->
          val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
          when (mode) {
            "user" -> !isSys
            "system" -> isSys
            else -> true
          }
        }.map { it.packageName }.sorted()
      return JSONArray(filtered).toString()
    }

    @JavascriptInterface
    fun getPackagesInfo(pkgsJson: String?): String {
      val pm = packageManager
      val arr = runCatching { JSONArray(pkgsJson ?: "[]") }.getOrNull() ?: JSONArray()
      val out = JSONArray()
      for (i in 0 until arr.length()) {
        val pkg = arr.optString(i)
        if (pkg.isBlank()) continue
        runCatching {
          val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
            } else {
              @Suppress("DEPRECATION")
              pm.getPackageInfo(pkg, 0)
            }
          val appInfo = info.applicationInfo
          val label = appInfo?.loadLabel(pm)?.toString() ?: pkg
          val isSys = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
          val vCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
          out.put(
            JSONObject().apply {
              put("packageName", pkg)
              put("appLabel", label)
              put("versionName", info.versionName ?: "")
              put("versionCode", vCode)
              put("isSystem", isSys)
            }
          )
        }
      }
      return out.toString()
    }

    @JavascriptInterface
    fun readFile(relativePath: String?): String {
      val mod = activeModule ?: return ""
      val rel = relativePath ?: return ""
      return extensionManager.readPluginFileText(mod, rel) ?: ""
    }

    @JavascriptInterface
    fun writeFile(relativePath: String?, content: String?): Boolean {
      val base = moduleRootDir ?: return false
      val rel = relativePath?.trimStart('/') ?: return false
      val target = resolveSafe(base, rel) ?: return false
      return try {
        target.parentFile?.mkdirs()
        target.writeText(content.orEmpty(), Charsets.UTF_8)
        true
      } catch (_: Exception) {
        false
      }
    }
  }
}

private const val PAGE_READY_BRIDGE_PATCH = """
(function() {
  if (window.__infinitAxBridgeReady) return;
  window.__infinitAxBridgeReady = true;
  if (!window.ksu) window.ksu = window.axmanager || window.AxManager;
  if (window.ksu && !window.ksu._wrappedExec && typeof window.ksu.exec === 'function') {
    const nativeKsu = window.ksu;
    window.ksu._wrappedExec = true;
    window.kernelsu = window.kernelsu || nativeKsu;
  }
})();
"""

private const val KERNELS_ESM_SHIM = """
let cbCounter = 0;
function nextCb(prefix) {
  cbCounter += 1;
  return prefix + '_' + Date.now() + '_' + cbCounter;
}
export function exec(command, options = {}) {
  return new Promise((resolve) => {
    const cb = nextCb('__ksu_exec');
    window[cb] = (errno, stdout, stderr) => {
      delete window[cb];
      resolve({ errno, stdout: stdout || '', stderr: stderr || '' });
    };
    if (window.ksu && typeof window.ksu.exec === 'function') {
      window.ksu.exec(String(command), JSON.stringify(options || {}), cb);
    } else {
      delete window[cb];
      resolve({ errno: 1, stdout: '', stderr: 'Bridge ksu indisponível' });
    }
  });
}
export function spawn(command, args = [], options = {}) {
  const listeners = { exit: [], error: [] };
  const streamListeners = { stdout: [], stderr: [] };
  const cb = nextCb('__ksu_spawn');
  window[cb] = {
    emit(type, payload) {
      if (type === 'stdout') streamListeners.stdout.forEach((fn) => fn(payload));
      else if (type === 'stderr') streamListeners.stderr.forEach((fn) => fn(payload));
      else if (type === 'exit') {
        listeners.exit.forEach((fn) => fn(payload));
        delete window[cb];
      }
    }
  };
  if (window.ksu && typeof window.ksu.spawn === 'function') {
    window.ksu.spawn(String(command), JSON.stringify(args || []), JSON.stringify(options || {}), cb);
  }
  return {
    stdout: { on(ev, fn) { if (ev === 'data') streamListeners.stdout.push(fn); } },
    stderr: { on(ev, fn) { if (ev === 'data') streamListeners.stderr.push(fn); } },
    on(ev, fn) { if (listeners[ev]) listeners[ev].push(fn); }
  };
}
export function fullScreen(isFullScreen) {
  if (window.ksu && typeof window.ksu.fullScreen === 'function') window.ksu.fullScreen(!!isFullScreen);
}
export function toast(message) {
  if (window.ksu && typeof window.ksu.toast === 'function') window.ksu.toast(String(message ?? ''));
}
export function moduleInfo() {
  try { return JSON.parse(window.ksu.moduleInfo()); } catch (_) { return {}; }
}
export function listPackages(type = 'all') {
  try { return JSON.parse(window.ksu.listPackages(String(type || 'all'))); } catch (_) { return []; }
}
export function getPackagesInfo(pkgs = []) {
  try { return JSON.parse(window.ksu.getPackagesInfo(JSON.stringify(pkgs || []))); } catch (_) { return []; }
}
export default { exec, spawn, fullScreen, toast, moduleInfo, listPackages, getPackagesInfo };
"""
