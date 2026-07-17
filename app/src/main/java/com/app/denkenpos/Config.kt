package com.app.denkenpos

import android.content.Context
import androidx.compose.ui.util.unpackInt1
import androidx.core.content.edit

// urls
const val DEFAULT_VALUES_SHEET_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vTCvdEKNc1PpZHe2WfXpqJtnYiDOtPo5P1diRY2fnn2QJMppQj2Ui99OalzX-CJQqTC7DjiIrFGDIH_/pub?gid=1590247624&single=true&output=csv"
const val DEFAULT_STAFF_SHEET_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vTCvdEKNc1PpZHe2WfXpqJtnYiDOtPo5P1diRY2fnn2QJMppQj2Ui99OalzX-CJQqTC7DjiIrFGDIH_/pub?gid=332829102&single=true&output=csv"
const val DEFAULT_LOCATION_SHEET_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vTCvdEKNc1PpZHe2WfXpqJtnYiDOtPo5P1diRY2fnn2QJMppQj2Ui99OalzX-CJQqTC7DjiIrFGDIH_/pub?gid=892986798&single=true&output=csv"

// admin id
const val DEFAULT_ADMIN_ID: Int = -1

fun getAdminId(context: Context): Int {
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    return prefs.getInt("admin_id", DEFAULT_ADMIN_ID)
}

fun setAdminId(context: Context, adminId: Int) {
    context.getSharedPreferences("config", Context.MODE_PRIVATE)
        .edit{
            putInt("admin_id", adminId)
        }
}

// timeout_minutes
const val DEFAULT_TIMEOUT_MINUTES: Long = 15

fun getTimeoutMinutes(context: Context): Long {
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    return prefs.getLong("timeout_minutes", DEFAULT_TIMEOUT_MINUTES)
}

fun setTimeoutMinutes(context: Context, timeoutMinutes: Long) {
    context.getSharedPreferences("config", Context.MODE_PRIVATE)
        .edit{
            putLong("timeout_minutes", timeoutMinutes)
        }
}

// staff_sheet_url

fun getStaffSheetUrl(context: Context): String {
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    return prefs.getString("staff_sheet_url", DEFAULT_STAFF_SHEET_URL)!!
}

fun setStaffSheetUrl(context: Context, url: String) {
    context.getSharedPreferences("config", Context.MODE_PRIVATE)
        .edit{
            putString("staff_sheet_url", url)
        }
}

// staff_sheet_url
const val DEFAULT_STAFF_SHEET_MAX_TRIES: Int = 3

fun getStaffSheetMaxTries(context: Context): Int {
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    return prefs.getInt("staff_sheet_url_max_tries", DEFAULT_STAFF_SHEET_MAX_TRIES)!!
}

fun setStaffSheetMaxTries(context: Context, attempts: Int) {
    context.getSharedPreferences("config", Context.MODE_PRIVATE)
        .edit{
            putInt("staff_sheet_url_max_tries", attempts)
        }
}

// sales_log_url
const val DEFAULT_SALES_LOG_URL = "https://script.google.com/macros/s/AKfycbwkhV3HPu756UCYJZaNSzDpdB3q0OG9MzXDE9LgMCFSCZNqTO5DSQTa5RLEOnfBSKx9bA/exec" // your /exec deployment URL

fun getSalesLogUrl(context: Context): String {
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    return prefs.getString("sales_log_url", DEFAULT_SALES_LOG_URL)!!
}

fun setSalesLogUrl(context: Context, url: String) {
    context.getSharedPreferences("config", Context.MODE_PRIVATE)
        .edit { putString("sales_log_url", url) }
}

// sales_log_secret
fun getSalesLogSecret(context: Context): String {
    val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    return prefs.getString("sales_log_secret", BuildConfig.SALES_LOG_SECRET)!!
}

fun setSalesLogSecret(context: Context, secret: String) {
    context.getSharedPreferences("config", Context.MODE_PRIVATE)
        .edit { putString("sales_log_secret", secret) }
}