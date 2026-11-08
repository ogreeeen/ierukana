package com.ierukana.app

import android.content.Context

/** アプリ全体で共有する小さな状態（ごほうびの終了時刻・ロックの有効/無効） */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("ierukana", Context.MODE_PRIVATE)

    /** ごほうび時間の終了時刻（ミリ秒）。0 = ごほうび中ではない */
    fun rewardEnd(c: Context): Long = sp(c).getLong("rewardEnd", 0L)
    fun setRewardEnd(c: Context, v: Long) = sp(c).edit().putLong("rewardEnd", v).apply()

    /** true の間は、ごほうび時間以外にクイズから出ると引き戻す */
    fun lock(c: Context): Boolean = sp(c).getBoolean("lock", false)
    fun setLock(c: Context, v: Boolean) = sp(c).edit().putBoolean("lock", v).apply()

    fun rewardRunning(c: Context): Boolean = rewardEnd(c) > System.currentTimeMillis()
}
