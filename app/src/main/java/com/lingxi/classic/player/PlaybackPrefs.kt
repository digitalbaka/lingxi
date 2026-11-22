package com.lingxi.classic.player

import android.content.Context

/** 播放相关偏好：退到后台自动播放音频 */
object PlaybackPrefs {

    private const val PREFS = "lingxi_playback"
    private const val KEY_BACKGROUND_AUDIO = "background_audio"

    fun isBackgroundAudioEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKGROUND_AUDIO, false)

    fun setBackgroundAudioEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKGROUND_AUDIO, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
