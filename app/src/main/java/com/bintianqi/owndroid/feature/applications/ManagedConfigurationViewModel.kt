package com.bintianqi.owndroid.feature.applications

import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.os.Bundle
import android.os.Parcelable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bintianqi.owndroid.MyApplication
import com.bintianqi.owndroid.PrivilegeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ManagedConfigurationViewModel(
    val packageName: String, val application: MyApplication, val ph: PrivilegeHelper
) : ViewModel() {
    val restrictionsState = MutableStateFlow(emptyList<AppRestriction>())

    init {
        viewModelScope.launch(Dispatchers.IO) { getRestrictionsWithoutCoroutine() }
    }

    private fun restrictionsManager(): RestrictionsManager =
        application.getSystemService(RestrictionsManager::class.java)

    private fun getRestrictionsWithoutCoroutine() {
        try {
            val rm = restrictionsManager()
            ph.safeDpmCall {
                val bundle = dpm.getApplicationRestrictions(dar, packageName)
                restrictionsState.value = rm.getManifestRestrictions(packageName)
                    ?.flatMap { transformRestrictionEntry(it, emptyList(), bundle) }
                    ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setRestriction(item: AppRestriction) {
        viewModelScope.launch(Dispatchers.IO) {
            ph.safeDpmCall {
                val identity = { r: AppRestriction -> r.path + r.key }
                val edited = restrictionsState.value
                    .filter { identity(it) != identity(item) }
                    .plus(item)
                val existing = dpm.getApplicationRestrictions(dar, packageName)
                val schema = restrictionsManager().getManifestRestrictions(packageName).orEmpty()
                val bundle = buildFullConfiguration(schema, edited, existing, emptyList())
                dpm.setApplicationRestrictions(dar, packageName, bundle)
                getRestrictionsWithoutCoroutine()
            }
        }
    }

    fun clearRestrictions() {
        viewModelScope.launch(Dispatchers.IO) {
            ph.safeDpmCall {
                dpm.setApplicationRestrictions(dar, packageName, Bundle())
                getRestrictionsWithoutCoroutine()
            }
        }
    }

    private fun transformRestrictionEntry(
        e: RestrictionEntry, path: List<String>, values: Bundle?
    ): List<AppRestriction> {
        if (e.type == RestrictionEntry.TYPE_BUNDLE) {
            val childValues = runCatching { values?.getBundle(e.key) }.getOrNull()
            return e.restrictions.orEmpty().flatMap {
                transformRestrictionEntry(it, path + e.key, childValues)
            }
        }

        // Bundle arrays require an array editor to expose individual rows safely.
        // They are still preserved/serialized by buildFullConfiguration().
        if (e.type == RestrictionEntry.TYPE_BUNDLE_ARRAY) return emptyList()

        val hidden = e.type == RestrictionEntry.TYPE_NULL
        val r: AppRestriction = when (e.type) {
            RestrictionEntry.TYPE_INTEGER -> AppRestriction.IntItem(
                e.key, e.title, e.description, null, path, hidden
            )
            RestrictionEntry.TYPE_STRING -> AppRestriction.StringItem(
                e.key, e.title, e.description, null, path, hidden
            )
            RestrictionEntry.TYPE_NULL -> AppRestriction.StringItem(
                e.key,
                e.title,
                e.description,
                runCatching { values?.getString(e.key) }.getOrNull() ?: e.selectedString,
                path,
                true
            )
            RestrictionEntry.TYPE_BOOLEAN -> AppRestriction.BooleanItem(
                e.key, e.title, e.description, null, path, hidden
            )
            RestrictionEntry.TYPE_CHOICE -> AppRestriction.ChoiceItem(
                e.key,
                e.title,
                e.description,
                e.choiceEntries ?: emptyArray(),
                e.choiceValues ?: emptyArray(),
                null,
                path,
                hidden
            )
            RestrictionEntry.TYPE_MULTI_SELECT -> AppRestriction.MultiSelectItem(
                e.key,
                e.title,
                e.description,
                e.choiceEntries ?: emptyArray(),
                e.choiceValues ?: emptyArray(),
                null,
                path,
                hidden
            )
            else -> return emptyList()
        }

        if (!hidden && values?.containsKey(e.key) == true) {
            when (r) {
                is AppRestriction.BooleanItem -> r.value =
                    runCatching { values.getBoolean(e.key) }.getOrNull()
                is AppRestriction.StringItem -> r.value =
                    runCatching { values.getString(e.key) }.getOrNull()
                is AppRestriction.IntItem -> r.value =
                    runCatching { values.getInt(e.key) }.getOrNull()
                is AppRestriction.ChoiceItem -> r.value =
                    runCatching { values.getString(e.key) }.getOrNull()
                is AppRestriction.MultiSelectItem -> r.value =
                    runCatching { values.getStringArray(e.key) }.getOrNull()
            }
        }
        return listOf(r)
    }

    /**
     * Reconstruct the complete managed-configuration tree from the application's
     * RestrictionEntry schema, overlaying the administrator's edited leaf values.
     *
     * Samsung KSP requires UEMs to send the complete OEMConfig schema, including
     * unmodified/default and hidden values. TYPE_BUNDLE is emitted as Bundle and
     * TYPE_BUNDLE_ARRAY as Parcelable[] (preserving any existing array values).
     */
    private fun buildFullConfiguration(
        schema: List<RestrictionEntry>,
        edited: List<AppRestriction>,
        existing: Bundle?,
        path: List<String>
    ): Bundle {
        val out = Bundle()
        for (entry in schema) {
            when (entry.type) {
                RestrictionEntry.TYPE_BUNDLE -> {
                    val oldChild = runCatching { existing?.getBundle(entry.key) }.getOrNull()
                    out.putBundle(
                        entry.key,
                        buildFullConfiguration(
                            entry.restrictions.orEmpty(), edited, oldChild, path + entry.key
                        )
                    )
                }

                RestrictionEntry.TYPE_BUNDLE_ARRAY -> {
                    val oldArray: Array<Parcelable>? = runCatching {
                        existing?.getParcelableArray(entry.key)
                    }.getOrNull()
                    out.putParcelableArray(entry.key, oldArray ?: emptyArray())
                }

                RestrictionEntry.TYPE_INTEGER -> {
                    val v = findEdited(edited, path, entry.key) as? AppRestriction.IntItem
                    val value = v?.value
                        ?: if (existing?.containsKey(entry.key) == true)
                            runCatching { existing.getInt(entry.key) }.getOrDefault(entry.intValue)
                        else entry.intValue
                    out.putInt(entry.key, value)
                }

                RestrictionEntry.TYPE_BOOLEAN -> {
                    val v = findEdited(edited, path, entry.key) as? AppRestriction.BooleanItem
                    val value = v?.value
                        ?: if (existing?.containsKey(entry.key) == true)
                            runCatching { existing.getBoolean(entry.key) }
                                .getOrDefault(entry.selectedState)
                        else entry.selectedState
                    out.putBoolean(entry.key, value)
                }

                RestrictionEntry.TYPE_MULTI_SELECT -> {
                    val v = findEdited(edited, path, entry.key) as? AppRestriction.MultiSelectItem
                    val value = v?.value
                        ?: if (existing?.containsKey(entry.key) == true)
                            runCatching { existing.getStringArray(entry.key) }.getOrNull()
                        else null
                        ?: entry.allSelectedStrings
                        ?: emptyArray()
                    out.putStringArray(entry.key, value)
                }

                RestrictionEntry.TYPE_STRING,
                RestrictionEntry.TYPE_CHOICE,
                RestrictionEntry.TYPE_NULL -> {
                    val editedValue = when (val v = findEdited(edited, path, entry.key)) {
                        is AppRestriction.StringItem -> v.value
                        is AppRestriction.ChoiceItem -> v.value
                        else -> null
                    }
                    val value = editedValue
                        ?: if (existing?.containsKey(entry.key) == true)
                            runCatching { existing.getString(entry.key) }.getOrNull()
                        else null
                        ?: entry.selectedString
                        ?: ""
                    out.putString(entry.key, value)
                }
            }
        }
        return out
    }

    private fun findEdited(
        edited: List<AppRestriction>, path: List<String>, key: String
    ): AppRestriction? = edited.lastOrNull { it.path == path && it.key == key }

    override fun onCleared() {
        viewModelScope.cancel()
        super.onCleared()
    }
}
