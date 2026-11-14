package at.sushi.handoff.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import at.sushi.handoff.HandoffState
import at.sushi.handoff.R

/** Issue #238 -- plays the short bundled tone for a vPilot event, whether or not the app is on
 *  screen (the sound is in-app playback, not a notification-channel sound: channel sounds can't
 *  be changed after creation, and are suppressed anyway by the foreground-service gating).
 *  Played on the notification audio stream, so it follows the user's notification volume / DND. */
class EventSoundPlayer(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundIds = mapOf(
        EventSound.Chat to soundPool.load(context, R.raw.sound_chat, 1),
        EventSound.RadioToYou to soundPool.load(context, R.raw.sound_radio_to_you, 1),
        EventSound.ContactMe to soundPool.load(context, R.raw.sound_contact_me, 1),
        EventSound.Selcal to soundPool.load(context, R.raw.sound_selcal, 1)
    )

    /** No-op when the pilot turned this event's sound off. */
    fun play(event: EventSound) {
        if (!HandoffState.eventSounds.value.isEnabled(event)) return
        preview(event)
    }

    /** Plays regardless of the switch -- the settings dialog previews a sound the moment the
     *  pilot taps "On", before the new setting is saved. */
    fun preview(event: EventSound) {
        soundIds[event]?.let { soundPool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun release() = soundPool.release()
}
