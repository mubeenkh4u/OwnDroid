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

        /*
         * Bundle arrays cannot be represented by OwnDroid's single-value editor.
         * The old KSP patch flattened them as ordinary Bundle paths. That produced
         * Bundle values where OEMConfig expects Parcelable[]/Bundle[], causing KSP's
         * "android.os.Bundle cannot be cast to android.os.Parcelable[]" failure.
         *
         * Do not expose bundle-array children until the UI has a real add/remove
         * array editor. More importantly, never serialize them as ordinary Bundles.
         */
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

    /**
     * Build a managed-configuration Bundle from editable leaf restrictions.
     *
     * A path is created only after a leaf actually has a value. This is important
     * for OEMConfig: creating every schema path as an empty Bundle makes KSP try to
     * parse inactive policy sections and their mandatory control fields. It also
     * used to turn bundle-array schema nodes into ordinary Bundles.
     */
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

    /**
     * KSP publishes both Device Owner (doPolicies) and Profile Owner (poPolicies)
     * policy trees in the same OEMConfig schema. On a fully-managed Device Owner
     * device, sending a partially populated poPolicies tree makes KSP reject the
     * entire profile because poPoliciesIsControlled is mandatory.
     *
     * OwnDroid is the Device Owner in this mode, so discard Profile Owner policy
     * data and ensure the mandatory DO/application-management control switches are
     * present whenever their corresponding bundles are being sent.
     */
    private fun normalizeKnoxServicePlugin(root: Bundle) {
        if (packageName != KNOX_SERVICE_PLUGIN_PACKAGE) return
        if (!dpm.isDeviceOwnerApp(application.packageName)) return

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
