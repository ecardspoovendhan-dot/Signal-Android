package org.thoughtcrime.securesms.newsgate

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Shows the "Today's Brief" cover screen in front of Signal.
 *
 * Signal starts locked. It locks again whenever every Signal screen has left
 * the foreground (home button, app switch) or the screen turns off. While
 * locked, any Signal screen that comes forward is hidden and the brief
 * screen is put on top of it. Double-tapping the title unlocks.
 *
 * Everything runs inside Signal: no permissions, no background service.
 */
object NewsGate {

  @Volatile
  var locked = true
    private set

  private var startedActivities = 0
  private var gateRef: WeakReference<NewsGateActivity>? = null
  private var gateRequestedAt = 0L
  private var initialized = false

  fun init(app: Application) {
    if (initialized) return
    initialized = true

    app.registerActivityLifecycleCallbacks(Callbacks)

    ContextCompat.registerReceiver(
      app,
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          locked = true
        }
      },
      IntentFilter(Intent.ACTION_SCREEN_OFF),
      ContextCompat.RECEIVER_NOT_EXPORTED
    )
  }

  fun unlock() {
    locked = false
  }

  internal fun onGateCreated(gate: NewsGateActivity) {
    gateRef = WeakReference(gate)
    gateRequestedAt = 0L
  }

  internal fun onGateDestroyed(gate: NewsGateActivity) {
    if (gateRef?.get() === gate) gateRef = null
  }

  /** Screens that must never be covered: answering a call has to work instantly. */
  private fun isExempt(activity: Activity): Boolean {
    if (activity is NewsGateActivity) return true
    val name = activity.javaClass.name
    return name.contains(".webrtc.") || name.endsWith("CalleeMustAcceptMessageRequestActivity")
  }

  private fun hide(activity: Activity) {
    activity.window?.decorView?.visibility = View.INVISIBLE
  }

  private fun show(activity: Activity) {
    activity.window?.decorView?.visibility = View.VISIBLE
  }

  private fun requestGate(from: Activity) {
    // A live gate is already on screen.
    val gate = gateRef?.get()
    if (gate != null && !gate.isFinishing && !gate.isDestroyed) return
    // A gate was just requested and hasn't appeared yet.
    val now = SystemClock.elapsedRealtime()
    if (gateRequestedAt != 0L && now - gateRequestedAt < 1000) return
    gateRequestedAt = now
    from.startActivity(
      Intent(from, NewsGateActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    )
  }

  private object Callbacks : Application.ActivityLifecycleCallbacks {

    override fun onActivityStarted(activity: Activity) {
      startedActivities++
      if (locked && !isExempt(activity)) hide(activity)
    }

    override fun onActivityResumed(activity: Activity) {
      if (isExempt(activity)) return
      if (locked) {
        hide(activity)
        requestGate(activity)
      } else {
        show(activity)
      }
    }

    override fun onActivityStopped(activity: Activity) {
      startedActivities--
      if (startedActivities <= 0 && !activity.isChangingConfigurations) {
        startedActivities = 0
        locked = true
      }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
  }
}
