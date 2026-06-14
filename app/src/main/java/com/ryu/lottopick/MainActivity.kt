package com.ryu.lottopick

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryu.lottopick.ui.AppTheme
import com.ryu.lottopick.ui.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow
import kotlin.random.Random

// ============================================================
// Pure Kotlin logic (ported from RYU LOTTO TOOL HTML script)
// ============================================================

object LottoLogic {

    /** Korean lotto ball color band by number (matches original CSS). */
    fun ballColor(num: Int): Color = when {
        num <= 10 -> Color(0xFFFACC15) // n1to10  yellow
        num <= 20 -> Color(0xFF60A5FA) // n11to20 blue
        num <= 30 -> Color(0xFFF87171) // n21to30 red
        num <= 40 -> Color(0xFF94A3B8) // n31to40 gray
        else -> Color(0xFF86EFAC)      // n41to45 green
    }

    /** Text color on the ball (red/gray bands use white in original). */
    fun ballTextColor(num: Int): Color = when {
        num in 21..40 -> Color.White
        else -> Color(0xFF111827)
    }

    /**
     * Parses a free-form number string (split on whitespace/commas),
     * validates 1..45 integers and dedupes + sorts.
     * Throws IllegalArgumentException with a Korean message on bad input.
     */
    fun parseNumberInput(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val numbers = parts.map {
            it.toIntOrNull() ?: throw IllegalArgumentException("숫자만 입력해주세요.")
        }
        if (numbers.any { it < 1 || it > 45 }) {
            throw IllegalArgumentException("번호는 1부터 45까지만 가능합니다.")
        }
        return numbers.toSortedSet().toList()
    }

    /** Stable comma key for dedup of generated lines. */
    fun toKey(numbers: List<Int>): String = numbers.sorted().joinToString(",")

    /**
     * Generates a single 6-number line honoring include/exclude sets.
     * Throws IllegalArgumentException (Korean) when constraints are invalid.
     */
    fun generateSingleLine(
        includeNums: List<Int>,
        excludeNums: List<Int>,
        rnd: Random = Random.Default
    ): List<Int> {
        if (includeNums.size > 6) {
            throw IllegalArgumentException("포함 번호는 최대 6개까지 가능합니다.")
        }
        val includeSet = includeNums.toSet()
        val excludeSet = excludeNums.toSet()
        for (num in includeSet) {
            if (excludeSet.contains(num)) {
                throw IllegalArgumentException("같은 번호를 포함과 제외에 동시에 넣을 수 없습니다.")
            }
        }
        val candidates = (1..45).filter { it !in includeSet && it !in excludeSet }
        val needCount = 6 - includeNums.size
        if (candidates.size < needCount) {
            throw IllegalArgumentException("제외 번호가 너무 많아서 6개를 만들 수 없습니다.")
        }
        val picked = includeNums.toMutableList()
        val pool = candidates.toMutableList()
        while (picked.size < 6) {
            val index = rnd.nextInt(pool.size)
            picked.add(pool[index])
            pool.removeAt(index)
        }
        return picked.sorted()
    }

    /**
     * Generates [lineCount] distinct lines (dedup via key), with a guard
     * limit mirroring the original's 5000-iteration cap.
     */
    fun recommend(
        includeNums: List<Int>,
        excludeNums: List<Int>,
        lineCount: Int,
        rnd: Random = Random.Default
    ): List<List<Int>> {
        val results = mutableListOf<List<Int>>()
        val usedKeys = HashSet<String>()
        var guard = 0
        while (results.size < lineCount && guard < 5000) {
            val line = generateSingleLine(includeNums, excludeNums, rnd)
            val key = toKey(line)
            if (usedKeys.add(key)) {
                results.add(line)
            }
            guard++
        }
        return results
    }
}

// ============================================================
// 상세 추첨 — 역대 데이터 기반 통계 + 가중 추첨
// ============================================================

/** 핫/콜드 가중 모드. EVEN=완전 랜덤, HOT=최다출현 가중, COLD=최소출현 가중. */
enum class PickMode { EVEN, HOT, COLD }

/**
 * 역대 전체 회차에서 뽑아낸 통계.
 * - [freq]    번호(1~45) → 역대 본번호 출현 횟수 (보너스 제외)
 * - [lastSeenRound] 번호 → 가장 최근에 나온 회차
 * - [pastWinningKeys] 과거 1등 6개 조합의 정렬 키 집합("1,2,3,..." 형태) — 완전 재현 회피용
 */
data class LottoStats(
    val freq: Map<Int, Int>,
    val lastSeenRound: Map<Int, Int>,
    val latestRound: Int,
    val totalDraws: Int,
    val pastWinningKeys: Set<String>
) {
    /** 역대 최다 출현 순(핫 먼저). */
    val hotOrder: List<Int> get() = (1..45).sortedByDescending { freq[it] ?: 0 }
    /** 역대 최소 출현 순(콜드 먼저). */
    val coldOrder: List<Int> get() = (1..45).sortedBy { freq[it] ?: 0 }
    /** 이월수: 최신 회차 기준 몇 회째 안 나왔나. */
    fun gap(n: Int): Int = (latestRound - (lastSeenRound[n] ?: latestRound)).coerceAtLeast(0)
}

object LottoStatsCalc {
    /** 회차 모음에서 통계 1벌을 계산. 1180여 회차 기준 1ms 미만. */
    fun compute(draws: Collection<LottoDraw>): LottoStats {
        val freq = HashMap<Int, Int>()
        val lastSeen = HashMap<Int, Int>()
        val keys = HashSet<String>()
        var latest = 0
        for (d in draws) {
            if (d.round > latest) latest = d.round
            for (n in d.numbers) {
                freq[n] = (freq[n] ?: 0) + 1
                if (d.round > (lastSeen[n] ?: 0)) lastSeen[n] = d.round
            }
            keys.add(d.numbers.sorted().joinToString(","))
        }
        // 한 번도 안 나온 번호는 없지만, 누락 방지로 1~45 모두 0 채움
        for (n in 1..45) { freq.putIfAbsent(n, 0) }
        return LottoStats(freq, lastSeen, latest, draws.size, keys)
    }
}

object DetailLogic {

    /**
     * pool 에서 모드에 따라 가중 추첨한 index 1개.
     * [strength]는 가중 지수(power): 0이면 균등에 가깝고, 클수록 핫/콜드 쏠림이 강해진다.
     */
    private fun weightedIndex(pool: List<Int>, mode: PickMode, stats: LottoStats?, strength: Double, rnd: Random): Int {
        if (mode == PickMode.EVEN || stats == null || strength <= 0.0) return rnd.nextInt(pool.size)
        val freqs = stats.freq
        val minF = freqs.values.minOrNull() ?: 0
        val maxF = freqs.values.maxOrNull() ?: 0
        val weights = DoubleArray(pool.size) { i ->
            val f = freqs[pool[i]] ?: 0
            val base = when (mode) {
                PickMode.HOT -> (f - minF + 1).toDouble()
                PickMode.COLD -> (maxF - f + 1).toDouble()
                PickMode.EVEN -> 1.0
            }
            base.pow(strength)   // 지수가 클수록 핫/콜드 쏠림 강화
        }
        val total = weights.sum()
        if (total <= 0.0) return rnd.nextInt(pool.size)
        var r = rnd.nextDouble() * total
        for (i in pool.indices) {
            r -= weights[i]
            if (r <= 0.0) return i
        }
        return pool.size - 1
    }

    /** 한 줄 생성 (포함/제외 + 가중 모드). 제약 위반 시 한국어 메시지로 throw. */
    fun generateLine(
        includeNums: List<Int>,
        excludeNums: List<Int>,
        mode: PickMode,
        stats: LottoStats?,
        strength: Double,
        rnd: Random = Random.Default
    ): List<Int> {
        if (includeNums.size > 6) throw IllegalArgumentException("포함 번호는 최대 6개까지 가능합니다.")
        val includeSet = includeNums.toSet()
        val excludeSet = excludeNums.toSet()
        for (num in includeSet) {
            if (excludeSet.contains(num)) {
                throw IllegalArgumentException("같은 번호를 포함과 제외에 동시에 넣을 수 없습니다.")
            }
        }
        val candidates = (1..45).filter { it !in includeSet && it !in excludeSet }
        if (candidates.size < 6 - includeNums.size) {
            throw IllegalArgumentException("제외 조건이 너무 많아서 6개를 만들 수 없습니다.")
        }
        val picked = includeNums.toMutableList()
        val pool = candidates.toMutableList()
        while (picked.size < 6) {
            val idx = weightedIndex(pool, mode, stats, strength, rnd)
            picked.add(pool[idx])
            pool.removeAt(idx)
        }
        return picked.sorted()
    }

    /**
     * [lineCount]줄 생성. [avoidPastWinning]이면 과거 1등과 6개 완전 일치하는 줄은 버린다.
     * 가드 한도를 넉넉히(8000) 둬서 가중·회피로 후보가 줄어도 가능한 만큼 채운다.
     */
    fun recommend(
        includeNums: List<Int>,
        excludeNums: List<Int>,
        lineCount: Int,
        mode: PickMode,
        avoidPastWinning: Boolean,
        stats: LottoStats?,
        strength: Double,
        rnd: Random = Random.Default
    ): List<List<Int>> {
        val results = mutableListOf<List<Int>>()
        val usedKeys = HashSet<String>()
        var guard = 0
        while (results.size < lineCount && guard < 8000) {
            guard++
            val line = generateLine(includeNums, excludeNums, mode, stats, strength, rnd)
            val key = LottoLogic.toKey(line)
            if (avoidPastWinning && stats != null && stats.pastWinningKeys.contains(key)) continue
            if (usedKeys.add(key)) results.add(line)
        }
        return results
    }
}

// ============================================================
// Record model + SharedPreferences persistence (was localStorage)
// ============================================================

data class LottoRecord(
    val id: String,
    val numbers: List<Int>,
    val type: String,
    val memo: String,
    val createdAt: String
)

object RecordStore {
    private const val PREFS = "ryu_lotto_prefs"
    private const val KEY = "ryu-hyunwoo-lotto-records-v1"

    fun load(context: Context): List<LottoRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<LottoRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val numsArr = o.getJSONArray("numbers")
                val nums = ArrayList<Int>(numsArr.length())
                for (j in 0 until numsArr.length()) nums.add(numsArr.getInt(j))
                out.add(
                    LottoRecord(
                        id = o.optString("id"),
                        numbers = nums,
                        type = o.optString("type"),
                        memo = o.optString("memo", ""),
                        createdAt = o.optString("createdAt", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, records: List<LottoRecord>) {
        val arr = JSONArray()
        for (r in records) {
            val o = JSONObject()
            o.put("id", r.id)
            val numsArr = JSONArray()
            for (n in r.numbers) numsArr.put(n)
            o.put("numbers", numsArr)
            o.put("type", r.type)
            o.put("memo", r.memo)
            o.put("createdAt", r.createdAt)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}

// ============================================================
// 당첨번호 회차 조회 (winning-number lookup) — 동행복권 official API
// ============================================================

/** Parsed result of one lotto draw round. */
data class LottoDraw(
    val round: Int,
    val date: String,
    val numbers: List<Int>,
    val bonus: Int,
    val firstWinAmount: Long,
    val firstWinnerCount: Int,
    val totalSales: Long
)

/** Parsed result of one 연금복권720+ draw round. first/bonus are 6-digit strings (leading zeros matter). */
data class PensionDraw(
    val round: Int,
    val date: String,
    val group: Int,        // 1등 당첨 "조" (1~5)
    val first: String,     // 1등 6자리 번호
    val bonus: String      // 보너스 6자리 번호
)

object LottoApi {

    // dhlottery 공식 엔드포인트는 봇 차단(TLS 지문 등)으로 앱에서 막힌다.
    // 대신 동행복권 데이터를 GitHub Pages로 미러링하는 공개 JSON을 사용한다.
    // (전 세계 접근 가능, 매 추첨일 갱신) 예: .../results/1228.json
    // 내가 소유한 GitHub 저장소(papaya5rhw1984/lotto-data)의 전체 회차 JSON.
    // 매주 GitHub Actions가 자동 갱신한다. 앱은 오직 이 주소만 호출한다.
    private const val ALL_URL = "https://papaya5rhw1984.github.io/lotto-data/all.json"

    /** 앱에 내장된 과거 데이터(assets)와 온라인에서 받은 데이터를 합친 메모리 캐시. */
    @Volatile private var onlineSnapshot: Map<Int, LottoDraw>? = null

    /** Persists the latest-known round across launches. */
    private const val PREFS = "ryu_lotto_prefs"
    private const val KEY_LATEST = "latest_known_round"

    fun loadLatestKnown(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LATEST, 0)

    fun saveLatestKnown(context: Context, round: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LATEST, round).apply()
    }

    /** smok95/lotto 호환 JSON 한 건을 LottoDraw로 변환. */
    private fun parseDraw(o: JSONObject): LottoDraw {
        val numsArr = o.getJSONArray("numbers")
        val nums = (0 until numsArr.length()).map { numsArr.getInt(it) }.sorted()
        // 1등 정보는 divisions 배열의 첫 항목 (없거나 빈 객체일 수 있음)
        val firstDiv = o.optJSONArray("divisions")?.optJSONObject(0)
        // "2026-06-13T00:00:00Z" → "2026-06-13"
        val dateRaw = o.optString("date", "")
        val date = if (dateRaw.length >= 10) dateRaw.substring(0, 10) else dateRaw
        return LottoDraw(
            round = o.getInt("draw_no"),
            date = date,
            numbers = nums,
            bonus = o.getInt("bonus_no"),
            firstWinAmount = firstDiv?.optLong("prize", 0L) ?: 0L,
            firstWinnerCount = firstDiv?.optInt("winners", 0) ?: 0,
            totalSales = o.optLong("total_sales_amount", 0L)
        )
    }

    /** all.json(배열) 문자열을 회차 목록으로 파싱. 잘못된 항목은 건너뜀. */
    private fun parseAll(text: String): List<LottoDraw> {
        val arr = JSONArray(text)
        val list = ArrayList<LottoDraw>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("draw_no")) continue
            try { list.add(parseDraw(o)) } catch (_: Exception) { /* skip bad row */ }
        }
        return list
    }

    /** 앱에 내장된 전체 회차(오프라인). assets/lotto_all.json */
    fun loadBundled(context: Context): List<LottoDraw> =
        try {
            val text = context.assets.open("lotto_all.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseAll(text)
        } catch (e: Exception) {
            android.util.Log.e("LottoApi", "bundled load failed", e)
            emptyList()
        }

    /** 내 repo의 all.json을 받아 회차→정보 맵으로. 1회 받으면 메모리에 캐시. */
    @Throws(Exception::class)
    private fun ensureOnlineSnapshot(forceRefresh: Boolean): Map<Int, LottoDraw> {
        if (!forceRefresh) onlineSnapshot?.let { return it }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ALL_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 10000
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP " + code)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val map = parseAll(text).associateBy { it.round }
            onlineSnapshot = map
            return map
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Returns a specific round, fetching the online snapshot if needed.
     * Null when the round isn't available yet (not drawn).
     */
    @Throws(Exception::class)
    fun fetchRoundOrThrow(round: Int): LottoDraw? = ensureOnlineSnapshot(forceRefresh = false)[round]

    /** 최신 회차 1건 (항상 최신을 받기 위해 새로고침). */
    @Throws(Exception::class)
    fun fetchLatest(): LottoDraw? =
        ensureOnlineSnapshot(forceRefresh = true).values.maxByOrNull { it.round }

    // ── 연금복권720+ ──────────────────────────────────────────────────────
    // 내 저장소의 pension.json (매주 목요일 자동 갱신). 형식과 동작은 로또와 동일.
    private const val PENSION_URL = "https://papaya5rhw1984.github.io/lotto-data/pension.json"

    @Volatile private var pensionSnapshot: Map<Int, PensionDraw>? = null

    private fun parsePensionDraw(o: JSONObject): PensionDraw =
        PensionDraw(
            round = o.getInt("round"),
            date = o.optString("date", ""),
            group = o.optInt("group", 0),
            first = o.optString("first", ""),
            bonus = o.optString("bonus", "")
        )

    private fun parsePensionAll(text: String): List<PensionDraw> {
        val arr = JSONArray(text)
        val list = ArrayList<PensionDraw>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("round")) continue
            try { list.add(parsePensionDraw(o)) } catch (_: Exception) { /* skip bad row */ }
        }
        return list
    }

    /** 앱에 내장된 연금복권 전체 회차(오프라인). assets/pension_all.json */
    fun loadBundledPension(context: Context): List<PensionDraw> =
        try {
            val text = context.assets.open("pension_all.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            parsePensionAll(text)
        } catch (e: Exception) {
            android.util.Log.e("LottoApi", "bundled pension load failed", e)
            emptyList()
        }

    @Throws(Exception::class)
    private fun ensurePensionSnapshot(forceRefresh: Boolean): Map<Int, PensionDraw> {
        if (!forceRefresh) pensionSnapshot?.let { return it }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(PENSION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 10000
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP " + code)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val map = parsePensionAll(text).associateBy { it.round }
            pensionSnapshot = map
            return map
        } finally {
            conn?.disconnect()
        }
    }

    @Throws(Exception::class)
    fun fetchPensionRoundOrThrow(round: Int): PensionDraw? =
        ensurePensionSnapshot(forceRefresh = false)[round]

    @Throws(Exception::class)
    fun fetchPensionLatest(): PensionDraw? =
        ensurePensionSnapshot(forceRefresh = true).values.maxByOrNull { it.round }
}

/** Comma-grouped number with a trailing label, e.g. 1,234,567원. */
private fun formatWon(value: Long): String {
    if (value <= 0L) return "-"
    return String.format(java.util.Locale.US, "%,d", value) + "원"
}

private const val APP_BRAND = "로또 추천기"

private fun formatNow(): String {
    val c = java.util.Calendar.getInstance()
    return String.format(
        java.util.Locale.US,
        "%04d-%02d-%02d %02d:%02d",
        c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH),
        c.get(java.util.Calendar.HOUR_OF_DAY),
        c.get(java.util.Calendar.MINUTE)
    )
}

private fun newId(): String =
    System.currentTimeMillis().toString() + Random.nextInt(0, 0xFFFF).toString(16)

// ============================================================
// Activity
// ============================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                LottoApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LottoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var includeText by remember { mutableStateOf("") }
    var excludeText by remember { mutableStateOf("") }
    var lineCount by remember { mutableStateOf(5) }            // 1~50로 클램프
    var lineCountText by remember { mutableStateOf("5") }      // 줄 수 직접 입력 필드
    var statusText by remember { mutableStateOf("") }
    val recommendations = remember { mutableStateListOf<List<Int>>() }

    /** 줄 수 텍스트를 1~50으로 클램프해 lineCount 에 반영. */
    fun applyLineCount(raw: String) {
        lineCountText = raw.filter { it.isDigit() }.take(2)
        val n = lineCountText.toIntOrNull()
        if (n != null) lineCount = n.coerceIn(1, 50)
    }

    fun setLineCount(n: Int) {
        lineCount = n.coerceIn(1, 50)
        lineCountText = lineCount.toString()
    }

    // -------- 상세 추첨(detail) state --------
    var detailMode by remember { mutableStateOf(false) }   // false=일반, true=상세
    var avoidPastWinning by remember { mutableStateOf(true) }   // 역대 1등 조합 완전 일치 회피
    var avoidPrevRound by remember { mutableStateOf(false) }    // 직전 회차 번호 회피
    var pickMode by remember { mutableStateOf(PickMode.EVEN) }  // 균등/핫/콜드
    var weightStrength by remember { mutableStateOf(2f) }      // 핫/콜드 가중 세기(지수) 0~5
    var showStats by remember { mutableStateOf(false) }        // 핫/콜드 통계 패널 펼침

    var manualText by remember { mutableStateOf("") }
    var memoText by remember { mutableStateOf("") }

    val records = remember { mutableStateListOf<LottoRecord>().apply { addAll(RecordStore.load(context)) } }

    fun persist() = RecordStore.save(context, records)
    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    fun addRecord(numbers: List<Int>, type: String, memo: String) {
        records.add(
            0,
            LottoRecord(
                id = newId(),
                numbers = numbers.sorted(),
                type = type,
                memo = memo,
                createdAt = formatNow()
            )
        )
        persist()
    }

    fun doRecommend() {
        try {
            val inc = LottoLogic.parseNumberInput(includeText)
            val exc = LottoLogic.parseNumberInput(excludeText)
            val results = LottoLogic.recommend(inc, exc, lineCount)
            recommendations.clear()
            if (results.isEmpty()) {
                statusText = ""
                toast("추천 결과를 만들지 못했습니다.")
                return
            }
            recommendations.addAll(results)
            statusText = "포함 번호: ${if (inc.isEmpty()) "없음" else inc.joinToString(", ")} / " +
                "제외 번호: ${if (exc.isEmpty()) "없음" else exc.joinToString(", ")}"
        } catch (e: IllegalArgumentException) {
            recommendations.clear()
            statusText = ""
            toast(e.message ?: "입력 오류")
        }
    }

    fun saveCurrent() {
        if (recommendations.isEmpty()) {
            toast("먼저 추천 번호를 생성해주세요.")
            return
        }
        recommendations.forEachIndexed { index, line ->
            addRecord(line, "추천 저장", "${index + 1}줄 자동 저장")
        }
        toast("현재 추천 번호를 기록에 저장했습니다.")
    }

    fun copyCurrent() {
        if (recommendations.isEmpty()) {
            toast("복사할 추천 번호가 없습니다.")
            return
        }
        val text = recommendations.mapIndexed { index, line ->
            "[$APP_BRAND] ${index + 1}줄: ${line.joinToString(", ")}"
        }.joinToString("\n")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("lotto", text))
        toast("현재 추천 번호를 복사했습니다.")
    }

    fun shareCurrent() {
        if (recommendations.isEmpty()) {
            toast("공유할 추천 번호가 없습니다.")
            return
        }
        val text = recommendations.mapIndexed { index, line ->
            "[$APP_BRAND] ${index + 1}줄: ${line.joinToString(", ")}"
        }.joinToString("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "추천 번호 공유"))
    }

    // -------- 줄 단위(per-line) 복사/공유/저장 --------
    fun copyLine(line: List<Int>) {
        val text = "[$APP_BRAND] ${line.joinToString(", ")}"
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("lotto", text))
        toast("이 줄 번호를 복사했습니다.")
    }

    fun shareLine(line: List<Int>) {
        val text = "[$APP_BRAND] ${line.joinToString(", ")}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "추천 번호 공유"))
    }

    fun saveLineToRecords(line: List<Int>) {
        addRecord(line, "추천 저장", "추천 1줄 저장")
        toast("이 줄을 기록에 저장했습니다.")
    }

    fun saveManual() {
        try {
            val numbers = LottoLogic.parseNumberInput(manualText)
            if (numbers.size != 6) {
                throw IllegalArgumentException("기록할 번호는 정확히 6개여야 합니다.")
            }
            addRecord(numbers, "직접 입력", memoText.trim())
            manualText = ""
            memoText = ""
            toast("번호를 기록했습니다.")
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "입력 오류")
        }
    }

    fun deleteRecord(id: String) {
        val idx = records.indexOfFirst { it.id == id }
        if (idx >= 0) {
            records.removeAt(idx)
            persist()
        }
    }

    fun clearHistory() {
        records.clear()
        RecordStore.clear(context)
        toast("전체 기록을 삭제했습니다.")
    }

    // -------- Tabs: 0 = 번호 추천, 1 = 당첨번호 조회 --------
    var selectedTab by remember { mutableStateOf(0) }

    // -------- 당첨번호 회차 조회 state --------
    val drawCache = remember { mutableStateMapOf<Int, LottoDraw>() }

    // 역대 통계: drawCache(내장+온라인)가 바뀔 때만 다시 계산. 상세 추첨/통계 패널에서 사용.
    val lottoStats by remember {
        derivedStateOf { if (drawCache.isEmpty()) null else LottoStatsCalc.compute(drawCache.values) }
    }

    var latestRound by remember { mutableStateOf(0) }       // newest successfully fetched round
    var currentDraw by remember { mutableStateOf<LottoDraw?>(null) }
    var lookupLoading by remember { mutableStateOf(false) }  // network in-flight guard
    var lookupError by remember { mutableStateOf<String?>(null) }
    var lookupInput by remember { mutableStateOf("") }
    var lookupStarted by remember { mutableStateOf(false) }  // whether auto-load has run once

    // 마지막으로 온라인 데이터를 성공적으로 동기화한 시각 (하단 표시용)
    var lastSync by remember { mutableStateOf<String?>(null) }

    // 상세 추첨 실행 — 가중 모드 + 체크박스 옵션을 적용해 결과를 recommendations 에 채운다.
    fun doDetailRecommend() {
        try {
            val inc = LottoLogic.parseNumberInput(includeText)
            var exc = LottoLogic.parseNumberInput(excludeText)
            val stats = lottoStats

            // 직전 회차 번호 회피: 최신 회차의 본번호 6개를 제외에 추가(포함 번호는 유지).
            var prevApplied = emptyList<Int>()
            if (avoidPrevRound) {
                val prev = stats?.let { drawCache[it.latestRound]?.numbers } ?: emptyList()
                prevApplied = prev.filter { it !in inc }
                exc = (exc + prevApplied).distinct()
            }

            val results = DetailLogic.recommend(
                includeNums = inc,
                excludeNums = exc,
                lineCount = lineCount,
                mode = pickMode,
                avoidPastWinning = avoidPastWinning,
                stats = stats,
                strength = weightStrength.toDouble()
            )
            recommendations.clear()
            if (results.isEmpty()) {
                statusText = ""
                toast("조건이 너무 빡빡해서 번호를 만들지 못했어요.")
                return
            }
            recommendations.addAll(results)
            if (results.size < lineCount) {
                toast("조건에 맞는 ${results.size}줄만 생성했어요.")
            }
            val modeLabel = when (pickMode) {
                PickMode.HOT -> "확률 높은(핫) 번호 위주(세기 ${"%.1f".format(weightStrength)})"
                PickMode.COLD -> "잘 안 나온(콜드) 번호 위주(세기 ${"%.1f".format(weightStrength)})"
                PickMode.EVEN -> "균등 무작위"
            }
            val opts = buildList {
                if (avoidPastWinning) add("역대 1등 조합 회피")
                if (avoidPrevRound && prevApplied.isNotEmpty()) add("직전 회차(${prevApplied.joinToString(",")}) 회피")
            }
            statusText = "모드: $modeLabel" +
                (if (inc.isNotEmpty()) " · 포함: ${inc.joinToString(",")}" else "") +
                (if (opts.isNotEmpty()) " · ${opts.joinToString(" · ")}" else "")
        } catch (e: IllegalArgumentException) {
            recommendations.clear()
            statusText = ""
            toast(e.message ?: "입력 오류")
        }
    }

    /**
     * Loads the latest round from my GitHub repo. Falls back to the newest
     * bundled round (offline) when the network is unavailable.
     */
    fun loadLatest() {
        if (lookupLoading) return            // concurrent-fetch guard (throttle rule)
        lookupLoading = true
        lookupError = null
        scope.launch {
            try {
                val online = withContext(Dispatchers.IO) { LottoApi.fetchLatest() }
                if (online != null) {
                    drawCache[online.round] = online
                    if (online.round > latestRound) latestRound = online.round
                    currentDraw = online
                    lastSync = formatNow()
                    LottoApi.saveLatestKnown(context, online.round)
                } else {
                    // 온라인 비어있음 → 내장 최신으로 폴백
                    val localMax = drawCache.keys.maxOrNull()
                    if (localMax != null) currentDraw = drawCache[localMax]
                    else lookupError = "데이터를 불러오지 못했습니다."
                }
            } catch (e: Exception) {
                // 네트워크 오류 → 내장 데이터로 폴백 (오프라인에서도 동작)
                android.util.Log.e("LottoApi", "loadLatest online failed", e)
                val localMax = drawCache.keys.maxOrNull()
                if (localMax != null) currentDraw = drawCache[localMax]
                else lookupError = "조회 실패: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                lookupLoading = false
            }
        }
    }

    /** Fetches a specific round; cached rounds never hit the network. */
    fun lookupRound(round: Int) {
        if (round < 1) return
        if (lookupLoading) return            // never fire while one is in flight
        val cached = drawCache[round]
        if (cached != null) {                // cache hit → instant, no network call
            currentDraw = cached
            lookupError = null
            return
        }
        lookupLoading = true
        lookupError = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { LottoApi.fetchRoundOrThrow(round) }
                if (result != null) {
                    drawCache[result.round] = result
                    if (result.round > latestRound) {
                        latestRound = result.round
                        LottoApi.saveLatestKnown(context, result.round)
                    }
                    currentDraw = result
                } else {
                    lookupError = "${round}회차는 아직 추첨되지 않았습니다."
                }
            } catch (e: Exception) {
                android.util.Log.e("LottoApi", "lookup failed", e)
                lookupError = "조회 실패: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                lookupLoading = false
            }
        }
    }

    fun doManualLookup() {
        val n = lookupInput.trim().toIntOrNull()
        if (n == null || n < 1) {
            toast("회차 번호를 정확히 입력해주세요.")
            return
        }
        lookupRound(n)
    }

    // -------- 연금복권720+ 조회 state (로또와 동일 패턴) --------
    val pDrawCache = remember { mutableStateMapOf<Int, PensionDraw>() }
    var pLatestRound by remember { mutableStateOf(0) }
    var pCurrentDraw by remember { mutableStateOf<PensionDraw?>(null) }
    var pLookupLoading by remember { mutableStateOf(false) }
    var pLookupError by remember { mutableStateOf<String?>(null) }
    var pLookupInput by remember { mutableStateOf("") }
    var pLookupStarted by remember { mutableStateOf(false) }

    /** 연금복권 최신 회차 로드 (실패 시 내장 최신으로 폴백). */
    fun pLoadLatest() {
        if (pLookupLoading) return
        pLookupLoading = true
        pLookupError = null
        scope.launch {
            try {
                val online = withContext(Dispatchers.IO) { LottoApi.fetchPensionLatest() }
                if (online != null) {
                    pDrawCache[online.round] = online
                    if (online.round > pLatestRound) pLatestRound = online.round
                    pCurrentDraw = online
                    lastSync = formatNow()
                } else {
                    val localMax = pDrawCache.keys.maxOrNull()
                    if (localMax != null) pCurrentDraw = pDrawCache[localMax]
                    else pLookupError = "데이터를 불러오지 못했습니다."
                }
            } catch (e: Exception) {
                android.util.Log.e("LottoApi", "pension loadLatest failed", e)
                val localMax = pDrawCache.keys.maxOrNull()
                if (localMax != null) pCurrentDraw = pDrawCache[localMax]
                else pLookupError = "조회 실패: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                pLookupLoading = false
            }
        }
    }

    /** 연금복권 특정 회차 조회 (캐시/내장에 있으면 통신 없이 즉시). */
    fun pLookupRound(round: Int) {
        if (round < 1) return
        if (pLookupLoading) return
        val cached = pDrawCache[round]
        if (cached != null) {
            pCurrentDraw = cached
            pLookupError = null
            return
        }
        pLookupLoading = true
        pLookupError = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { LottoApi.fetchPensionRoundOrThrow(round) }
                if (result != null) {
                    pDrawCache[result.round] = result
                    if (result.round > pLatestRound) pLatestRound = result.round
                    pCurrentDraw = result
                } else {
                    pLookupError = "${round}회차는 아직 추첨되지 않았습니다."
                }
            } catch (e: Exception) {
                android.util.Log.e("LottoApi", "pension lookup failed", e)
                pLookupError = "조회 실패: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                pLookupLoading = false
            }
        }
    }

    fun pDoManualLookup() {
        val n = pLookupInput.trim().toIntOrNull()
        if (n == null || n < 1) {
            toast("회차 번호를 정확히 입력해주세요.")
            return
        }
        pLookupRound(n)
    }

    // 앱 시작 시 내장 과거 데이터를 캐시에 미리 로드 → 과거 회차는 오프라인·즉시 조회.
    LaunchedEffect(Unit) {
        val bundled = withContext(Dispatchers.IO) { LottoApi.loadBundled(context) }
        if (bundled.isNotEmpty()) {
            bundled.forEach { drawCache[it.round] = it }
            val maxR = bundled.maxOf { it.round }
            if (maxR > latestRound) latestRound = maxR
        }
        val pBundled = withContext(Dispatchers.IO) { LottoApi.loadBundledPension(context) }
        if (pBundled.isNotEmpty()) {
            pBundled.forEach { pDrawCache[it.round] = it }
            val pMax = pBundled.maxOf { it.round }
            if (pMax > pLatestRound) pLatestRound = pMax
        }
    }

    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val bgGradient = Brush.verticalGradient(
        colors = if (isDark) listOf(Brand.NightTop, Brand.NightBg)
        else listOf(Brand.CreamTop, Brand.Cream)
    )

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("🍀 로또 추천기", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Brand.Accent,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(bgGradient)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeroCard()

            // -------- Tab switcher (cute pill segmented) --------
            CuteTabBar(
                selected = selectedTab,
                onSelect = { idx ->
                    selectedTab = idx
                    if (idx == 1 && !lookupStarted) {   // auto-load latest on first entry
                        lookupStarted = true
                        loadLatest()
                    }
                    if (idx == 2 && !pLookupStarted) {  // 연금복권 최초 진입 시 자동 로드
                        pLookupStarted = true
                        pLoadLatest()
                    }
                }
            )

            if (selectedTab == 0) {
            // -------- Recommendation card --------
            SectionCard(title = "조건을 넣고 번호 추천받기") {
                // 일반 / 상세 추첨 토글
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !detailMode,
                        onClick = { if (detailMode) { detailMode = false; recommendations.clear(); statusText = "" } },
                        shape = CircleShape,
                        label = { Text("🎲 일반 추첨", fontWeight = FontWeight.Bold) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Brand.Accent,
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = detailMode,
                        onClick = { if (!detailMode) { detailMode = true; recommendations.clear(); statusText = "" } },
                        shape = CircleShape,
                        label = { Text("🔬 상세 추첨", fontWeight = FontWeight.Bold) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Brand.Accent,
                            selectedLabelColor = Color.White
                        )
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (detailMode)
                        "역대 ${lottoStats?.totalDraws ?: 0}회차 데이터를 바탕으로 핫/콜드 가중과 회피 조건을 적용해 번호를 추천합니다."
                    else
                        "포함하고 싶은 번호, 제외하고 싶은 번호를 입력한 뒤 추천 줄 수를 선택해서 번호를 생성합니다.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = includeText,
                    onValueChange = { includeText = it },
                    label = { Text("반드시 포함할 번호") },
                    placeholder = { Text("예: 3, 7, 15") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = excludeText,
                    onValueChange = { excludeText = it },
                    label = { Text("반드시 제외할 번호") },
                    placeholder = { Text("예: 1, 2, 45") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("추천 줄 수 (1~50)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 5).forEach { n ->
                        FilterChip(
                            selected = lineCount == n,
                            onClick = { setLineCount(n) },
                            shape = CircleShape,
                            label = { Text("${n}줄", fontWeight = FontWeight.Bold) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Accent,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                    OutlinedTextField(
                        value = lineCountText,
                        onValueChange = { applyLineCount(it) },
                        label = { Text("직접") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp)
                    )
                }
                // -------- 상세 추첨 전용 옵션 --------
                if (detailMode) {
                    Spacer(Modifier.height(16.dp))
                    Text("추첨 방식", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChip("균등", pickMode == PickMode.EVEN) { pickMode = PickMode.EVEN }
                        ModeChip("🔥 핫", pickMode == PickMode.HOT) { pickMode = PickMode.HOT }
                        ModeChip("❄️ 콜드", pickMode == PickMode.COLD) { pickMode = PickMode.COLD }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (pickMode) {
                            PickMode.EVEN -> "1~45를 똑같은 확률로 뽑습니다."
                            PickMode.HOT -> "역대 많이 나온 번호에 가중치를 줍니다."
                            PickMode.COLD -> "역대 적게 나온 번호에 가중치를 줍니다."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 핫/콜드일 때만: 가중 세기 슬라이더 (0=균등에 가깝게 ~ 5=강하게)
                    if (pickMode != PickMode.EVEN) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("가중 세기", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                when {
                                    weightStrength < 0.5f -> "거의 균등 (${"%.1f".format(weightStrength)})"
                                    weightStrength < 2f -> "약하게 (${"%.1f".format(weightStrength)})"
                                    weightStrength < 3.5f -> "보통 (${"%.1f".format(weightStrength)})"
                                    else -> "강하게 (${"%.1f".format(weightStrength)})"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Brand.Accent
                            )
                        }
                        Slider(
                            value = weightStrength,
                            onValueChange = { weightStrength = it },
                            valueRange = 0f..5f,
                            steps = 9,   // 0.5 단위
                            colors = SliderDefaults.colors(
                                thumbColor = Brand.Accent,
                                activeTrackColor = Brand.Accent
                            )
                        )
                        Text(
                            "왼쪽일수록 무작위에 가깝고, 오른쪽일수록 ${if (pickMode == PickMode.HOT) "핫" else "콜드"} 번호로 쏠립니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    CheckRow(
                        checked = avoidPastWinning,
                        onCheckedChange = { avoidPastWinning = it },
                        title = "역대 1등 조합과 완전히 겹치면 제외",
                        subtitle = "6개 모두 과거 1등과 같은 줄은 버립니다."
                    )
                    CheckRow(
                        checked = avoidPrevRound,
                        onCheckedChange = { avoidPrevRound = it },
                        title = "직전 회차 당첨번호 회피",
                        subtitle = lottoStats?.let { s ->
                            drawCache[s.latestRound]?.let { "${s.latestRound}회(${it.numbers.joinToString(",")}) 번호를 제외합니다." }
                        } ?: "최신 회차의 6개 번호를 제외합니다."
                    )

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showStats = !showStats },
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Accent)
                    ) { Text(if (showStats) "📊 핫/콜드 통계 닫기" else "📊 핫/콜드 통계 보기", fontWeight = FontWeight.Bold) }
                    if (showStats) {
                        Spacer(Modifier.height(10.dp))
                        StatsPanel(lottoStats)
                    }
                }

                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { if (detailMode) doDetailRecommend() else doRecommend() },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Accent)
                    ) { Text(if (detailMode) "🔬 상세 추천 받기" else "✨ 번호 추천 받기", fontWeight = FontWeight.Bold) }
                }

                Spacer(Modifier.height(14.dp))
                if (recommendations.isEmpty()) {
                    EmptyBox("아직 추천된 번호가 없습니다.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        recommendations.forEachIndexed { index, line ->
                            LineItem(
                                label = "${index + 1}줄",
                                numbers = line,
                                onCopy = { copyLine(line) },
                                onShare = { shareLine(line) },
                                onSave = { saveLineToRecords(line) }
                            )
                        }
                    }
                    if (statusText.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            statusText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // -------- History / manual record card --------
            SectionCard(title = "내가 사용한 번호 기록") {
                Text(
                    "추천 결과를 저장하거나, 직접 사용한 번호를 수동으로 기록할 수 있습니다. 기록은 이 기기에 저장됩니다.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    label = { Text("수동 기록 번호") },
                    placeholder = { Text("예: 4, 11, 19, 23, 31, 40") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = memoText,
                    onValueChange = { memoText = it },
                    label = { Text("메모") },
                    placeholder = { Text("예: 자동 5줄 중 마음에 들어서 구매") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { saveManual() },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Mint)
                    ) { Text("📝 직접 입력 번호 기록", fontWeight = FontWeight.Bold, color = Color(0xFF2E5249)) }
                    OutlinedButton(
                        onClick = { clearHistory() },
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Accent)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("전체 기록 삭제")
                    }
                }

                Spacer(Modifier.height(14.dp))
                if (records.isEmpty()) {
                    EmptyBox("아직 저장된 번호 기록이 없습니다.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        records.forEach { r ->
                            RecordItem(record = r, onDelete = { deleteRecord(r.id) })
                        }
                    }
                }
            }
            } else if (selectedTab == 1) {
                // -------- 당첨번호 회차 조회 tab --------
                SectionCard(title = "당첨번호 회차 조회") {
                    Text(
                        "동행복권 공식 회차 데이터를 불러옵니다. 회차 번호를 입력하거나 이전/다음 버튼으로 이동하세요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = lookupInput,
                            onValueChange = { v -> lookupInput = v.filter { it.isDigit() } },
                            label = { Text("회차") },
                            placeholder = { Text("예: 1100") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { doManualLookup() },
                            enabled = !lookupLoading,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.Accent)
                        ) { Text("🔍 조회", fontWeight = FontWeight.Bold) }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val cur = currentDraw?.round
                        OutlinedButton(
                            onClick = { if (cur != null) lookupRound(cur - 1) },
                            enabled = !lookupLoading && cur != null && cur > 1,
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Accent)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 회차", modifier = Modifier.size(18.dp))
                            Text("이전")
                        }
                        Button(
                            onClick = { loadLatest() },
                            enabled = !lookupLoading,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.Lavender)
                        ) { Text("최신", fontWeight = FontWeight.Bold, color = Color.White) }
                        OutlinedButton(
                            onClick = { if (cur != null) lookupRound(cur + 1) },
                            enabled = !lookupLoading && cur != null && (latestRound == 0 || cur < latestRound),
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Accent)
                        ) {
                            Text("다음")
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 회차", modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    when {
                        lookupLoading -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator(color = Brand.Accent) }
                        }
                        lookupError != null -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                EmptyBox(lookupError ?: "오류")
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val cur = currentDraw?.round
                                        if (cur != null) lookupRound(cur) else loadLatest()
                                    },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Accent)
                                ) { Text("다시 시도", fontWeight = FontWeight.Bold) }
                            }
                        }
                        currentDraw != null -> DrawResult(currentDraw!!)
                        else -> EmptyBox("회차를 조회해주세요.")
                    }
                }
            } else {
                // -------- 연금복권720+ 조회 tab --------
                SectionCard(title = "연금복권720+ 당첨번호 조회") {
                    Text(
                        "동행복권 연금복권720+ 회차 데이터를 불러옵니다. 회차 번호를 입력하거나 이전/다음 버튼으로 이동하세요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pLookupInput,
                            onValueChange = { v -> pLookupInput = v.filter { it.isDigit() } },
                            label = { Text("회차") },
                            placeholder = { Text("예: 319") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { pDoManualLookup() },
                            enabled = !pLookupLoading,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.Accent)
                        ) { Text("🔍 조회", fontWeight = FontWeight.Bold) }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val cur = pCurrentDraw?.round
                        OutlinedButton(
                            onClick = { if (cur != null) pLookupRound(cur - 1) },
                            enabled = !pLookupLoading && cur != null && cur > 1,
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Accent)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 회차", modifier = Modifier.size(18.dp))
                            Text("이전")
                        }
                        Button(
                            onClick = { pLoadLatest() },
                            enabled = !pLookupLoading,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.Lavender)
                        ) { Text("최신", fontWeight = FontWeight.Bold, color = Color.White) }
                        OutlinedButton(
                            onClick = { if (cur != null) pLookupRound(cur + 1) },
                            enabled = !pLookupLoading && cur != null && (pLatestRound == 0 || cur < pLatestRound),
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Accent)
                        ) {
                            Text("다음")
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 회차", modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    when {
                        pLookupLoading -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator(color = Brand.Accent) }
                        }
                        pLookupError != null -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                EmptyBox(pLookupError ?: "오류")
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val cur = pCurrentDraw?.round
                                        if (cur != null) pLookupRound(cur) else pLoadLatest()
                                    },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Accent)
                                ) { Text("다시 시도", fontWeight = FontWeight.Bold) }
                            }
                        }
                        pCurrentDraw != null -> PensionDrawResult(pCurrentDraw!!)
                        else -> EmptyBox("회차를 조회해주세요.")
                    }
                }
            }

            // -------- 하단: 데이터 동기화 상태 --------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Brand.Mint.copy(alpha = 0.15f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔄", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (lastSync != null) "최신 정보 동기화 완료" else "최신 정보 동기화 대기 중",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (lastSync != null) "동기화 시각 · $lastSync" else "조회 화면을 열면 최신 데이터를 받아옵니다.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "데이터 기준 · 로또 ${if (latestRound > 0) "${latestRound}회" else "-"} · 연금복권 ${if (pLatestRound > 0) "${pLatestRound}회" else "-"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "번호를 무작위로 추천하는 도구이며, 당첨을 보장하지 않습니다.\n정확한 당첨번호는 동행복권 공식 사이트에서 확인하세요.",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun HeroCard() {
    val heroGradient = Brush.linearGradient(
        colors = listOf(Brand.Accent, Brand.Lavender),
        start = Offset(0f, 0f),
        end = Offset(900f, 700f)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(26.dp), spotColor = Brand.Accent),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroGradient)
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                "🍀 로또 번호 추천기 R",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "번호 추천 · 로또/연금복권 당첨번호 조회",
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun CuteTabBar(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("🎲 번호추천", "🏆 로또조회", "💰 연금복권")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Brand.AccentSoft.copy(alpha = 0.5f), CircleShape)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { idx, label ->
            val isSel = selected == idx
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSel) Brand.Accent else Color.Transparent)
                    .clickable { onSelect(idx) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(24.dp), spotColor = Brand.Accent.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // rounded section header pill with emoji
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Brand.AccentSoft.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🍀", fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brand.AccentSoft.copy(alpha = 0.18f))
            .padding(vertical = 22.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🍀", fontSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 상세 추첨 방식 선택 칩 (균등/핫/콜드). */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        label = { Text(label, fontWeight = FontWeight.Bold) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = Brand.Accent,
            selectedLabelColor = Color.White
        )
    )
}

/** 체크박스 + 제목/설명 한 줄. 행 전체를 눌러 토글된다. */
@Composable
private fun CheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Brand.Accent)
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 역대 핫(최다)·콜드(최소) 번호와 오래 안 나온 번호를 참고용으로 보여주는 패널. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsPanel(stats: LottoStats?) {
    if (stats == null || stats.totalDraws == 0) {
        EmptyBox("통계를 계산할 데이터가 아직 없습니다.")
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Brand.Lavender.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("역대 ${stats.totalDraws}회차 기준 · 최신 ${stats.latestRound}회",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(10.dp))
            Text("🔥 가장 많이 나온 번호 TOP 8", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatBallRow(stats.hotOrder.take(8)) { "${stats.freq[it] ?: 0}회" }

            Spacer(Modifier.height(12.dp))
            Text("❄️ 가장 적게 나온 번호 TOP 8", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            StatBallRow(stats.coldOrder.take(8)) { "${stats.freq[it] ?: 0}회" }

            Spacer(Modifier.height(12.dp))
            Text("⏳ 오래 안 나온 번호 TOP 8 (이월수)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val staleOrder = (1..45).sortedByDescending { stats.gap(it) }.take(8)
            StatBallRow(staleOrder) { "${stats.gap(it)}회째" }

            Spacer(Modifier.height(10.dp))
            Text("※ 모든 번호가 역대 100회 이상 나왔습니다. 통계는 참고용이며 당첨 확률을 바꾸지 않습니다.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 공 + 아래 라벨(횟수/이월수)을 가로로 흐르게 나열. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatBallRow(numbers: List<Int>, label: (Int) -> String) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        numbers.forEach { n ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Ball(n)
                Spacer(Modifier.height(2.dp))
                Text(label(n), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineItem(
    label: String,
    numbers: List<Int>,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onSave: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Brand.AccentSoft.copy(alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Brand.Accent.copy(alpha = 0.9f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        label,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    numbers.forEach { Ball(it) }
                }
            }
            // 줄 단위 복사/공유/저장 (CEO 요청: 각 줄마다 개별 액션)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LineActionChip("복사", onCopy)
                LineActionChip("공유", onShare)
                LineActionChip("저장", onSave)
            }
        }
    }
}

@Composable
private fun LineActionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Brand.Accent.copy(alpha = 0.12f))
            .border(1.dp, Brand.Accent.copy(alpha = 0.5f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Brand.Accent
        )
    }
}

@Composable
private fun Ball(num: Int) {
    val base = LottoLogic.ballColor(num)
    // radial gloss: lighter top-left highlight blending into the band color
    val gloss = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.85f), base),
        center = Offset(14f, 12f),
        radius = 58f
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(3.dp, CircleShape, spotColor = base)
            .clip(CircleShape)
            .background(gloss)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            num.toString(),
            color = LottoLogic.ballTextColor(num),
            fontWeight = FontWeight.Black,
            fontSize = 16.sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawResult(draw: LottoDraw) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Brand.Accent)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                "🏆 ${draw.round}회",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
        }
        if (draw.date.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "추첨일 ${draw.date}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            draw.numbers.forEach { Ball(it) }
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("➕", fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Ball(draw.bonus)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "당첨번호 6개 + 보너스 ${draw.bonus}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (draw.firstWinAmount > 0L || draw.firstWinnerCount > 0) {
            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Brand.Mint.copy(alpha = 0.18f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("💰 1등 당첨금", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(formatWon(draw.firstWinAmount), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🎉 1등 당첨자 수", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${draw.firstWinnerCount}명", fontSize = 13.sp)
                    }
                    if (draw.totalSales > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("🛒 총 판매금액", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(formatWon(draw.totalSales), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A single digit shown in a glossy box — used for 연금복권 numbers. */
@Composable
private fun DigitBall(ch: Char, highlight: Boolean) {
    val base = if (highlight) Brand.Accent else Brand.Lavender
    val gloss = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.85f), base),
        center = Offset(12f, 10f),
        radius = 44f
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .shadow(2.dp, RoundedCornerShape(11.dp), spotColor = base)
            .clip(RoundedCornerShape(11.dp))
            .background(gloss)
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            ch.toString(),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp
        )
    }
}

/** 조 pill + 6 digit boxes. FlowRow 로 감싸 화면이 좁아도 다음 줄로 넘어가 잘리지 않는다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DigitRow(group: Int?, digits: String, highlight: Boolean) {
    val base = if (highlight) Brand.Accent else Brand.Lavender
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (group != null) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(base.copy(alpha = 0.18f))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("${group}조", fontSize = 15.sp, fontWeight = FontWeight.Black, color = base)
            }
        }
        digits.forEach { DigitBall(it, highlight) }
    }
}

/** 등위별 [등수][당첨번호][당첨금·조건] 한 줄. */
@Composable
private fun RankRow(rank: String, number: String, detail: String, emphasize: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (emphasize) Brand.Accent.copy(alpha = 0.18f) else Brand.Lavender.copy(alpha = 0.14f))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                rank,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = if (emphasize) Brand.Accent else MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                number,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = if (emphasize) Brand.Accent else MaterialTheme.colorScheme.onSurface
            )
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PensionDrawResult(draw: PensionDraw) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Brand.Accent)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                "💰 ${draw.round}회",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
        }
        if (draw.date.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "추첨일 ${draw.date}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("1등 당첨번호", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Brand.Accent)
        Spacer(Modifier.height(8.dp))
        DigitRow(group = draw.group, digits = draw.first, highlight = true)

        Spacer(Modifier.height(16.dp))
        Text("보너스 번호", fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        DigitRow(group = null, digits = draw.bonus, highlight = false)

        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Brand.Mint.copy(alpha = 0.16f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🏅 등위별 당첨번호 · 당첨금", fontSize = 14.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(
                    "2~7등은 1등 번호의 뒷자리가 맞을수록 올라갑니다.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                val n = draw.first
                RankRow("1등", "${draw.group}조 $n", "매월 700만원·20년 · 조+여섯 자리 모두 일치", true)
                RankRow("2등", n, "매월 100만원·10년 · 조 무관, 여섯 자리 일치", false)
                RankRow("보너스", draw.bonus, "매월 100만원·10년 · 보너스 여섯 자리 일치", true)
                RankRow("3등", n.takeLast(5), "100만원 · 뒤 다섯 자리 일치", false)
                RankRow("4등", n.takeLast(4), "10만원 · 뒤 네 자리 일치", false)
                RankRow("5등", n.takeLast(3), "5만원 · 뒤 세 자리 일치", false)
                RankRow("6등", n.takeLast(2), "5천원 · 뒤 두 자리 일치", false)
                RankRow("7등", n.takeLast(1), "1천원 · 뒤 한 자리 일치", false)
                Spacer(Modifier.height(6.dp))
                Text(
                    "※ 등위·당첨금은 동행복권 기준 고정값입니다.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecordItem(record: LottoRecord, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Brand.Lavender.copy(alpha = 0.14f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Brand.AccentSoft.copy(alpha = 0.4f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(record.type, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Brand.Accent)
                }
                OutlinedButton(
                    onClick = onDelete,
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Accent)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "삭제", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("삭제", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(record.numbers.joinToString(", "), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (record.memo.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("메모: ${record.memo}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(record.createdAt, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
