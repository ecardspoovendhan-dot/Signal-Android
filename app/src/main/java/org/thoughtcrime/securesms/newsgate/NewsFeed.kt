package org.thoughtcrime.securesms.newsgate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

data class NewsItem(
  val title: String,
  val link: String,
  val source: String,
  val publishedMillis: Long
)

/** Downloads headlines from an RSS feed and keeps the last batch for offline use. */
object NewsFeed {

  const val FEED_URL = "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"

  private const val PREFS = "newsgate"
  private const val KEY_CACHE = "cache"
  private const val KEY_UPDATED = "updated"
  private const val MAX_ITEMS = 25
  private const val TIMEOUT_MS = 8_000

  private val executor = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())

  fun loadCached(context: Context, callback: (List<NewsItem>, Long) -> Unit) {
    val app = context.applicationContext
    executor.execute {
      val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val items = decode(prefs.getString(KEY_CACHE, null))
      val updated = prefs.getLong(KEY_UPDATED, 0L)
      main.post { callback(items, updated) }
    }
  }

  fun refresh(context: Context, callback: (Result<List<NewsItem>>) -> Unit) {
    val app = context.applicationContext
    executor.execute {
      val result = runCatching { fetch(FEED_URL) }
      result.onSuccess { items ->
        if (items.isNotEmpty()) {
          app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CACHE, encode(items))
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
        }
      }
      main.post { callback(result) }
    }
  }

  private fun encode(items: List<NewsItem>): String {
    fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    return items.joinToString("\n") {
      "${clean(it.title)}\t${clean(it.link)}\t${clean(it.source)}\t${it.publishedMillis}"
    }
  }

  private fun decode(raw: String?): List<NewsItem> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
      val p = line.split('\t')
      if (p.size < 4) null else NewsItem(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0L)
    }.toList()
  }

  private fun fetch(url: String): List<NewsItem> {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = TIMEOUT_MS
    conn.readTimeout = TIMEOUT_MS
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
    try {
      val code = conn.responseCode
      if (code !in 200..299) throw IOException("HTTP $code")
      return conn.inputStream.use { parse(it) }
    } finally {
      conn.disconnect()
    }
  }

  private fun parse(input: InputStream): List<NewsItem> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(input, null)

    val items = mutableListOf<NewsItem>()
    var inItem = false
    var title = ""
    var link = ""
    var source = ""
    var date = ""

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT && items.size < MAX_ITEMS) {
      when (event) {
        XmlPullParser.START_TAG -> when (parser.name) {
          "item", "entry" -> {
            inItem = true
            title = ""; link = ""; source = ""; date = ""
          }
          "title" -> if (inItem) title = parser.nextText().trim()
          "link" -> if (inItem) link = parser.getAttributeValue(null, "href") ?: parser.nextText().trim()
          "source" -> if (inItem) source = parser.nextText().trim()
          "pubDate", "published", "updated" -> if (inItem && date.isEmpty()) date = parser.nextText().trim()
        }
        XmlPullParser.END_TAG -> if (inItem && (parser.name == "item" || parser.name == "entry")) {
          inItem = false
          if (title.isNotBlank()) {
            val clean = if (source.isNotEmpty() && title.endsWith(" - $source")) title.removeSuffix(" - $source") else title
            items += NewsItem(clean, link, source, parseDate(date))
          }
        }
      }
      event = parser.next()
    }
    return items
  }

  private fun parseDate(text: String): Long {
    if (text.isEmpty()) return 0L
    val patterns = listOf(
      "EEE, dd MMM yyyy HH:mm:ss zzz",
      "EEE, dd MMM yyyy HH:mm:ss Z",
      "yyyy-MM-dd'T'HH:mm:ssXXX",
      "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    )
    for (p in patterns) {
      try {
        val parsed = SimpleDateFormat(p, Locale.US).parse(text)
        if (parsed != null) return parsed.time
      } catch (e: Exception) {
      }
    }
    return 0L
  }
}
