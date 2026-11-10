/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.spider

import android.util.Log
import coil3.disk.DiskCache
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.coil.edit
import com.hippo.unifile.UniFile
import com.hippo.unifile.openInputStream
import com.hippo.unifile.openOutputStream
import com.hippo.util.runSuspendCatching
import com.hippo.yorozuya.NumberUtils
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import okio.buffer
import okio.source

@Serializable
class SpiderInfo(
    val gid: Long,
    var token: String? = null,
    val pages: Int,
    var pTokenMap: MutableMap<Int, String> = ConcurrentHashMap(),
    var startPage: Int = 0,
    var previewPages: Int = -1,
    var previewPerPage: Int = -1,
    var upgradeFrom: Long? = null,
    var downloadFormat: String? = null,
)

private val cbor = Cbor { ignoreUnknownKeys = true }

fun readFromUniFile(file: UniFile): SpiderInfo? = runCatching {
    file.openInputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= 16 * 1024 * 1024) { "Oversized spider info" }
            output.write(buffer, 0, count)
        }
        decodeSpiderInfo(output.toByteArray())
    }
}.onFailure {
    Log.e(TAG, "Error", it)
}.getOrNull()

/** 主库及 .ehreader 共用的续传格式；保留旧编码读取能力，拒绝损坏身份和越界页号。 */
fun decodeSpiderInfo(bytes: ByteArray): SpiderInfo? {
    if (bytes.size > 16 * 1024 * 1024) return null
    val info = runCatching { cbor.decodeFromByteArray<SpiderInfo>(bytes) }.getOrNull()
        ?: runCatching { bytes.inputStream().use(::readLegacySpiderInfo) }.getOrNull()
    return info?.takeIf { it.gid > 0 && it.pages > 0 && it.startPage in 0 until it.pages && it.pTokenMap.keys.all { index -> index in 0 until it.pages } }
        ?.apply { pTokenMap = ConcurrentHashMap(pTokenMap) }
}

fun SpiderInfo.encodeSpiderInfo(): ByteArray = cbor.encodeToByteArray(this)

fun SpiderInfo.saveToUniFile(file: UniFile) = runSuspendCatching {
    file.openOutputStream().use { it.write(cbor.encodeToByteArray(this@saveToUniFile)) }
}.onFailure {
    Log.e(TAG, "Error", it)
}

fun readFromCache(gid: Long): SpiderInfo? = runCatching {
    EhDB.getReadingState(gid)?.let { state ->
        state.spiderInfo?.let {
            cbor.decodeFromByteArray<SpiderInfo>(it).apply {
                startPage = state.page
                pTokenMap = ConcurrentHashMap(pTokenMap)
            }
        }
    }
}.onFailure { Log.e(TAG, "Cannot read persisted spider info", it) }.getOrNull()

fun SpiderInfo.saveToCache() {
    EhDB.putReadingState(gid, spiderInfo = cbor.encodeToByteArray(this))
}

private fun readLegacySpiderInfo(inputStream: InputStream): SpiderInfo? {
    val source = inputStream.source().buffer()
    fun read(): String = source.readUtf8LineStrict()
    fun readInt(): Int = read().toInt()
    fun readLong(): Long = read().toLong()
    fun getVersion(str: String): Int = if (str.startsWith(VERSION_STR)) {
        NumberUtils.parseIntSafely(str.substring(VERSION_STR.length), -1)
    } else {
        1
    }
    val version = getVersion(read())
    var startPage = 0
    when (version) {
        VERSION -> {
            // Read next line
            startPage = read().toInt(16).coerceAtLeast(0)
        }
        1 -> {
            // pass
        }
        else -> {
            // Invalid version
            return null
        }
    }
    val gid = readLong()
    val token = read()
    read() // Deprecated, mode, skip it
    val previewPages = readInt()
    val previewPerPage = if (version == 1) 0 else readInt()
    val pages = readInt()
    if (gid == -1L || pages <= 0) return null
    val pTokenMap = hashMapOf<Int, String>()
    runCatching {
        while (true) {
            val line = read()
            val pos = line.indexOf(" ")
            if (pos > 0) {
                val index = line.substring(0, pos).toInt()
                val pToken = line.substring(pos + 1)
                if (pToken.isNotEmpty()) {
                    pTokenMap[index] = pToken
                }
            }
        }
    }
    return SpiderInfo(gid, token, pages, pTokenMap, startPage, previewPages, previewPerPage)
}

private val TAG = SpiderInfo::class.java.simpleName
private const val VERSION_STR = "VERSION"
private const val VERSION = 2
