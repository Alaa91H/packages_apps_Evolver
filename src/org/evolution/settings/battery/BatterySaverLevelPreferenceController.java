/*
 * Copyright (C) 2026 Evolution X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.evolution.settings.battery;

import android.content.Context;
import android.provider.Settings;

import java.io.File;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;

/** Controls selectable Battery Saver levels such as brightness reduction. */
public class BatterySaverLevelPreferenceController extends BasePreferenceController
        implements Preference.OnPreferenceChangeListener {

    public static final String KEY_CPU_LIMIT_PERCENT = "low_power_cpu_limit_percent";
    public static final String KEY_BRIGHTNESS_REDUCTION = "low_power_brightness_reduction";
    public static final String KEY_SCREEN_TIMEOUT = "low_power_screen_timeout";

    private static final String CPUFREQ_DIR = "/sys/devices/system/cpu/cpufreq";

    public BatterySaverLevelPreferenceController(Context context, String key) {
        super(context, key);
    }

    @Override
    public int getAvailabilityStatus() {
        if (KEY_CPU_LIMIT_PERCENT.equals(getPreferenceKey())
                && !new File(CPUFREQ_DIR).isDirectory()) {
            return UNSUPPORTED_ON_DEVICE;
        }
        return AVAILABLE;
    }

    @Override
    public void updateState(Preference preference) {
        if (!(preference instanceof ListPreference)) {
            return;
        }

        final ListPreference listPreference = (ListPreference) preference;
        listPreference.setValue(Integer.toString(getCurrentValue()));
        listPreference.setSummary(listPreference.getEntry());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final int value;
        try {
            value = Integer.parseInt(String.valueOf(newValue));
        } catch (NumberFormatException e) {
            return false;
        }

        final boolean success = Settings.Global.putInt(
                mContext.getContentResolver(), getPreferenceKey(), value);
        if (success && preference instanceof ListPreference) {
            final ListPreference listPreference = (ListPreference) preference;
            final int index = listPreference.findIndexOfValue(Integer.toString(value));
            if (index >= 0) {
                listPreference.setSummary(listPreference.getEntries()[index]);
            }
        }
        return success;
    }

    private int getCurrentValue() {
        final String stored = Settings.Global.getString(
                mContext.getContentResolver(), getPreferenceKey());
        if (stored != null) {
            try {
                final int value = Integer.parseInt(stored);
                // Compatibility with the first implementation where timeout was a boolean.
                if (KEY_SCREEN_TIMEOUT.equals(getPreferenceKey()) && value == 1) {
                    return 30000;
                }
                return value;
            } catch (NumberFormatException ignored) {
                // Treat malformed values as no change/platform default.
            }
        }
        return -1;
    }

    @Override
    public int getSliceHighlightMenuRes() {
        return R.string.menu_key_battery;
    }
}
