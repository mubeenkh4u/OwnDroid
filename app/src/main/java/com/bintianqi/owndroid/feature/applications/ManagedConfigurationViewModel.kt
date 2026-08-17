package com.bintianqi.owndroid.feature.applications

import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.os.Bundle
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

    private fun getRestrictionsWithoutCoroutine() {
        try {
            val rm = application.getSystemService(RestrictionsManager::class.java)
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
                val list = restrictionsState.value
                    .filter { identity(it) != identity(item) }
                    .plus(item)
                val bundle = transformAppRestriction(list)
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

        if (e.type == RestrictionEntry.TYPE_BUNDLE_ARRAY) {
            return emptyList()
        }

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

    private fun transformAppRestriction(list: List<AppRestriction>): Bundle {
        val root = Bundle()

        for (r in list) {
            if (r.isNull()) continue

            var target = root
            for (segment in r.path) {
                var child = runCatching { target.getBundle(segment) }.getOrNull()
                if (child == null) {
                    child = Bundle()
                    target.putBundle(segment, child)
                }
                target = child
            }

            when (r) {
                is AppRestriction.IntItem -> r.value?.let { target.putInt(r.key, it) }
                is AppRestriction.StringItem -> r.value?.let { target.putString(r.key, it) }
                is AppRestriction.BooleanItem -> r.value?.let { target.putBoolean(r.key, it) }
                is AppRestriction.ChoiceItem -> r.value?.let { target.putString(r.key, it) }
                is AppRestriction.MultiSelectItem -> r.value?.let {
                    target.putStringArray(r.key, it)
                }
            }
        }

        normalizeKnoxServicePlugin(root)
        return root
    }

    private fun normalizeKnoxServicePlugin(root: Bundle) {
        if (packageName != KNOX_SERVICE_PLUGIN_PACKAGE) return
        if (!ph.myDpm.isDeviceOwnerApp(application.packageName)) return

        root.remove("poPolicies")

        val doPolicies = runCatching { root.getBundle("doPolicies") }.getOrNull()
        if (doPolicies != null) {
            if (!doPolicies.containsKey("doPoliciesIsControlled")) {
                doPolicies.putBoolean("doPoliciesIsControlled", true)
            }

            val appManagement = runCatching { doPolicies.getBundle("doAppMgmt") }.getOrNull()
            if (appManagement != null && !appManagement.containsKey("doAppMgmtIsControlled")) {
                appManagement.putBoolean("doAppMgmtIsControlled", true)
            }
        }
    }

    override fun onCleared() {
        viewModelScope.cancel()
        super.onCleared()
    }

    companion object {
        private const val KNOX_SERVICE_PLUGIN_PACKAGE = "com.samsung.android.knox.kpu"
    }
}
