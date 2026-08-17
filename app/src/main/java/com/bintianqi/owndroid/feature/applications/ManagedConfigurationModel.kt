package com.bintianqi.owndroid.feature.applications

sealed class AppRestriction(
    open val key: String,
    open val title: String?,
    open val description: String?,
    open val path: List<String>,
    open val hidden: Boolean
) {
    abstract fun isNull(): Boolean

    data class IntItem(
        override val key: String,
        override val title: String?,
        override val description: String?,
        var value: Int?,
        override val path: List<String> = emptyList(),
        override val hidden: Boolean = false,
    ) : AppRestriction(key, title, description, path, hidden) {
        override fun isNull(): Boolean = value == null
    }

    data class StringItem(
        override val key: String,
        override val title: String?,
        override val description: String?,
        var value: String?,
        override val path: List<String> = emptyList(),
        override val hidden: Boolean = false
    ) : AppRestriction(key, title, description, path, hidden) {
        override fun isNull(): Boolean = value == null
    }

    data class BooleanItem(
        override val key: String,
        override val title: String?,
        override val description: String?,
        var value: Boolean?,
        override val path: List<String> = emptyList(),
        override val hidden: Boolean = false
    ) : AppRestriction(key, title, description, path, hidden) {
        override fun isNull(): Boolean = value == null
    }

    data class ChoiceItem(
        override val key: String,
        override val title: String?,
        override val description: String?,
        val entries: Array<String>,
        val entryValues: Array<String>,
        var value: String?,
        override val path: List<String> = emptyList(),
        override val hidden: Boolean = false
    ) : AppRestriction(key, title, description, path, hidden) {
        override fun isNull(): Boolean = value == null
    }

    data class MultiSelectItem(
        override val key: String,
        override val title: String?,
        override val description: String?,
        val entries: Array<String>,
        val entryValues: Array<String>,
        var value: Array<String>?,
        override val path: List<String> = emptyList(),
        override val hidden: Boolean = false
    ) : AppRestriction(key, title, description, path, hidden) {
        override fun isNull(): Boolean = value == null
    }
}

data class MultiSelectEntry(val value: String, val title: String?, val selected: Boolean)
