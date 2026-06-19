package com.ryu.lottopick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 홈 화면 로또 위젯 — 최근 로또 당첨번호 + 최근 연금복권 당첨번호 2줄.
 * 데이터는 앱의 LottoApi(공개)를 그대로 재사용:
 *  - LottoApi.fetchLatest() / fetchPensionLatest() (papaya5rhw1984.github.io/lotto-data JSON)
 *  - 네트워크 실패 시 앱 내장(assets) 최신 회차로 폴백.
 * 캐시는 위젯 전용 prefs("lotto_widget")에 포맷 문자열로 저장 → 즉시 렌더.
 */
class LottoWidget : AppWidgetProvider() {

    companion object {
        private const val PREFS = "lotto_widget"

        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, LottoWidget::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, LottoWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, LottoWidget::class.java))
            if (ids.isEmpty()) return

            for (id in ids) renderWidget(context, mgr, id)

            val pending = goAsync()
            Thread {
                try {
                    val appCtx = context.applicationContext
                    val lotto: LottoDraw? = (try { LottoApi.fetchLatest() } catch (e: Exception) { null })
                        ?: LottoApi.loadBundled(appCtx).maxByOrNull { it.round }
                    val pension: PensionDraw? = (try { LottoApi.fetchPensionLatest() } catch (e: Exception) { null })
                        ?: LottoApi.loadBundledPension(appCtx).maxByOrNull { it.round }

                    val e = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    if (lotto != null) {
                        e.putString("lotto_round", "로또 ${lotto.round}회" + dateSuffix(lotto.date))
                        e.putString("lotto_nums", lotto.numbers.joinToString("  ") + "   ＋${lotto.bonus}")
                    }
                    if (pension != null) {
                        e.putString("pension_round", "연금복권 ${pension.round}회" + dateSuffix(pension.date))
                        e.putString("pension_nums", "${pension.group}조  ${spaced(pension.first)}")
                    }
                    e.apply()
                    if (lotto != null || pension != null) for (id in ids) renderWidget(appCtx, mgr, id)
                } catch (e: Exception) {
                    // 캐시로 이미 렌더됨 — 무시
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, mgr, id)
    }

    private fun renderWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val views = RemoteViews(context.packageName, R.layout.widget_lotto)

        val lottoRound = prefs.getString("lotto_round", null)
        val lottoNums = prefs.getString("lotto_nums", null)
        val pensionRound = prefs.getString("pension_round", null)
        val pensionNums = prefs.getString("pension_nums", null)

        if (lottoNums == null && pensionNums == null) {
            views.setTextViewText(R.id.lt_lotto_round, "로또")
            views.setTextViewText(R.id.lt_lotto_nums, "탭하여 불러오기")
            views.setTextViewText(R.id.lt_pension_round, "연금복권")
            views.setTextViewText(R.id.lt_pension_nums, "—")
        } else {
            views.setTextViewText(R.id.lt_lotto_round, lottoRound ?: "로또")
            views.setTextViewText(R.id.lt_lotto_nums, lottoNums ?: "—")
            views.setTextViewText(R.id.lt_pension_round, pensionRound ?: "연금복권")
            views.setTextViewText(R.id.lt_pension_nums, pensionNums ?: "—")
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setOnClickPendingIntent(
            R.id.lt_root,
            PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        mgr.updateAppWidget(id, views)
    }

    /** "123456" → "1 2 3 4 5 6" (연금 6자리 가독성). */
    private fun spaced(s: String): String = s.trim().toCharArray().joinToString(" ")

    /** 회차 옆 날짜 접미사. "2026-06-13T..." → " · 2026-06-13", 없으면 "". */
    private fun dateSuffix(raw: String?): String {
        val d = raw?.trim().orEmpty()
        if (d.isEmpty()) return ""
        val date = if (d.length >= 10) d.substring(0, 10) else d
        return " · $date"
    }
}
