package app.murmure.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import app.murmure.R

/** Sons courts et retours haptiques : l'app répond sous le doigt. */
object Feedback {
    enum class Sound { START, STOP, TICK, TAP, SUCCESS, WAKE }

    private var pool: SoundPool? = null
    private val ids = HashMap<Sound, Int>()
    @Volatile var soundsEnabled = true
    @Volatile var hapticsEnabled = true

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        ids[Sound.START] = p.load(context, R.raw.snd_start, 1)
        ids[Sound.STOP] = p.load(context, R.raw.snd_stop, 1)
        ids[Sound.TICK] = p.load(context, R.raw.snd_tick, 1)
        ids[Sound.TAP] = p.load(context, R.raw.snd_tap, 1)
        ids[Sound.SUCCESS] = p.load(context, R.raw.snd_success, 1)
        ids[Sound.WAKE] = p.load(context, R.raw.snd_wake, 1)
        pool = p
    }

    fun play(s: Sound, volume: Float = 1f) {
        if (!soundsEnabled) return
        val id = ids[s] ?: return
        pool?.play(id, volume, volume, 1, 0, 1f)
    }

    private fun vibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()

    /** Tic léger : un tap sur un contrôle. */
    fun tap(view: View) {
        if (!hapticsEnabled) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Confirmation nette : classement validé, rituel complété. */
    fun confirm(view: View) {
        if (!hapticsEnabled) return
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }

    /** Double impulsion douce : « j'ai attrapé quelque chose » pendant la dictée. */
    fun moment(context: Context) {
        if (!hapticsEnabled) return
        val v = vibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 18, 60, 28), intArrayOf(0, 120, 0, 200), -1))
            else @Suppress("DEPRECATION") v.vibrate(40)
        }
    }

    /** Pulsation au démarrage de l'écoute. */
    fun wake(context: Context) {
        if (!hapticsEnabled) return
        val v = vibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 12, 40, 12, 40, 30), intArrayOf(0, 90, 0, 120, 0, 180), -1))
            else @Suppress("DEPRECATION") v.vibrate(60)
        }
    }
}

/** Le contrôle s'enfonce sous le doigt (ressort) — à composer avec clickable(interactionSource). */
fun Modifier.pressScale(interaction: MutableInteractionSource, down: Float = 0.96f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) down else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Raccourci : tap haptique + son sur un clic. */
@Composable
fun rememberTapAction(onClick: () -> Unit, sound: Boolean = true): () -> Unit {
    val view = LocalView.current
    return remember(onClick, sound) {
        {
            Feedback.tap(view)
            if (sound) Feedback.play(Feedback.Sound.TAP, 0.5f)
            onClick()
        }
    }
}

@Composable
fun rememberContext(): Context = LocalContext.current
