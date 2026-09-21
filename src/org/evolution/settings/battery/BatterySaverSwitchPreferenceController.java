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
import android.content.pm.PackageManager;
import android.os.BatterySaverPolicyConfig;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import com.android.settings.R;
import com.android.settings.core.TogglePreferenceController;

/**
 * Controller for persistent Battery Saver switches backed by Settings.Global.
 *
 * AOD and Dark theme fall back to the device's existing full Battery Saver
 * policy until the user explicitly changes the switch.
 */
public class BatterySaverSwitchPreferenceController extends TogglePreferenceController {

    public static final String KEY_DISABLE_AOD = "low_power_disable_aod";
    public static final String KEY_DISABLE_5G = "low_power_disable_5g";
    public static final String KEY_FORCE_DARK = "low_power_force_dark";
    public static final String KEY_SCREEN_TIMEOUT = "low_power_screen_timeout";

    private final PowerManager mPowerManager;
    private final TelephonyManager mTelephonyManager;

    public BatterySaverSwitchPreferenceController(Context context, String key) {
        super(context, key);
        mPowerManager = context.getSystemService(PowerManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
    }

    @Override
    public int getAvailabilityStatus() {
        if (KEY_DISABLE_5G.equals(getPreferenceKey())) {
            final PackageManager packageManager = mContext.getPackageManager();
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                    || !packageManager.hasSystemFeature(
                            PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)) {
                return UNSUPPORTED_ON_DEVICE;
            }

            if (mTelephonyManager != null) {
                try {
                    final long supportedRaf =
                            mTelephonyManager.getSupportedRadioAccessFamily();
                    if (supportedRaf != TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN
                            && (supportedRaf & TelephonyManager.NETWORK_TYPE_BITMASK_NR) == 0) {
                        return UNSUPPORTED_ON_DEVICE;
                    }
                } catch (SecurityException | UnsupportedOperationException ignored) {
                    // If support cannot be queried reliably, keep the option available rather
                    // than hiding a valid control on a device whose telephony service is not
                    // ready yet or uses a vendor-specific implementation.
                }
            }
        }
        return AVAILABLE;
    }

    @Override
    public boolean isChecked() {
        final String value = Settings.Global.getString(
                mContext.getContentResolver(), getPreferenceKey());
        if (value != null) {
            try {
                return Integer.parseInt(value) != 0;
            } catch (NumberFormatException ignored) {
                // Fall through to the policy/default value.
            }
        }

        if (mPowerManager != null) {
            final BatterySaverPolicyConfig policy = mPowerManager.getFullPowerSavePolicy();
            if (KEY_DISABLE_AOD.equals(getPreferenceKey())) {
                return policy.getDisableAod();
            }
            if (KEY_FORCE_DARK.equals(getPreferenceKey())) {
                return policy.getEnableNightMode();
            }
        }

        return false;
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        return Settings.Global.putInt(
                mContext.getContentResolver(), getPreferenceKey(), isChecked ? 1 : 0);
    }

    @Override
    public int getSliceHighlightMenuRes() {
        return R.string.menu_key_battery;
    }
}
