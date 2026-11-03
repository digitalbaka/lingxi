package com.lingxi.classic.player

import com.lingxi.classic.data.model.Danmaku

/** 弹幕渲染项 - 对象池复用，减少 GC */
class DanmakuItem {
    var id: Long = 0
    var content: String = ""
    var timeMs: Long = 0
    var mode: Int = Danmaku.MODE_SCROLL
    var color: Int = 0xFFFFFF
    var fontSize: Int = 25

    var x: Float = 0f
    var y: Float = 0f
    var width: Float = 0f
    var speed: Float = 0f
    var alive: Boolean = false
    var track: Int = 0

    fun reset() {
        id = 0; content = ""; timeMs = 0; mode = Danmaku.MODE_SCROLL
        color = 0xFFFFFF; fontSize = 25
        x = 0f; y = 0f; width = 0f; speed = 0f; alive = false; track = 0
    }

    companion object {
        private val pool = ArrayDeque<DanmakuItem>(64)

        fun obtain(): DanmakuItem = pool.removeLastOrNull() ?: DanmakuItem()

        fun recycle(item: DanmakuItem) {
            item.reset()
            if (pool.size < 128) pool.addLast(item)
        }
    }
}
