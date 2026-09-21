/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings

import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment

class EdgeLightSettings : SettingsPreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureLocationDefaults()
        addPreferencesFromResource(R.xml.edge_light_settings)
    }

    /**
     * Preserve the legacy visual when upgrading. The old straight style lit only the sides,
     * while the rounded/frame style lit the complete perimeter.
     */
    private fun ensureLocationDefaults() {
        val resolver = requireContext().contentResolver
        val style = Settings.System.getStringForUser(
            resolver, Settings.System.EDGE_LIGHT_STYLE, UserHandle.USER_CURRENT
        ) ?: "default"
        val frameStyle = style.equals("rounded", ignoreCase = true) ||
                style.equals("frame", ignoreCase = true)

        fun ensure(key: String, defaultValue: Boolean) {
            if (Settings.System.getStringForUser(
                    resolver, key, UserHandle.USER_CURRENT
                ) == null) {
                Settings.System.putIntForUser(
                    resolver, key, if (defaultValue) 1 else 0, UserHandle.USER_CURRENT
                )
            }
        }

        ensure(Settings.System.EDGE_LIGHT_TOP_ENABLED, frameStyle)
        ensure(Settings.System.EDGE_LIGHT_SIDES_ENABLED, true)
        ensure(Settings.System.EDGE_LIGHT_BOTTOM_ENABLED, frameStyle)

        if (Settings.System.getStringForUser(
                resolver,
                Settings.System.EDGE_LIGHT_AURORA_COLOR_MODE,
                UserHandle.USER_CURRENT
            ) == null) {
            Settings.System.putStringForUser(
                resolver,
                Settings.System.EDGE_LIGHT_AURORA_COLOR_MODE,
                "single",
                UserHandle.USER_CURRENT
            )
        }
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.EVOLVER

    companion object {
        @JvmStatic
        fun reset(context: Context) {
            val resolver = context.contentResolver
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_ENABLED, 0, UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_COLOR_MODE, "accent", UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_CUSTOM_COLOR, Color.WHITE, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_PULSE_COUNT, 1, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_STROKE_WIDTH, 8, UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_STYLE, "default", UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_ANIMATION_EFFECT, "none", UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_TOP_ENABLED, 0, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_SIDES_ENABLED, 1, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_BOTTOM_ENABLED, 0, UserHandle.USER_CURRENT)
            Settings.System.putStringForUser(resolver,
                    Settings.System.EDGE_LIGHT_AURORA_COLOR_MODE, "single", UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_SPREAD, 0, UserHandle.USER_CURRENT)
            Settings.System.putIntForUser(resolver,
                    Settings.System.EDGE_LIGHT_INTENSITY, 0, UserHandle.USER_CURRENT)
        }
    }
}
