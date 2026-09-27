package com.circle.app.presentation.common

val categories = listOf("" to "All", "coffee" to "Coffee", "outdoors" to "Outdoors", "games" to "Games", "fitness" to "Fitness")

fun categoryLabel(id: String): String = categories.firstOrNull { it.first == id }?.second ?: id
