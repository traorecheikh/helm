package com.orvlabs.helm

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.text.NumberFormat
import java.util.Locale

object Str {
    lateinit var app: Context
}

fun tr(@StringRes id: Int, vararg args: Any?): String = Str.app.getString(id, *args)

fun trn(@PluralsRes id: Int, n: Int, vararg args: Any?): String = Str.app.resources.getQuantityString(id, n, n, *args)

val appLocale: Locale get() = Str.app.resources.configuration.locales[0]

val isFrench: Boolean get() = appLocale.language == "fr"

fun num(n: Number): String = NumberFormat.getInstance(appLocale).format(n)

fun countryName(code: String, fallback: String): String =
    Locale.Builder().setRegion(code.uppercase()).build().getDisplayCountry(appLocale).takeIf { it.isNotBlank() && !it.equals(code, true) } ?: fallback

fun continentName(en: String): String = when (en) {
    "Africa" -> tr(R.string.continent_africa)
    "Asia" -> tr(R.string.continent_asia)
    "Europe" -> tr(R.string.continent_europe)
    "North America" -> tr(R.string.continent_north_america)
    "South America" -> tr(R.string.continent_south_america)
    "Oceania" -> tr(R.string.continent_oceania)
    else -> en
}
