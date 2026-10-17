package com.quickdns.switcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object DnsPreferences {
    private const val PREFS_NAME = "quick_dns_preferences"
    private const val KEY_CUSTOM_DNS = "key_custom_dns_list"
    private const val KEY_DELETED_PRESET_IDS = "key_deleted_preset_ids"
    private const val KEY_SELECTED_DNS_ID = "key_selected_dns_id"

    val DEFAULT_PRESETS = listOf(
        DnsItem("cf", "Cloudflare DNS", "1.1.1.1", "Сверхбыстрый и приватный (1.1.1.1)", hostname = "one.one.one.one"),
        DnsItem("adguard_dot", "AdGuard (DoT / DoQ)", "dns.adguard.com", "Фильтрация рекламы и трекеров (dns.adguard.com)", hostname = "dns.adguard.com", isDoT = true),
        DnsItem("geohide_dot", "Geohide DNS", "geohide.ru", "Обход блокировок и DoT/DoQ (geohide.ru)", hostname = "geohide.ru", isDoT = true),
        DnsItem("google", "Google DNS", "8.8.8.8", "Глобальная стабильность Anycast (8.8.8.8)", hostname = "dns.google"),
        DnsItem("quad9", "Quad9", "9.9.9.9", "Защита от вредоносных сайтов (9.9.9.9)", hostname = "dns.quad9.net")
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveSelectedDnsId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_DNS_ID, id).apply()
    }

    fun getSelectedDnsId(context: Context): String? {
        return getPrefs(context).getString(KEY_SELECTED_DNS_ID, null)
    }

    fun saveDeletedPresetIds(context: Context, ids: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_DELETED_PRESET_IDS, ids).apply()
    }

    fun getDeletedPresetIds(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_DELETED_PRESET_IDS, emptySet()) ?: emptySet()
    }

    fun getActivePresets(context: Context): List<DnsItem> {
        val deleted = getDeletedPresetIds(context)
        return DEFAULT_PRESETS.filter { it.id !in deleted }
    }

    fun restoreDefaultPresets(context: Context) {
        getPrefs(context).edit().remove(KEY_DELETED_PRESET_IDS).apply()
    }

    fun saveCustomDnsList(context: Context, list: List<DnsItem>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("target", item.target)
                put("description", item.description)
                put("hostname", item.hostname)
                put("isCustom", item.isCustom)
                put("isDoT", item.isDoT)
            }
            jsonArray.put(obj)
        }
        getPrefs(context).edit().putString(KEY_CUSTOM_DNS, jsonArray.toString()).apply()
    }

    fun getCustomDnsList(context: Context): List<DnsItem> {
        val raw = getPrefs(context).getString(KEY_CUSTOM_DNS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(raw)
            val result = mutableListOf<DnsItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(
                    DnsItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        target = obj.getString("target"),
                        description = obj.optString("description", ""),
                        hostname = if (obj.has("hostname") && !obj.isNull("hostname")) obj.getString("hostname") else null,
                        isCustom = obj.optBoolean("isCustom", true),
                        isDoT = obj.optBoolean("isDoT", false)
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }
}
