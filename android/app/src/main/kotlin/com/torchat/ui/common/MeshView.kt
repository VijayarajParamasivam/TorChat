package com.torchat.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.torchat.R
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The network as a picture: your node, your friends' nodes, and the links
 * between them.
 *
 * There is no server in this drawing because there is no server in the system.
 * Every friend is a node of their own, joined to yours by a line — lit green
 * while a link is live, dashed grey while it is not. Along each line sit three
 * small dots: the relays a Tor circuit actually passes through, none of which
 * sees both ends. A pulse travels a live line now and then, which is what a
 * message does.
 *
 * In demo mode (onboarding) it draws a mesh with no centre at all, which is the
 * idea before there is anyone in it.
 */
class MeshView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class State { ONLINE, OFFLINE, PENDING }

    data class Node(val id: String, val label: String, val state: State)

    /** Tapping a friend's node. */
    var onNodeClick: ((String) -> Unit)? = null

    private var self: Node? = null
    private var peers: List<Node> = emptyList()
    private var demo = false

    private val density = resources.displayMetrics.density
    private val signal = ContextCompat.getColor(context, R.color.tc_signal)
    private val primary = ContextCompat.getColor(context, R.color.tc_primary)
    private val offline = ContextCompat.getColor(context, R.color.tc_offline)
    private val warning = ContextCompat.getColor(context, R.color.tc_warning)
    private val text = ContextCompat.getColor(context, R.color.tc_text_dim)
    private val surface = ContextCompat.getColor(context, R.color.tc_surface_high)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dashed = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        color = text
    }

    private val rect = RectF()
    private val hitTargets = mutableListOf<Pair<RectF, String>>()

    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setNodes(self: Node?, peers: List<Node>) {
        this.demo = false
        this.self = self
        this.peers = peers.take(MAX_PEERS)
        invalidate()
    }

    /** A centreless mesh of anonymous nodes, for before there is anyone. */
    fun showDemo() {
        demo = true
        self = null
        peers = (0 until 7).map { Node("demo-node-$it", "", if (it % 3 == 2) State.OFFLINE else State.ONLINE) }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // No animation for a view nobody can see.
        if (visibility == VISIBLE && isAttachedToWindow) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitTargets.clear()

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 34f * density
        val nodeRadius = (min(width, height) / 11f).coerceIn(16f * density, 28f * density)

        if (demo) {
            drawDemo(canvas, cx, cy, radius, nodeRadius)
            return
        }

        val positions = peers.mapIndexed { index, _ ->
            val angle = -Math.PI / 2 + index * 2 * Math.PI / peers.size.coerceAtLeast(1)
            (cx + radius * cos(angle).toFloat()) to (cy + radius * sin(angle).toFloat())
        }

        // Links first, so nodes sit on top of them.
        peers.forEachIndexed { index, peer ->
            val (x, y) = positions[index]
            drawLink(canvas, cx, cy, x, y, peer.state, index)
        }

        self?.let { drawNode(canvas, it, cx, cy, nodeRadius * 1.25f, primary, clickable = false) }

        peers.forEachIndexed { index, peer ->
            val (x, y) = positions[index]
            val ring = when (peer.state) {
                State.ONLINE -> signal
                State.OFFLINE -> offline
                State.PENDING -> warning
            }
            drawNode(canvas, peer, x, y, nodeRadius, ring, clickable = true)
        }
    }

    private fun drawDemo(canvas: Canvas, cx: Float, cy: Float, radius: Float, nodeRadius: Float) {
        val count = peers.size
        val positions = (0 until count).map { index ->
            val wobble = if (index % 2 == 0) 1f else 0.72f
            val angle = -Math.PI / 2 + index * 2 * Math.PI / count
            (cx + radius * wobble * cos(angle).toFloat()) to (cy + radius * wobble * sin(angle).toFloat())
        }

        // Every node joined to its neighbours and to the one across the ring:
        // many paths, no centre, nothing whose loss takes the rest down.
        for (i in 0 until count) {
            for (j in listOf(i + 1, i + 3)) {
                val k = j % count
                val state = if (peers[i].state == State.ONLINE && peers[k].state == State.ONLINE) State.ONLINE else State.OFFLINE
                drawLink(canvas, positions[i].first, positions[i].second, positions[k].first, positions[k].second, state, i * 7 + k)
            }
        }
        peers.forEachIndexed { index, node ->
            val (x, y) = positions[index]
            drawNode(canvas, node, x, y, nodeRadius * 0.8f, if (node.state == State.ONLINE) signal else offline, clickable = false)
        }
    }

    private fun drawLink(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, state: State, seed: Int) {
        val live = state == State.ONLINE
        linePaint.color = when (state) {
            State.ONLINE -> signal
            State.OFFLINE -> offline
            State.PENDING -> warning
        }
        linePaint.alpha = if (live) 150 else 90
        linePaint.pathEffect = if (live) null else dashed
        canvas.drawLine(x1, y1, x2, y2, linePaint)

        // The three relays of a circuit.
        dotPaint.color = linePaint.color
        dotPaint.alpha = if (live) 220 else 110
        for (step in 1..3) {
            val t = step / 4f
            canvas.drawCircle(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, 2.4f * density, dotPaint)
        }

        if (!live) return

        // A message in flight, offset per link so they do not march in step.
        val t = (phase + (seed * 0.137f)) % 1f
        val px = x1 + (x2 - x1) * t
        val py = y1 + (y2 - y1) * t
        dotPaint.color = signal
        dotPaint.alpha = 70
        canvas.drawCircle(px, py, 7f * density, dotPaint)
        dotPaint.alpha = 255
        canvas.drawCircle(px, py, 3.2f * density, dotPaint)
    }

    private fun drawNode(canvas: Canvas, node: Node, x: Float, y: Float, r: Float, ring: Int, clickable: Boolean) {
        dotPaint.color = surface
        dotPaint.alpha = 255
        canvas.drawCircle(x, y, r + 3f * density, dotPaint)

        rect.set(x - r, y - r, x + r, y + r)
        Identicon.draw(canvas, rect, node.id)

        ringPaint.color = ring
        ringPaint.alpha = if (node.state == State.ONLINE) 255 else 170
        canvas.drawCircle(x, y, r + 1.5f * density, ringPaint)

        if (node.label.isNotEmpty()) {
            val label = if (node.label.length > 12) node.label.take(11) + "…" else node.label
            canvas.drawText(label, x, y + r + 15f * density, labelPaint)
        }

        if (clickable) hitTargets += RectF(x - r * 1.4f, y - r * 1.4f, x + r * 1.4f, y + r * 1.4f) to node.id
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onNodeClick == null) return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return hitTargets.any { it.first.contains(event.x, event.y) }
            MotionEvent.ACTION_UP -> {
                val hit = hitTargets.firstOrNull { it.first.contains(event.x, event.y) } ?: return false
                performClick()
                onNodeClick?.invoke(hit.second)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    @Suppress("unused")
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float) = hypot(x2 - x1, y2 - y1)

    private companion object {
        const val MAX_PEERS = 12
    }
}
