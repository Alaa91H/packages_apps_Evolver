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
import android.os.BatterySaverPolicyConfig;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;

/** Controls selectable Battery Saver levels such as brightness reduction. */
public class BatterySaverLevelPreferenceController extends BasePreferenceController
        implements Preference.OnPreferenceChangeListener {

    public static final String KEY_BRIGHTNESS_REDUCTION = "low_power_brightness_reduction";

    private final PowerManager mPowerManager;

    public BatterySaverLevelPreferenceController(Context context, String key) {
        super(context, key);
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    @Override
    public int getAvailabilityStatus() {
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
                return Integer.parseInt(stored);
            } catch (NumberFormatException ignored) {
                // Fall back to the platform policy below.
            }
        }

        if (KEY_BRIGHTNESS_REDUCTION.equals(getPreferenceKey()) && mPowerManager != null) {
            final BatterySaverPolicyConfig policy = mPowerManager.getFullPowerSavePolicy();
            if (!policy.getEnableAdjustBrightness()) {
                return 0;
            }

            final int reduction = Math.round((1.0f - policy.getAdjustBrightnessFactor()) * 100f);
            if (reduction <= 0) {
                return 0;
            }
            return Math.max(10, Math.min(50, Math.round(reduction / 10.0f) * 10));
        }

        return 0;
    }

    @Override
    public int getSliceHighlightMenuRes() {
        return R.string.menu_key_battery;
    }
}
