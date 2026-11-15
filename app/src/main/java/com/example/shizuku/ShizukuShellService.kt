package com.example.shizuku

import android.os.Process
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

@Keep
class ShizukuShellService : IShizukuShellService.Stub() {

  override fun destroy() {
    exitProcess(0)
  }

  override fun getUid(): Int = Process.myUid()

  override fun runShellCommand(command: String): String {
    return try {
      val process = ProcessBuilder("sh", "-c", command)
        .redirectErrorStream(true)
        .start()
      val output = StringBuilder()
      BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
          if (output.isNotEmpty()) output.append('\n')
          output.append(line)
        }
      }
      val exitCode = process.waitFor()
      if (output.isBlank()) "exit=$exitCode" else "exit=$exitCode | $output"
    } catch (e: Exception) {
      "error=${e.message ?: "unknown"}"
    }
  }
}
