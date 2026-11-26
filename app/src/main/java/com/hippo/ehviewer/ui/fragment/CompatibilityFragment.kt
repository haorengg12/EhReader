// Copyright (C) 2026 haorengg12. SPDX-License-Identifier: GPL-3.0-only
package com.hippo.ehviewer.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.preference.Preference
import com.hippo.ehviewer.R

/** 旧版本兼容功能的独立入口；本阶段只提示未开放，不读取或修改旧数据。 */
class CompatibilityFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.compatibility_settings)
        findPreference<Preference>(KEY_IMPORT_LEGACY)?.onPreferenceClickListener = this
        findPreference<Preference>(KEY_RESTORE_LEGACY_DOWNLOADS)?.onPreferenceClickListener = this
    }

    override fun onPreferenceClick(preference: Preference): Boolean = when (preference.key) {
        KEY_IMPORT_LEGACY, KEY_RESTORE_LEGACY_DOWNLOADS -> {
            Toast.makeText(requireContext(), R.string.settings_compatibility_unavailable, Toast.LENGTH_SHORT).show()
            true
        }
        else -> super.onPreferenceClick(preference)
    }

    @get:StringRes
    override val fragmentTitle: Int
        get() = R.string.settings_compatibility

    companion object {
        private const val KEY_IMPORT_LEGACY = "import_legacy_ehviewer"
        private const val KEY_RESTORE_LEGACY_DOWNLOADS = "restore_legacy_downloads"
    }
}
