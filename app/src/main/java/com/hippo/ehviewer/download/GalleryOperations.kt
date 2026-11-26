// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.download

/** 按 GID 预留删除/维护任务；短操作与预留原子互斥，不在主线程等待文件 I/O。 */
internal object GalleryOperations {
    private val reserved = mutableSetOf<Long>()

    @Synchronized
    fun isBusy(gid: Long): Boolean = gid in reserved

    @Synchronized
    fun reserve(gid: Long): AutoCloseable? {
        require(gid > 0)
        if (!reserved.add(gid)) return null
        return AutoCloseable { synchronized(this) { reserved.remove(gid) } }
    }

    @Synchronized
    fun <T> available(gid: Long, action: () -> T): T? = if (gid in reserved) null else action()
}
