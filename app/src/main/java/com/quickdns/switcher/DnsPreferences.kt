package com.quickdns.switcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object DnsPreferences {
    private const val PREFS_NAME = "quick_dns_preferences"
    private const val KEY_CUSTOM_DNS = "key_custom_dns_list"
    private const val KEY_SELECTED_DNS_ID = "key_selected_dns_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveSelectedDnsId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_DNS_ID, id).apply()
    }

    fun getSelectedDnsId(context: Context): String? {
        return getPrefs(context).getString(KEY_SELECTED_DNS_ID, null)
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
