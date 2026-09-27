package com.circle.app.domain.model

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
data class Appearance(val mode: ThemeMode = ThemeMode.SYSTEM, val dynamicColors: Boolean = true)
