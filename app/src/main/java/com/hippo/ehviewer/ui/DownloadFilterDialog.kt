// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.ui

import android.content.Context
import android.text.InputType
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadTagKey
import com.hippo.ehviewer.download.DownloadFilter
import com.hippo.ehviewer.download.DownloadFilterSort
import com.hippo.ehviewer.download.DownloadFilterStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 下载专用离线面板；译名只作展示，选择与手工输入始终使用原始标签身份。 */
internal class DownloadFilterDialog(
    private val context: Context,
    parentScope: CoroutineScope,
    private val initial: DownloadFilter,
    private val group: String,
    private val apply: (DownloadFilter?) -> Unit,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private val selected = listOf(initial.all.toMutableSet(), initial.any.toMutableSet(), initial.excluded.toMutableSet())
    private val modes = context.resources.getStringArray(R.array.download_tag_modes)
    private val padding = (16 * context.resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding / 2, padding, padding / 2)
    }
    private fun LinearLayout.label(text: String) = TextView(context).also {
        it.text = text
        addView(it)
    }
    private fun LinearLayout.input(hint: Int, value: String = "") = EditText(context).also {
        it.hint = context.getString(hint)
        it.setText(value)
        it.setSingleLine()
        addView(it)
    }
    private fun LinearLayout.button(text: Int, action: () -> Unit) = Button(context).also {
        it.setText(text)
        it.setOnClickListener { action() }
        addView(it)
    }
    private fun LinearLayout.spinner(title: Int, values: List<String>, position: Int = 0): Spinner {
        label(context.getString(title))
        return Spinner(context).also {
            it.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            it.setSelection(position.coerceAtLeast(0))
            addView(it)
        }
    }

    fun show() {
        val form = column()
        form.label(context.getString(R.string.offline_filter_scope, group))
        val title = form.input(R.string.download_filter_title, initial.title)
        val categories = listOf(null, EhUtils.DOUJINSHI, EhUtils.MANGA, EhUtils.ARTIST_CG, EhUtils.GAME_CG, EhUtils.WESTERN, EhUtils.NON_H, EhUtils.IMAGE_SET, EhUtils.COSPLAY, EhUtils.ASIAN_PORN, EhUtils.MISC, EhUtils.PRIVATE, EhUtils.UNKNOWN)
        val category = form.spinner(R.string.offline_filter_category, categories.map { it?.let(EhUtils::getCategory) ?: context.getString(R.string.download_all) }, categories.indexOf(initial.category))
        val languages = mutableListOf<String?>(null, "")
        initial.language?.takeIf { it.isNotEmpty() }?.let { languages.add(it) }
        val languageNames = languages.map { it?.ifEmpty { context.getString(R.string.offline_filter_unknown) } ?: context.getString(R.string.download_all) }.toMutableList()
        val language = form.spinner(R.string.offline_filter_language, languageNames, languages.indexOf(initial.language))
        scope.launch {
            try {
                DownloadFilterStore.languages().forEach {
                    if (it !in languages) {
                        languages.add(it)
                        languageNames.add(it)
                    }
                }
                (language.adapter as ArrayAdapter<*>).notifyDataSetChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DownloadFilterDialog", "Language lookup failed", e)
            }
        }
        val minimum = form.input(R.string.offline_filter_min_rating, initial.minimumRating?.toString().orEmpty())
        val maximum = form.input(R.string.offline_filter_max_rating, initial.maximumRating?.toString().orEmpty())
        minimum.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        maximum.inputType = minimum.inputType
        val state = form.spinner(R.string.download_filter, context.resources.getStringArray(R.array.download_state).take(6), (initial.state ?: -1) + 1)
        val sort = form.spinner(R.string.download_sort_by, context.resources.getStringArray(R.array.download_offline_sort).toList(), initial.sort.ordinal)
        val tags = column()
        form.addView(tags)
        fun renderTags() {
            tags.removeAllViews()
            selected.forEachIndexed { index, keys ->
                tags.label(modes[index])
                keys.forEach { key ->
                    tags.addView(
                        Button(context).apply {
                            isAllCaps = false
                            text = context.getString(R.string.offline_filter_remove_tag, display(key))
                            setOnClickListener {
                                keys.remove(key)
                                renderTags()
                            }
                        },
                    )
                }
            }
        }
        renderTags()
        val mode = form.spinner(R.string.offline_filter_tag_mode, modes.toList())
        val manual = form.input(R.string.offline_filter_tag_hint)
        form.button(R.string.offline_filter_add_tag) {
            val key = DownloadTagKey.parse(manual.text.toString())
            if (key.tag.isEmpty()) {
                manual.error = context.getString(R.string.text_is_empty)
            } else {
                selected[mode.selectedItemPosition].add(key)
                manual.setText("")
                renderTags()
            }
        }
        form.button(R.string.offline_filter_tags) {
            showTags { key ->
                selected[mode.selectedItemPosition].add(key)
                renderTags()
            }
        }
        val status = form.label("")
        val rebuild = form.button(R.string.offline_filter_rebuild) {}
        rebuild.setOnClickListener {
            rebuild.isEnabled = false
            status.setText(R.string.offline_filter_loading)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { EhDB.rebuildDownloadTags() }
                    status.setText(R.string.offline_filter_rebuilt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("DownloadFilterDialog", "Tag operation failed", e)
                    status.setText(R.string.offline_filter_failed)
                } finally {
                    rebuild.isEnabled = true
                }
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.offline_filter)
            .setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.offline_filter_clear) { _, _ -> apply(null) }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnDismissListener { scope.cancel() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun rating(field: EditText): Float? {
                    if (field.text.isBlank()) return null
                    val value = field.text.toString().toFloatOrNull()
                    require(value != null && value.isFinite() && value in 0f..5f)
                    return value
                }
                val ratings = runCatching { rating(minimum) to rating(maximum) }.getOrNull()
                if (ratings == null || (ratings.first != null && ratings.second != null && ratings.first!! > ratings.second!!)) {
                    status.setText(R.string.offline_filter_rating_error)
                    return@setOnClickListener
                }
                val filter = DownloadFilter(
                    all = selected[0].toList(),
                    any = selected[1].toList(),
                    excluded = selected[2].toList(),
                    title = title.text.toString(),
                    category = categories[category.selectedItemPosition],
                    language = languages[language.selectedItemPosition],
                    minimumRating = ratings.first,
                    maximumRating = ratings.second,
                    state = (state.selectedItemPosition - 1).takeIf { it >= 0 },
                    sort = DownloadFilterSort.entries[sort.selectedItemPosition],
                )
                if (filter != DownloadFilter()) apply(filter)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 全局计数按页读取；搜索与翻页取消旧请求，避免较慢结果覆盖较新输入。 */
    private fun showTags(onSelect: (DownloadTagKey) -> Unit) {
        val form = column()
        form.label(context.getString(R.string.offline_filter_global_counts))
        val search = form.input(R.string.offline_filter_tag_search)
        val status = form.label("")
        val rows = column()
        form.addView(rows)
        var page = 0
        var job: Job? = null
        val previous = form.button(R.string.offline_filter_previous) {}
        val next = form.button(R.string.offline_filter_next) {}
        val dialog = AlertDialog.Builder(context).setTitle(R.string.offline_filter_tags)
            .setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null).create()
        fun load() {
            job?.cancel()
            rows.removeAllViews()
            previous.isEnabled = false
            next.isEnabled = false
            status.setText(R.string.offline_filter_loading)
            job = scope.launch {
                delay(200)
                try {
                    val result = DownloadFilterStore.tags(search.text.toString(), page)
                    status.text = context.getString(R.string.offline_filter_tag_page, page + 1)
                    result.take(50).forEach { row ->
                        rows.addView(
                            Button(context).apply {
                                isAllCaps = false
                                text = context.resources.getQuantityString(R.plurals.offline_filter_tag_count, row.count, display(row.key), row.count)
                                setOnClickListener {
                                    onSelect(row.key)
                                    dialog.dismiss()
                                }
                            },
                        )
                    }
                    if (result.isEmpty()) status.setText(R.string.offline_filter_empty_tags)
                    previous.isEnabled = page > 0
                    next.isEnabled = result.size > 50
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("DownloadFilterDialog", "Tag operation failed", e)
                    status.setText(R.string.offline_filter_failed)
                }
            }
        }
        previous.setOnClickListener {
            page--
            load()
        }
        next.setOnClickListener {
            page++
            load()
        }
        search.doAfterTextChanged {
            page = 0
            load()
        }
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        load()
    }

    private fun display(key: DownloadTagKey): String {
        val translation = if (EhTagDatabase.isInitialized()) EhTagDatabase.namespaceToPrefix(key.namespace)?.let { EhTagDatabase.getTranslation(it, key.tag) } else null
        return if (translation == null || translation == key.tag) key.identity else "$translation (${key.identity})"
    }
}
