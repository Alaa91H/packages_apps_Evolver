/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.android.OmniJawsClient;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.utils.PreferenceUtils;
import org.evolution.settings.utils.SystemUtils;

/**
 * Unified lock screen customization surface.
 *
 * This fragment intentionally reuses the same Settings.System / Settings.Secure
 * keys as the existing Evolver pages. It is a hub, not a second configuration
 * store, so changes made here and in the legacy pages always stay in sync.
 */
@SearchIndexable
public class LockscreenCustomization extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_SMARTSPACE = "lockscreen_smartspace_enabled";
    private static final String KEY_WEATHER = "lockscreen_weather_enabled";

    private SwitchPreferenceCompat mSmartspace;
    private SwitchPreferenceCompat mWeather;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.lockscreen_customization);

        mSmartspace = findPreference(KEY_SMARTSPACE);
        mWeather = findPreference(KEY_WEATHER);

        if (mSmartspace != null) {
            mSmartspace.setOnPreferenceChangeListener(this);
        }
        if (mWeather != null) {
            mWeather.setOnPreferenceChangeListener(this);
        }

        updateWeatherSettings();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mSmartspace) {
            updateWeatherSettings((Boolean) newValue);
            SystemUtils.showSystemUiRestartDialog(getContext());
            return true;
        }

        if (preference == mWeather) {
            SystemUtils.showSystemUiRestartDialog(getContext());
            return true;
        }

        return false;
    }

    private void updateWeatherSettings() {
        updateWeatherSettings(mSmartspace != null && mSmartspace.isChecked());
    }

    private void updateWeatherSettings(boolean smartspaceEnabled) {
        if (mWeather == null) {
            return;
        }

        final Context context = getContext();
        if (context == null) {
            return;
        }

        final boolean weatherServiceEnabled =
                OmniJawsClient.get().isOmniJawsEnabled(context);
        final boolean weatherAvailable = !smartspaceEnabled && weatherServiceEnabled;

        mWeather.setEnabled(weatherAvailable);
        mWeather.setSummary(weatherAvailable
                ? R.string.lockscreen_weather_summary
                : R.string.lockscreen_weather_enabled_info);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWeatherSettings();
        PreferenceUtils.reloadCustomPrimarySwitches(getPreferenceScreen());
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.lockscreen_customization);
}
