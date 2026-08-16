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
                dpm.setApplicationRestrictions(dar, packageName, transformAppRestriction(list))
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
            val childValues = values?.getBundle(e.key)
            return e.restrictions.orEmpty().flatMap {
                transformRestrictionEntry(it, path + e.key, childValues)
            }
        }
        if (e.type == RestrictionEntry.TYPE_BUNDLE_ARRAY) {
            return e.restrictions.orEmpty().flatMap {
                transformRestrictionEntry(it, path + e.key, null)
            }
        }

        val hidden = e.type == RestrictionEntry.TYPE_NULL
        val r: AppRestriction = when (e.type) {
            RestrictionEntry.TYPE_INTEGER -> AppRestriction.IntItem(
                e.key, e.title, e.description, null, path, hidden
            )
            RestrictionEntry.TYPE_STRING, RestrictionEntry.TYPE_NULL -> AppRestriction.StringItem(
                e.key, e.title, e.description, null, path, hidden
            )
            RestrictionEntry.TYPE_BOOLEAN -> AppRestriction.BooleanItem(
                e.key, e.title, e.description, null, path, hidden
            )
            RestrictionEntry.TYPE_CHOICE -> AppRestriction.ChoiceItem(
                e.key, e.title, e.description,
                e.choiceEntries ?: emptyArray(), e.choiceValues ?: emptyArray(), null, path, hidden
            )
            RestrictionEntry.TYPE_MULTI_SELECT -> AppRestriction.MultiSelectItem(
                e.key, e.title, e.description,
                e.choiceEntries ?: emptyArray(), e.choiceValues ?: emptyArray(), null, path, hidden
            )
            else -> return emptyList()
        }

        if (values?.containsKey(e.key) == true) {
            when (r) {
                is AppRestriction.BooleanItem -> r.value = values.getBoolean(e.key)
                is AppRestriction.StringItem -> r.value = values.getString(e.key)
                is AppRestriction.IntItem -> r.value = values.getInt(e.key)
                is AppRestriction.ChoiceItem -> r.value = values.getString(e.key)
                is AppRestriction.MultiSelectItem -> r.value = values.getStringArray(e.key)
            }
        }
        return listOf(r)
    }

    private fun transformAppRestriction(list: List<AppRestriction>): Bundle {
        val root = Bundle()
        for (r in list) {
            var target = root
            for (segment in r.path) {
                var child = target.getBundle(segment)
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
                is AppRestriction.MultiSelectItem -> r.value?.let { target.putStringArray(r.key, it) }
            }
        }
        return root
    }

    override fun onCleared() {
        viewModelScope.cancel()
        super.onCleared()
    }
}
