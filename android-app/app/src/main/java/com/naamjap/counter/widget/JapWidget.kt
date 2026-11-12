package com.naamjap.counter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.naamjap.counter.MainActivity
import com.naamjap.counter.R
import com.naamjap.counter.data.LocalStore
import com.naamjap.counter.data.Tally
import com.naamjap.counter.ui.indian

/** Home-screen widget: today's japs, malas, goal and streak. Tap to open the app. */
class JapWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, JapWidget::class.java))
            if (ids.isEmpty()) return
            val v = views(context)
            ids.forEach { manager.updateAppWidget(it, v) }
        }

        private fun views(context: Context): RemoteViews {
            val store = LocalStore(context)
            val tally = Tally(store)
            val me = store.me
            val today = tally.today(me)
            val goal = store.goalMalas * 108L
            val v = RemoteViews(context.packageName, R.layout.widget_jap)
            v.setTextViewText(R.id.w_name, (me ?: "Naam Jap").uppercase())
            v.setTextViewText(R.id.w_count, today.indian())
            v.setTextViewText(R.id.w_mala, "Mala ${today / 108}")
            if (goal > 0) {
                v.setProgressBar(R.id.w_goal, 100, ((today * 100 / goal).coerceAtMost(100)).toInt(), false)
                val streak = tally.streak(me, goal)
                v.setTextViewText(R.id.w_streak, if (streak > 0) "🔥 $streak days" else "Goal ${store.goalMalas} mala")
            } else {
                v.setProgressBar(R.id.w_goal, 100, 0, false)
                v.setTextViewText(R.id.w_streak, "")
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }
    }
}
