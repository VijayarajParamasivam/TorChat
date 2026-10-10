package com.torchat.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.app.PendingIntentCompat
import com.torchat.MainActivity
import com.torchat.R
import com.torchat.core.engine.EngineStatus
import com.torchat.core.engine.TorPhase
import com.torchat.data.TorChatContract
import com.torchat.service.Session
import com.torchat.ui.common.Identicon

/**
 * The home-screen widget: is my node up, who is online, what is unread.
 *
 * An AppWidgetProvider is a broadcast receiver underneath. The service sends it
 * an explicit [ACTION_REFRESH] broadcast whenever the node's state or the
 * unread count changes, and it redraws from the same sources the app uses —
 * the engine's status and the content provider — so the widget and the app
 * can never disagree.
 */
class NodeWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            render(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {

        /** Redraw every placed widget. Sent by the service as an explicit broadcast. */
        const val ACTION_REFRESH = "com.torchat.action.WIDGET_REFRESH"

        /** Ask every placed widget to redraw. Cheap when none is placed. */
        fun requestRefresh(context: Context) {
            context.sendBroadcast(
                Intent(context, NodeWidgetProvider::class.java).setAction(ACTION_REFRESH)
            )
        }

        private fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NodeWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_node)
            val engine = Session.engine
            val identity = Session.loadIdentity(context)

            if (engine == null) {
                views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_stopped))
                views.setImageViewResource(R.id.widget_dot, R.drawable.bg_dot_offline)
                views.setTextViewText(R.id.widget_peers, "")
                views.setTextViewText(R.id.widget_unread, "")
            } else {
                val status = engine.status.value
                views.setTextViewText(R.id.widget_status, phaseLabel(context, status))
                views.setImageViewResource(R.id.widget_dot, phaseDot(status))
                views.setTextViewText(
                    R.id.widget_peers,
                    context.getString(R.string.widget_online, status.online, status.friends),
                )
                val unread = unreadCount(context)
                views.setTextViewText(
                    R.id.widget_unread,
                    if (unread > 0) context.resources.getQuantityString(R.plurals.widget_unread, unread, unread) else "",
                )
            }

            if (identity != null) {
                views.setTextViewText(R.id.widget_name, identity.name)
                views.setTextViewText(R.id.widget_id, identity.id)
                views.setImageViewBitmap(R.id.widget_avatar, Identicon.bitmap(identity.id, 96))
            }

            views.setOnClickPendingIntent(R.id.widget_root, open(context, null, 0))
            views.setOnClickPendingIntent(R.id.widget_code, open(context, Uri.parse("torchat://card"), 1))

            manager.updateAppWidget(ids, views)
        }

        private fun open(context: Context, uri: Uri?, requestCode: Int): PendingIntent =
            PendingIntentCompat.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    if (uri != null) {
                        action = Intent.ACTION_VIEW
                        data = uri
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            )!!

        private fun unreadCount(context: Context): Int =
            runCatching {
                context.contentResolver.query(TorChatContract.Stats.CONTENT_URI, null, null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getInt(cursor.getColumnIndexOrThrow(TorChatContract.Stats.UNREAD))
                        } else {
                            0
                        }
                    } ?: 0
            }.getOrDefault(0)

        fun phaseLabel(context: Context, status: EngineStatus): String = when {
            status.torRestarting -> context.getString(R.string.phase_restarting)
            else -> when (status.phase) {
                TorPhase.OFFLINE -> context.getString(R.string.phase_offline)
                TorPhase.BOOTSTRAPPING ->
                    if (status.bootstrapPercent > 0) {
                        "${context.getString(R.string.phase_bootstrapping)} · ${status.bootstrapPercent}%"
                    } else {
                        context.getString(R.string.phase_bootstrapping)
                    }
                TorPhase.PUBLISHING -> context.getString(R.string.phase_publishing)
                TorPhase.PROPAGATING -> context.getString(R.string.phase_propagating)
                TorPhase.REACHABLE -> context.getString(R.string.phase_reachable)
            }
        }

        fun phaseDot(status: EngineStatus): Int = when {
            status.torRestarting -> R.drawable.bg_dot_warning
            status.phase == TorPhase.REACHABLE -> R.drawable.bg_dot_online
            status.phase == TorPhase.OFFLINE -> R.drawable.bg_dot_error
            else -> R.drawable.bg_dot_warning
        }
    }
}
