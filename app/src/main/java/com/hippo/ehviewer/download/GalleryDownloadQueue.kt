// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

/** 在主线程按 GID 调度。降低上限只影响下一次补位，运行中的任务保留原实例。 */
internal class GalleryDownloadQueue<T : Any> {
    private val waiting = linkedSetOf<Long>()
    private val active = linkedMapOf<Long, T>()
    val running: Map<Long, T> get() = active
    val waitingIds: Set<Long> get() = waiting

    // 维护和备份可在 IO 线程读取；队列本身只在主线程修改。
    @Volatile
    var isIdle = true
        private set

    fun enqueue(gid: Long) {
        isIdle = false
        if (gid !in active) waiting.add(gid)
    }

    fun removeWaiting(gid: Long): Boolean = waiting.remove(gid).also { updateIdle() }
    fun release(gid: Long): T? = active.remove(gid).also { updateIdle() }
    fun isCurrent(gid: Long, task: T): Boolean = active[gid] === task

    private fun updateIdle() {
        isIdle = waiting.isEmpty() && active.isEmpty()
    }

    /** 队首仍在退出或维护时原位等待重试；先登记新实例，再订阅其进度。 */
    fun fill(limit: Int, acquire: (Long) -> T?, start: (T) -> Unit): Boolean {
        while (active.size < limit && waiting.isNotEmpty()) {
            val gid = waiting.first()
            val task = acquire(gid) ?: return false
            waiting.remove(gid)
            active[gid] = task
            start(task)
        }
        return true
    }
}
