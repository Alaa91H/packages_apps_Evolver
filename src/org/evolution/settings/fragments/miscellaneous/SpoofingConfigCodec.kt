/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class CustomSpoofProfile(
    val id: String,
    val name: String,
    val brand: String,
    val manufacturer: String,
    val device: String,
    val model: String,
    val fingerprint: String,
    val product: String,
)

internal data class DecodedSpoofAssignments(
    val values: LinkedHashMap<String, String>,
    val legacy: Boolean = false,
    val malformedEntries: Int = 0,
    val error: String? = null,
)

internal data class DecodedCustomSpoofProfiles(
    val profiles: List<CustomSpoofProfile>,
    val legacy: Boolean = false,
    val malformedEntries: Int = 0,
    val error: String? = null,
)

internal data class SpoofingBackupData(
    val enabled: Boolean,
    val assignments: LinkedHashMap<String, String>,
    val customProfiles: List<CustomSpoofProfile>,
)

internal object SpoofingConfigCodec {
    private const val CONFIG_VERSION = 2
    private const val BACKUP_VERSION = 1
    private const val BACKUP_KIND = "evolution_x_property_spoofing"

    private val packageNameRegex =
        Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*""")
    private val profileIdRegex =
        Regex("""[A-Za-z0-9._-]{1,64}""")
    private val fingerprintRegex =
        Regex("""^[^\s/]+/[^\s/]+/[^\s:]+:[^\s/]+/[^\s/]+/[^\s:]+:[^\s/]+/[^\s]+$""")

    fun decodeAssignments(raw: String?): DecodedSpoofAssignments {
        if (raw.isNullOrBlank()) return DecodedSpoofAssignments(linkedMapOf())

        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            val values = linkedMapOf<String, String>()
            var malformed = 0
            trimmed.split(",").forEach { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size != 2) {
                    malformed++
                    return@forEach
                }
                val pkg = parts[0].trim()
                val profile = parts[1].trim()
                if (!isValidPackageName(pkg) || !isValidProfileId(profile)) {
                    malformed++
                    return@forEach
                }
                values[pkg] = profile
            }
            return DecodedSpoofAssignments(
                values = values,
                legacy = true,
                malformedEntries = malformed,
            )
        }

        return try {
            val root = JSONObject(trimmed)
            val version = root.optInt("version", 0)
            val apps = root.optJSONObject("apps")
                ?: return DecodedSpoofAssignments(
                    linkedMapOf(),
                    error = "Missing apps object",
                )
            if (version != CONFIG_VERSION) {
                return DecodedSpoofAssignments(
                    linkedMapOf(),
                    error = "Unsupported config version: $version",
                )
            }

            val values = linkedMapOf<String, String>()
            var malformed = 0
            val keys = apps.keys()
            while (keys.hasNext()) {
                val pkg = keys.next().trim()
                val profile = apps.optString(pkg, "").trim()
                if (!isValidPackageName(pkg) || !isValidProfileId(profile)) {
                    malformed++
                    continue
                }
                values[pkg] = profile
            }
            DecodedSpoofAssignments(values, malformedEntries = malformed)
        } catch (e: Exception) {
            DecodedSpoofAssignments(
                linkedMapOf(),
                error = e.message ?: "Invalid assignment payload",
            )
        }
    }

    fun encodeAssignments(values: Map<String, String>): String {
        val apps = JSONObject()
        values.toSortedMap().forEach { (pkg, profile) ->
            if (isValidPackageName(pkg) && isValidProfileId(profile)) {
                apps.put(pkg, profile)
            }
        }
        return JSONObject()
            .put("version", CONFIG_VERSION)
            .put("apps", apps)
            .toString()
    }

    fun decodeCustomProfiles(raw: String?): DecodedCustomSpoofProfiles {
        if (raw.isNullOrBlank()) return DecodedCustomSpoofProfiles(emptyList())

        return try {
            val parsed = JSONTokener(raw.trim()).nextValue()
            val legacy = parsed is JSONArray
            val array = when (parsed) {
                is JSONArray -> parsed
                is JSONObject -> {
                    val version = parsed.optInt("version", 0)
                    if (version != CONFIG_VERSION) {
                        return DecodedCustomSpoofProfiles(
                            emptyList(),
                            error = "Unsupported custom profile version: $version",
                        )
                    }
                    parsed.optJSONArray("profiles")
                        ?: return DecodedCustomSpoofProfiles(
                            emptyList(),
                            error = "Missing profiles array",
                        )
                }
                else -> return DecodedCustomSpoofProfiles(
                    emptyList(),
                    error = "Unsupported custom profile payload",
                )
            }

            val profiles = mutableListOf<CustomSpoofProfile>()
            val seenIds = mutableSetOf<String>()
            var malformed = 0
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj == null) {
                    malformed++
                    continue
                }
                val profile = profileFromJson(obj)
                if (profile == null || !seenIds.add(profile.id)) {
                    malformed++
                    continue
                }
                profiles += profile
            }
            DecodedCustomSpoofProfiles(
                profiles = profiles,
                legacy = legacy,
                malformedEntries = malformed,
            )
        } catch (e: Exception) {
            DecodedCustomSpoofProfiles(
                emptyList(),
                error = e.message ?: "Invalid custom profile payload",
            )
        }
    }

    fun encodeCustomProfiles(profiles: List<CustomSpoofProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            if (validateCustomProfile(profile) == null) {
                array.put(profileToJson(profile))
            }
        }
        return JSONObject()
            .put("version", CONFIG_VERSION)
            .put("profiles", array)
            .toString()
    }

    fun encodeBackup(
        enabled: Boolean,
        assignments: Map<String, String>,
        customProfiles: List<CustomSpoofProfile>,
    ): String {
        val apps = JSONObject()
        assignments.toSortedMap().forEach { (pkg, profile) ->
            apps.put(pkg, profile)
        }
        val profiles = JSONArray()
        customProfiles.forEach { profiles.put(profileToJson(it)) }

        return JSONObject()
            .put("kind", BACKUP_KIND)
            .put("version", BACKUP_VERSION)
            .put("enabled", enabled)
            .put("assignments", apps)
            .put("customProfiles", profiles)
            .toString(2)
    }

    fun decodeBackup(
        raw: String,
        builtInProfileIds: Set<String>,
    ): Result<SpoofingBackupData> = runCatching {
        val root = JSONObject(raw)
        require(root.optString("kind") == BACKUP_KIND) {
            "Not an Evolution X property spoofing backup"
        }
        require(root.optInt("version", 0) == BACKUP_VERSION) {
            "Unsupported backup version"
        }

        val profilesArray = root.optJSONArray("customProfiles")
            ?: throw IllegalArgumentException("Missing customProfiles")
        val profiles = mutableListOf<CustomSpoofProfile>()
        val customIds = mutableSetOf<String>()
        for (i in 0 until profilesArray.length()) {
            val obj = profilesArray.optJSONObject(i)
                ?: throw IllegalArgumentException("Malformed custom profile")
            val profile = profileFromJson(obj)
                ?: throw IllegalArgumentException("Invalid custom profile")
            val validationError = validateCustomProfile(profile, builtInProfileIds)
            require(validationError == null) { validationError ?: "Invalid custom profile" }
            require(customIds.add(profile.id)) { "Duplicate custom profile id: ${profile.id}" }
            profiles += profile
        }

        val knownProfileIds = builtInProfileIds + customIds
        val assignmentsObject = root.optJSONObject("assignments")
            ?: throw IllegalArgumentException("Missing assignments")
        val assignments = linkedMapOf<String, String>()
        val keys = assignmentsObject.keys()
        while (keys.hasNext()) {
            val pkg = keys.next().trim()
            val profile = assignmentsObject.optString(pkg, "").trim()
            require(isValidPackageName(pkg)) { "Invalid package name: $pkg" }
            require(profile in knownProfileIds) { "Unknown spoof profile: $profile" }
            assignments[pkg] = profile
        }

        SpoofingBackupData(
            enabled = root.optBoolean("enabled", true),
            assignments = assignments,
            customProfiles = profiles,
        )
    }

    fun validateCustomProfile(
        profile: CustomSpoofProfile,
        reservedProfileIds: Set<String> = emptySet(),
    ): String? {
        if (!isValidProfileId(profile.id)) return "Invalid profile id"
        if (profile.id in reservedProfileIds) return "Profile id conflicts with a built-in profile"
        if (profile.name.isBlank()) return "Profile name is required"
        if (profile.brand.isBlank()) return "Brand is required"
        if (profile.manufacturer.isBlank()) return "Manufacturer is required"
        if (profile.device.isBlank()) return "Device is required"
        if (profile.model.isBlank()) return "Model is required"
        if (profile.fingerprint.isNotBlank() && !isValidFingerprint(profile.fingerprint)) {
            return "Invalid Android build fingerprint"
        }
        return null
    }

    fun isValidFingerprint(fingerprint: String): Boolean =
        fingerprint.length <= 512 && fingerprintRegex.matches(fingerprint)

    private fun isValidPackageName(packageName: String): Boolean =
        packageName.length <= 255 && packageNameRegex.matches(packageName)

    private fun isValidProfileId(profileId: String): Boolean =
        profileIdRegex.matches(profileId)

    private fun profileFromJson(obj: JSONObject): CustomSpoofProfile? {
        val profile = CustomSpoofProfile(
            id = obj.optString("id", "").trim(),
            name = obj.optString("name", "").trim(),
            brand = obj.optString("brand", "").trim(),
            manufacturer = obj.optString("manufacturer", "").trim(),
            device = obj.optString("device", "").trim(),
            model = obj.optString("model", "").trim(),
            fingerprint = obj.optString("fingerprint", "").trim(),
            product = obj.optString("product", "").trim(),
        )
        return if (validateCustomProfile(profile) == null) profile else null
    }

    private fun profileToJson(profile: CustomSpoofProfile) = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("brand", profile.brand)
        put("manufacturer", profile.manufacturer)
        put("device", profile.device)
        put("model", profile.model)
        put("fingerprint", profile.fingerprint)
        put("product", profile.product)
    }
}
