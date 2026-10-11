package com.example.sampleapp

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val ink = Color.rgb(24, 36, 50)
    private val muted = Color.rgb(105, 119, 133)
    private val green = Color.rgb(16, 135, 91)
    private val pale = Color.rgb(239, 247, 243)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(248, 250, 249)
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildDashboard())
    }

    private fun buildDashboard(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 250, 249))
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        val scroll = ScrollView(this)
        scroll.addView(page)

        page.addView(text("MY INVEST", 13, green, true))
        page.addView(text("Good morning 👋", 25, ink, true, top = 8))
        page.addView(text("Your money, made easier to track.", 14, muted, false, top = 4))

        val balance = card()
        balance.addView(text("TOTAL PORTFOLIO VALUE", 11, Color.WHITE, true))
        balance.addView(text("₦250,000.00", 29, Color.WHITE, true, top = 10))
        balance.addView(text("Illustration only • Demo balance", 12, Color.rgb(218, 239, 228), false, top = 6))
        val balanceActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, 0) }
        balanceActions.addView(actionButton("Deposit", true) { demoMessage("Deposits are not connected yet.") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        balanceActions.addView(space(10), LinearLayout.LayoutParams(dp(10), 1))
        balanceActions.addView(actionButton("Withdraw", false) { demoMessage("Withdrawals are not connected yet.") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        balance.addView(balanceActions)
        page.addView(balance, marginParams(top = 22))

        page.addView(text("Investment overview", 19, ink, true, top = 24))
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(metricCard("Interest rate", "8.0%", "Example rate"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(space(12), LinearLayout.LayoutParams(dp(12), 1))
        metrics.addView(metricCard("Interest earned", "₦12,500", "Sample figure"), LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(metrics, marginParams(top = 12))

        page.addView(text("Market watch", 19, ink, true, top = 24))
        val market = card()
        val marketHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val marketTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        marketTitle.addView(text("S&P 500", 17, ink, true))
        marketTitle.addView(text("Illustrative chart • not live data", 11, muted, false, top = 4))
        marketHeader.addView(marketTitle)
        marketHeader.addView(text("DEMO", 10, green, true))
        market.addView(marketHeader)
        market.addView(MarketLine(this), LinearLayout.LayoutParams(-1, dp(125)))
        market.addView(text("Connect a market-data provider to display real prices.", 12, muted, false, top = 4))
        page.addView(market, marginParams(top = 12))

        page.addView(text("Your investment plan", 19, ink, true, top = 24))
        val plan = card()
        plan.addView(text("Starter Plan", 17, ink, true))
        plan.addView(text("Sample plan • no funds are invested", 12, muted, false, top = 5))
        plan.addView(text("Example annual interest", 12, muted, false, top = 16))
        plan.addView(text("8.0% p.a.", 22, green, true, top = 3))
        plan.addView(actionButton("View plan details", true) { demoMessage("Investment plans are not connected yet.") }, marginParams(top = 14))
        page.addView(plan, marginParams(top = 12))

        page.addView(text("Quick links", 19, ink, true, top = 24))
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        links.addView(actionButton("Transactions", false) { demoMessage("Transaction history will be added in a later build.") }, LinearLayout.LayoutParams(0, dp(46), 1f))
        links.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
        links.addView(actionButton("Account", false) { demoMessage("Secure account sign-in is not connected yet.") }, LinearLayout.LayoutParams(0, dp(46), 1f))
        page.addView(links, marginParams(top = 12))
        page.addView(text("Preview build  •  No real deposits, withdrawals, or returns", 11, muted, false, top = 24))
        return scroll
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(Color.WHITE)
        elevation = dp(2).toFloat()
    }

    private fun metricCard(label: String, value: String, note: String): LinearLayout = card().apply {
        addView(text(label, 12, muted, true))
        addView(text(value, 21, ink, true, top = 10))
        addView(text(note, 10, muted, false, top = 5))
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean, top: Int = 0): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
            if (top > 0) setPadding(0, dp(top), 0, 0)
        }

    private fun actionButton(label: String, primary: Boolean, click: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else green)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) green else pale)
            setOnClickListener { click() }
        }

    private fun space(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun marginParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun demoMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private inner class MarketLine(context: android.content.Context) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = green
            strokeWidth = dp(3).toFloat()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 238, 234)
            strokeWidth = dp(1).toFloat()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(4).toFloat()
            val right = width - dp(4).toFloat()
            val top = dp(12).toFloat()
            val bottom = height - dp(12).toFloat()
            for (i in 0..3) {
                val y = top + (bottom - top) * i / 3f
                canvas.drawLine(left, y, right, y, grid)
            }
            val values = floatArrayOf(.72f, .63f, .68f, .48f, .55f, .39f, .46f, .28f, .34f, .18f, .25f, .12f)
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = left + (right - left) * index / (values.size - 1)
                val y = top + (bottom - top) * value
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, line)
        }
    }
}
