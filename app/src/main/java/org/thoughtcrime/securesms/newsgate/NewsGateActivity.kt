package org.thoughtcrime.securesms.newsgate

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Date

/**
 * The "Today's Brief" cover screen. Looks like a plain news reader.
 * Double-tap the title to go through to Signal. Back goes to the home screen.
 */
class NewsGateActivity : ComponentActivity() {

  private lateinit var headlines: LinearLayout
  private lateinit var status: TextView
  private var lastTitleTap = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    NewsGate.onGateCreated(this)

    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = false
      isAppearanceLightNavigationBars = false
    }
    setContentView(buildUi())

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          moveTaskToBack(true)
        }
      }
    )

    NewsFeed.loadCached(this) { items, updated ->
      if (!isFinishing && !isDestroyed && items.isNotEmpty() && headlines.childCount == 0) {
        showNews(items, updated)
      }
    }
    refreshNews()
  }

  override fun onDestroy() {
    NewsGate.onGateDestroyed(this)
    super.onDestroy()
  }

  // ---- Unlock ----

  private fun onTitleTap() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastTitleTap < DOUBLE_TAP_MS) {
      lastTitleTap = 0L
      reveal()
    } else {
      lastTitleTap = now
    }
  }

  private fun reveal() {
    NewsGate.unlock()
    if (isTaskRoot) {
      // Nothing of Signal underneath (rare): open it fresh.
      try {
        startActivity(Intent().setClassName(packageName, "org.thoughtcrime.securesms.MainActivity"))
      } catch (e: ActivityNotFoundException) {
      }
    }
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)
  }

  // ---- News ----

  private fun refreshNews() {
    status.text = "Refreshing…"
    NewsFeed.refresh(this) { result ->
      if (!isFinishing && !isDestroyed) {
        val items = result.getOrNull()
        if (!items.isNullOrEmpty()) {
          showNews(items, System.currentTimeMillis())
        } else if (headlines.childCount > 0) {
          status.text = "Offline · showing saved headlines"
        } else {
          status.text = ""
          showMessage("Couldn't load headlines. Check your connection and tap ↻.")
        }
      }
    }
  }

  private fun showNews(items: List<NewsItem>, updatedAt: Long) {
    headlines.removeAllViews()
    if (updatedAt > 0) {
      status.text = "Updated " + DateFormat.getTimeFormat(this).format(Date(updatedAt))
    }
    for (item in items) {
      headlines.addView(headlineRow(item))
    }
  }

  private fun headlineRow(item: NewsItem): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, dp(14), 0, 0)
    }
    row.addView(
      TextView(this).apply {
        text = item.title
        textSize = 16f
        setTextColor(TEXT)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(dp(2).toFloat(), 1f)
      }
    )
    row.addView(
      TextView(this).apply {
        text = meta(item)
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(4), 0, 0)
      }
    )
    row.addView(
      View(this).apply { setBackgroundColor(DIVIDER) },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(14) }
    )
    if (item.link.startsWith("http")) {
      row.setOnClickListener {
        try {
          startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
        } catch (e: ActivityNotFoundException) {
        }
      }
    }
    return row
  }

  private fun meta(item: NewsItem): String {
    val parts = mutableListOf<String>()
    if (item.source.isNotEmpty()) parts += item.source
    if (item.publishedMillis > 0) {
      parts += DateUtils.getRelativeTimeSpanString(
        item.publishedMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
      ).toString()
    }
    return parts.joinToString(" · ")
  }

  private fun showMessage(text: String) {
    headlines.removeAllViews()
    headlines.addView(
      TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(MUTED)
        setPadding(0, dp(16), 0, 0)
      }
    )
  }

  // ---- Layout ----

  private fun buildUi(): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(BG)
    }

    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(20), dp(16), dp(12), dp(8))
    }
    val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    titles.addView(
      TextView(this).apply {
        text = "Today's Brief"
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TEXT)
        setOnClickListener { onTitleTap() }
      }
    )
    titles.addView(
      TextView(this).apply {
        text = DateFormat.format("EEEE, d MMMM", System.currentTimeMillis())
        textSize = 14f
        setTextColor(MUTED)
      }
    )
    header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(
      TextView(this).apply {
        text = "↻"
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        contentDescription = "Refresh headlines"
        setOnClickListener { refreshNews() }
      },
      LinearLayout.LayoutParams(dp(48), dp(48))
    )
    root.addView(header)

    val body = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), 0, dp(20), dp(24))
    }
    body.addView(
      View(this).apply {
        background = GradientDrawable(
          GradientDrawable.Orientation.TL_BR,
          intArrayOf(0xFF2E4060.toInt(), 0xFF1B2638.toInt(), 0xFF141D2E.toInt())
        ).apply { cornerRadius = dp(16).toFloat() }
      },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)).apply { topMargin = dp(8) }
    )
    status = TextView(this).apply {
      textSize = 12f
      setTextColor(MUTED)
      setPadding(0, dp(8), 0, 0)
    }
    body.addView(status)
    body.addView(
      TextView(this).apply {
        text = "Top stories"
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TEXT)
        setPadding(0, dp(16), 0, 0)
      }
    )
    headlines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(headlines)

    val scroll = ScrollView(this).apply { isFillViewport = true }
    scroll.addView(body)
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
    return root
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private companion object {
    const val DOUBLE_TAP_MS = 400L
    val BG = 0xFF121212.toInt()
    val TEXT = 0xFFECECEC.toInt()
    val MUTED = 0xFF9A9A9A.toInt()
    val DIVIDER = 0xFF2C2C2C.toInt()
  }
}
