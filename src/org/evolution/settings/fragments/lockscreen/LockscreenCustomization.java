/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.android.OmniJawsClient;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.utils.DeviceUtils;
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
    private static final String KEY_RIPPLE_EFFECT = "enable_ripple_effect";
    private static final String KEY_FACE_UNLOCK_SCAN_EFFECT = "face_unlock_scan_effect";
    private static final String KEY_FP_SUCCESS = "fp_success_vibrate";
    private static final String KEY_FP_ERROR = "fp_error_vibrate";
    private static final String KEY_INTERACTION_CATEGORY = "lockscreen_studio_interaction_category";

    private SwitchPreferenceCompat mSmartspace;
    private SwitchPreferenceCompat mWeather;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.lockscreen_customization);

        mSmartspace = findPreference(KEY_SMARTSPACE);
        mWeather = findPreference(KEY_WEATHER);

        configureBiometricPreferences();

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

    private void configureBiometricPreferences() {
        final Context context = getContext();
        final PreferenceCategory interactionCategory =
                findPreference(KEY_INTERACTION_CATEGORY);
        if (context == null || interactionCategory == null) {
            return;
        }

        final Preference rippleEffect = findPreference(KEY_RIPPLE_EFFECT);
        final Preference faceUnlockScanEffect = findPreference(KEY_FACE_UNLOCK_SCAN_EFFECT);
        final Preference fpSuccess = findPreference(KEY_FP_SUCCESS);
        final Preference fpError = findPreference(KEY_FP_ERROR);
        final boolean hasFingerprint = DeviceUtils.hasFingerprint(context);

        if (!hasFingerprint && rippleEffect != null) {
            interactionCategory.removePreference(rippleEffect);
        }

        if (!DeviceUtils.hasFace(context) && faceUnlockScanEffect != null) {
            interactionCategory.removePreference(faceUnlockScanEffect);
        }

        if ((!hasFingerprint || !DeviceUtils.hasVibrator(context))) {
            if (fpSuccess != null) {
                interactionCategory.removePreference(fpSuccess);
            }
            if (fpError != null) {
                interactionCategory.removePreference(fpError);
            }
        }
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
            new BaseSearchIndexProvider(R.xml.lockscreen_customization) {
                @Override
                public java.util.List<String> getNonIndexableKeys(Context context) {
                    final java.util.List<String> keys = super.getNonIndexableKeys(context);
                    final boolean hasFingerprint = DeviceUtils.hasFingerprint(context);

                    if (!hasFingerprint) {
                        keys.add(KEY_RIPPLE_EFFECT);
                    }

                    if (!DeviceUtils.hasFace(context)) {
                        keys.add(KEY_FACE_UNLOCK_SCAN_EFFECT);
                    }

                    if (!hasFingerprint || !DeviceUtils.hasVibrator(context)) {
                        keys.add(KEY_FP_SUCCESS);
                        keys.add(KEY_FP_ERROR);
                    }

                    return keys;
                }
            };
}
