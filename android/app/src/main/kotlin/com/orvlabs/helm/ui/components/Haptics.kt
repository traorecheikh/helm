package com.orvlabs.helm.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition as P
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants as HC
import android.view.View
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.quietNow
import java.lang.ref.WeakReference

enum class Feel(val event: Boolean) {
    Tick(false), ToggleOn(false), ToggleOff(false), Progress(false), ThresholdIn(false), ThresholdOut(false),
    Commit(false), Confirm(false), Reject(false), Seal(false),
    Arrival(true), Attention(true), Failure(true), Recovery(true),
}

@android.annotation.SuppressLint("NewApi")
object Haptics {
    private val RICH = intArrayOf(P.PRIMITIVE_CLICK, P.PRIMITIVE_TICK, P.PRIMITIVE_LOW_TICK, P.PRIMITIVE_SLOW_RISE, P.PRIMITIVE_QUICK_FALL, P.PRIMITIVE_THUD)
    private lateinit var app: Context
    private lateinit var vibrator: Vibrator
    private var rich = false
    private var view = WeakReference<View>(null)
    @Volatile var resumed = false
    var reduceMotion = false
    private var lastEvent = 0L
    private var lastProgress = 0L
    private val failed = HashMap<String, Long>()
    private val recovered = HashMap<String, Long>()
    private val attentionKeys = HashSet<String>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        vibrator = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator else app.getSystemService(Vibrator::class.java)
        rich = Build.VERSION.SDK_INT >= 31 && vibrator.areAllPrimitivesSupported(*RICH)
    }

    fun attach(v: View, isResumed: Boolean) {
        view = WeakReference(v)
        resumed = isResumed
        reduceMotion = Settings.Global.getFloat(v.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    private fun comp(vararg p: Triple<Int, Float, Int>) =
        VibrationEffect.startComposition().apply { p.forEach { addPrimitive(it.first, it.second, it.third) } }.compose()

    private fun richEffect(f: Feel, level: Float): VibrationEffect? = when (f) {
        Feel.Progress -> comp(Triple(P.PRIMITIVE_LOW_TICK, 0.2f + 0.35f * level, 0))
        Feel.Commit -> comp(Triple(P.PRIMITIVE_CLICK, 1f, 0))
        Feel.Seal -> comp(Triple(P.PRIMITIVE_CLICK, 1f, 0), Triple(P.PRIMITIVE_CLICK, 0.7f, 60), Triple(P.PRIMITIVE_LOW_TICK, 0.5f, 60))
        Feel.Arrival -> comp(Triple(P.PRIMITIVE_LOW_TICK, 0.3f, 0))
        Feel.Attention -> comp(Triple(P.PRIMITIVE_TICK, 0.6f, 0), Triple(P.PRIMITIVE_TICK, 0.4f, 120))
        Feel.Failure -> comp(Triple(P.PRIMITIVE_QUICK_FALL, 0.5f, 0), Triple(P.PRIMITIVE_THUD, 0.6f, 0))
        Feel.Recovery -> comp(Triple(P.PRIMITIVE_SLOW_RISE, 0.4f, 0), Triple(P.PRIMITIVE_QUICK_FALL, 0.3f, 0), Triple(P.PRIMITIVE_LOW_TICK, 0.35f, 150))
        else -> null
    }

    private fun basicEffect(f: Feel): VibrationEffect? = when (f) {
        Feel.Commit, Feel.Seal, Feel.Failure -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        Feel.Recovery -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        Feel.Attention -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        Feel.Reject -> if (Build.VERSION.SDK_INT < 30) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK) else null
        else -> null
    }

    private fun constant(f: Feel): Int? = when (f) {
        Feel.Tick -> if (Build.VERSION.SDK_INT >= 34) HC.SEGMENT_TICK else HC.CLOCK_TICK
        Feel.ToggleOn -> if (Build.VERSION.SDK_INT >= 34) HC.TOGGLE_ON else HC.VIRTUAL_KEY
        Feel.ToggleOff -> if (Build.VERSION.SDK_INT >= 34) HC.TOGGLE_OFF else HC.CLOCK_TICK
        Feel.ThresholdIn -> if (Build.VERSION.SDK_INT >= 34) HC.GESTURE_THRESHOLD_ACTIVATE else HC.CONTEXT_CLICK
        Feel.ThresholdOut -> if (Build.VERSION.SDK_INT >= 34) HC.GESTURE_THRESHOLD_DEACTIVATE else null
        Feel.Progress -> if (!rich && Build.VERSION.SDK_INT >= 34) HC.SEGMENT_FREQUENT_TICK else null
        Feel.Confirm -> if (Build.VERSION.SDK_INT >= 30) HC.CONFIRM else HC.VIRTUAL_KEY
        Feel.Reject -> if (Build.VERSION.SDK_INT >= 30) HC.REJECT else null
        else -> null
    }

    private fun priority(f: Feel) = when (f) { Feel.Failure -> 3; Feel.Recovery -> 2; Feel.Attention -> 1; else -> 0 }

    /** Outage kinds may vibrate during quiet hours; everything else event-class is muted. */
    private fun allowed(f: Feel, key: String?): Boolean {
        val now = System.currentTimeMillis()
        if (!f.event) {
            if (f == Feel.Progress) { if (now - lastProgress < 50) return false; lastProgress = now }
            return Settings.System.getInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        }
        if (!resumed) return false
        if (app.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        if (f == Feel.Arrival && reduceMotion) return false
        val cfg = Store.config.value
        if (cfg != null && cfg.quietOn && quietNow(cfg.quietStart, cfg.quietEnd) && f != Feel.Failure && f != Feel.Recovery) return false
        if (now - lastEvent < 4000 && priority(f) <= 0) return false
        val k = key ?: "_"
        when (f) {
            Feel.Failure -> { if (failed.containsKey(k)) return false; failed[k] = now }
            Feel.Recovery -> {
                val since = failed.remove(k) ?: return false
                if (now - since < 30_000 || now - (recovered[k] ?: 0) < 300_000) return false
                recovered[k] = now
            }
            Feel.Attention -> if (!attentionKeys.add(k)) return false
            Feel.Arrival -> if (failed.isNotEmpty()) return false
            else -> Unit
        }
        lastEvent = now
        return true
    }

    fun fire(f: Feel, level: Float = 1f, key: String? = null) {
        if (!::app.isInitialized || !allowed(f, key)) return
        val v = view.get()
        constant(f)?.let { c -> if (v != null) { v.performHapticFeedback(c); return } }
        val effect = (if (rich) richEffect(f, level) else null) ?: basicEffect(f) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(if (f.event) VibrationAttributes.USAGE_NOTIFICATION else VibrationAttributes.USAGE_TOUCH))
        } else {
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(if (f.event) AudioAttributes.USAGE_NOTIFICATION else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(),
            )
        }
    }

    fun confirmLater() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ fire(Feel.Confirm) }, 300)
    }
}
