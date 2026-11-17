package com.example.guardian

import android.content.Context
import com.example.model.GameHealthSummary
import com.example.model.GuardianCause
import com.example.model.GuardianOutcome
import com.example.model.GuardianPipelineStage
import com.example.model.GuardianReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class StrategyHistoryEntry(
  val attempts: Int = 0,
  val successes: Int = 0,
  val failures: Int = 0,
  val consecutiveFailures: Int = 0,
  val lastAttemptMs: Long = 0L,
)

data class GuardianStrategyPick(
  val strategyKey: String,
  val description: String,
  val avoidedRepeated: Boolean,
)

data class GuardianMemorySnapshot(
  val currentStage: GuardianPipelineStage = GuardianPipelineStage.IDLE,
  val lastCauseDetected: GuardianCause = GuardianCause.STABLE,
  val avoidedRepeatsCount: Int = 0,
  val reports: List<GuardianReport> = emptyList(),
  val gameHealthMap: Map<String, GameHealthSummary> = emptyMap(),
)

object GuardianMemoryStore {
  private const val PREFS_NAME = "infinit_guardian_memory_v1"
  private const val KEY_STRATEGIES = "strategy_stats_json"
  private const val KEY_GAMES = "game_health_json"
  private const val KEY_REPORTS = "reports_json"
  private const val KEY_AVOIDED = "avoided_repeats"
  private const val FAILED_COOLDOWN_MS = 15L * 60L * 1000L // 15 min memory window

  private val lock = Any()
  @Volatile private var loaded = false

  private val strategyHistory = mutableMapOf<String, StrategyHistoryEntry>()
  private val gameHealth = mutableMapOf<String, GameHealthSummary>()
  private val reportsHistory = mutableListOf<GuardianReport>()
  private val fpsSamplesByPackage = mutableMapOf<String, ArrayDeque<Int>>()
  private var avoidedCount = 0

  private val _snapshot = MutableStateFlow(GuardianMemorySnapshot())
  val snapshot: StateFlow<GuardianMemorySnapshot> = _snapshot.asStateFlow()

  fun ensureLoaded(context: Context) {
    if (loaded) return
    synchronized(lock) {
      if (loaded) return
      val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      avoidedCount = prefs.getInt(KEY_AVOIDED, 0)

      runCatching {
        val stratRaw = prefs.getString(KEY_STRATEGIES, null)
        if (!stratRaw.isNullOrBlank()) {
          val obj = JSONObject(stratRaw)
          obj.keys().forEach { k ->
            val e = obj.optJSONObject(k) ?: return@forEach
            strategyHistory[k] =
              StrategyHistoryEntry(
                attempts = e.optInt("attempts", 0),
                successes = e.optInt("successes", 0),
                failures = e.optInt("failures", 0),
                consecutiveFailures = e.optInt("consecutiveFailures", 0),
                lastAttemptMs = e.optLong("lastAttemptMs", 0L),
              )
          }
        }
      }

      runCatching {
        val gamesRaw = prefs.getString(KEY_GAMES, null)
        if (!gamesRaw.isNullOrBlank()) {
          val obj = JSONObject(gamesRaw)
          obj.keys().forEach { pkg ->
            val g = obj.optJSONObject(pkg) ?: return@forEach
            val causeId = g.optString("lastCause", "")
            val lowest = g.optInt("lowestFps", -1).takeIf { it > 0 }
            gameHealth[pkg] =
              GameHealthSummary(
                packageName = pkg,
                gameLabel = g.optString("gameLabel", pkg),
                lagEventsCount = g.optInt("lagEventsCount", 0),
                resolvedFixesCount = g.optInt("resolvedFixesCount", 0),
                failedFixesCount = g.optInt("failedFixesCount", 0),
                lowestFpsSeen = lowest,
                lastCause = GuardianCause.entries.firstOrNull { it.id == causeId },
                lastReportSummary = g.optString("lastReportSummary", ""),
                lastUpdatedLabel = g.optString("lastUpdatedLabel", ""),
              )
          }
        }
      }

      runCatching {
        val repRaw = prefs.getString(KEY_REPORTS, null)
        if (!repRaw.isNullOrBlank()) {
          val arr = JSONArray(repRaw)
          for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val causeId = r.optString("probableCause", GuardianCause.STABLE.id)
            val outcomeName = r.optString("outcome", GuardianOutcome.RESOLVED.name)
            reportsHistory +=
              GuardianReport(
                id = r.optString("id", "rep_$i"),
                timestampLabel = r.optString("timestampLabel", "--:--:--"),
                packageName = r.optString("packageName", "").ifBlank { null },
                gameLabel = r.optString("gameLabel", "").ifBlank { null },
                detectedProblem = r.optString("detectedProblem", ""),
                probableCause = GuardianCause.entries.firstOrNull { it.id == causeId } ?: GuardianCause.STABLE,
                causeDetail = r.optString("causeDetail", ""),
                correctionApplied = r.optString("correctionApplied", ""),
                strategyKey = r.optString("strategyKey", ""),
                outcome = GuardianOutcome.entries.firstOrNull { it.name == outcomeName } ?: GuardianOutcome.RESOLVED,
                verificationResult = r.optString("verificationResult", ""),
                avoidedRepeatedStrategy = r.optBoolean("avoidedRepeatedStrategy", false),
              )
          }
        }
      }

      loaded = true
      emitSnapshot()
    }
  }

  fun setStage(stage: GuardianPipelineStage) {
    _snapshot.update { it.copy(currentStage = stage) }
  }

  /**
   * Records a fresh FPS reading for a package and checks whether FPS dropped sharply compared
   * to the rolling baseline (>= 28% drop from stable baseline >= 55 FPS).
   */
  fun recordFpsAndCheckSuddenDrop(packageName: String?, fps: Int?): Pair<Boolean, Int?> {
    if (fps == null || fps !in 1..240) return false to null
    val key = packageName ?: "__system__"
    synchronized(lock) {
      val queue = fpsSamplesByPackage.getOrPut(key) { ArrayDeque() }
      val baseline =
        if (queue.size >= 3) {
          queue.average().toInt()
        } else {
          null
        }
      val suddenDrop =
        baseline != null &&
          baseline >= 55 &&
          fps <= (baseline * 0.72f).toInt()
      if (queue.size >= 8) queue.removeFirst()
      queue.addLast(fps)
      return suddenDrop to baseline
    }
  }

  /**
   * Picks the most reliable non-failed strategy for (cause, packageName).
   * Skips strategies that recently failed to solve the issue.
   */
  fun pickBestStrategy(
    context: Context,
    cause: GuardianCause,
    packageName: String?,
    candidates: List<Pair<String, String>>,
  ): GuardianStrategyPick {
    ensureLoaded(context)
    if (candidates.isEmpty()) {
      return GuardianStrategyPick("NONE", "Monitoramento preventivo", false)
    }
    val now = System.currentTimeMillis()
    synchronized(lock) {
      val scopePkg = packageName ?: "global"
      var skippedAny = false
      for ((key, desc) in candidates) {
        val memKey = "${cause.id}|$scopePkg|$key"
        val entry = strategyHistory[memKey]
        val recentlyFailed =
          entry != null &&
            entry.consecutiveFailures >= 1 &&
            (now - entry.lastAttemptMs) < FAILED_COOLDOWN_MS
        if (!recentlyFailed) {
          if (skippedAny) {
            avoidedCount++
            persistLocked(context)
            emitSnapshot()
          }
          return GuardianStrategyPick(
            strategyKey = key,
            description = desc,
            avoidedRepeated = skippedAny,
          )
        }
        skippedAny = true
      }

      // All candidates were marked failed recently; pick the least-recently attempted one
      val fallback =
        candidates.minByOrNull { (key, _) ->
          strategyHistory["${cause.id}|$scopePkg|$key"]?.lastAttemptMs ?: 0L
        } ?: candidates.first()
      return GuardianStrategyPick(
        strategyKey = fallback.first,
        description = fallback.second,
        avoidedRepeated = skippedAny,
      )
    }
  }

  fun recordFixVerification(
    context: Context,
    report: GuardianReport,
  ) {
    ensureLoaded(context)
    val now = System.currentTimeMillis()
    val succeeded =
      report.outcome == GuardianOutcome.RESOLVED ||
        report.outcome == GuardianOutcome.IMPROVED ||
        report.outcome == GuardianOutcome.PREVENTIVE

    synchronized(lock) {
      val scopePkg = report.packageName ?: "global"
      if (report.strategyKey.isNotBlank() && report.outcome != GuardianOutcome.PREVENTIVE) {
        val memKey = "${report.probableCause.id}|$scopePkg|${report.strategyKey}"
        val prev = strategyHistory[memKey] ?: StrategyHistoryEntry()
        strategyHistory[memKey] =
          StrategyHistoryEntry(
            attempts = prev.attempts + 1,
            successes = prev.successes + if (succeeded) 1 else 0,
            failures = prev.failures + if (succeeded) 0 else 1,
            consecutiveFailures = if (succeeded) 0 else prev.consecutiveFailures + 1,
            lastAttemptMs = now,
          )
      }

      val pkg = report.packageName
      if (!pkg.isNullOrBlank()) {
        val label = report.gameLabel ?: pkg.substringAfterLast('.')
        val prevGame = gameHealth[pkg] ?: GameHealthSummary(packageName = pkg, gameLabel = label)
        val ts = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        val fpsFromProblem =
          Regex("(\\d{1,3})\\s*FPS").find(report.detectedProblem)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val nextLowest =
          when {
            fpsFromProblem != null && prevGame.lowestFpsSeen != null ->
              minOf(fpsFromProblem, prevGame.lowestFpsSeen)
            fpsFromProblem != null -> fpsFromProblem
            else -> prevGame.lowestFpsSeen
          }
        gameHealth[pkg] =
          prevGame.copy(
            gameLabel = label,
            lagEventsCount =
              prevGame.lagEventsCount +
                if (report.outcome != GuardianOutcome.PREVENTIVE && report.probableCause != GuardianCause.STABLE) 1 else 0,
            resolvedFixesCount = prevGame.resolvedFixesCount + if (succeeded) 1 else 0,
            failedFixesCount = prevGame.failedFixesCount + if (succeeded) 0 else 1,
            lowestFpsSeen = nextLowest,
            lastCause = report.probableCause,
            lastReportSummary = "${report.outcome.label}: ${report.verificationResult}",
            lastUpdatedLabel = ts,
          )
      }

      reportsHistory.removeAll { it.id == report.id }
      reportsHistory.add(0, report)
      while (reportsHistory.size > 10) {
        reportsHistory.removeAt(reportsHistory.lastIndex)
      }

      persistLocked(context, lastCause = report.probableCause)
      emitSnapshot(lastCause = report.probableCause)
    }
  }

  fun dismissReport(context: Context, reportId: String) {
    ensureLoaded(context)
    synchronized(lock) {
      reportsHistory.removeAll { it.id == reportId }
      persistLocked(context)
      emitSnapshot()
    }
  }

  fun removeGame(context: Context, packageName: String) {
    ensureLoaded(context)
    synchronized(lock) {
      if (gameHealth.remove(packageName) != null) {
        fpsSamplesByPackage.remove(packageName)
        persistLocked(context)
        emitSnapshot()
      }
    }
  }

  fun clearAll(context: Context) {
    synchronized(lock) {
      strategyHistory.clear()
      gameHealth.clear()
      reportsHistory.clear()
      fpsSamplesByPackage.clear()
      avoidedCount = 0
      loaded = true
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
      _snapshot.value = GuardianMemorySnapshot()
    }
  }

  private fun emitSnapshot(lastCause: GuardianCause? = null) {
    _snapshot.update { cur ->
      cur.copy(
        lastCauseDetected = lastCause ?: reportsHistory.firstOrNull()?.probableCause ?: cur.lastCauseDetected,
        avoidedRepeatsCount = avoidedCount,
        reports = reportsHistory.toList(),
        gameHealthMap = gameHealth.toMap(),
      )
    }
  }

  private fun persistLocked(context: Context, lastCause: GuardianCause? = null) {
    val stratObj = JSONObject()
    strategyHistory.entries.take(60).forEach { (k, v) ->
      stratObj.put(
        k,
        JSONObject().apply {
          put("attempts", v.attempts)
          put("successes", v.successes)
          put("failures", v.failures)
          put("consecutiveFailures", v.consecutiveFailures)
          put("lastAttemptMs", v.lastAttemptMs)
        },
      )
    }

    val gamesObj = JSONObject()
    gameHealth.values.take(30).forEach { g ->
      gamesObj.put(
        g.packageName,
        JSONObject().apply {
          put("gameLabel", g.gameLabel)
          put("lagEventsCount", g.lagEventsCount)
          put("resolvedFixesCount", g.resolvedFixesCount)
          put("failedFixesCount", g.failedFixesCount)
          put("lowestFps", g.lowestFpsSeen ?: -1)
          put("lastCause", g.lastCause?.id ?: "")
          put("lastReportSummary", g.lastReportSummary)
          put("lastUpdatedLabel", g.lastUpdatedLabel)
        },
      )
    }

    val repArr = JSONArray()
    reportsHistory.take(10).forEach { r ->
      repArr.put(
        JSONObject().apply {
          put("id", r.id)
          put("timestampLabel", r.timestampLabel)
          put("packageName", r.packageName ?: "")
          put("gameLabel", r.gameLabel ?: "")
          put("detectedProblem", r.detectedProblem)
          put("probableCause", r.probableCause.id)
          put("causeDetail", r.causeDetail)
          put("correctionApplied", r.correctionApplied)
          put("strategyKey", r.strategyKey)
          put("outcome", r.outcome.name)
          put("verificationResult", r.verificationResult)
          put("avoidedRepeatedStrategy", r.avoidedRepeatedStrategy)
        },
      )
    }

    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_STRATEGIES, stratObj.toString())
      .putString(KEY_GAMES, gamesObj.toString())
      .putString(KEY_REPORTS, repArr.toString())
      .putInt(KEY_AVOIDED, avoidedCount)
      .apply()
  }
}
