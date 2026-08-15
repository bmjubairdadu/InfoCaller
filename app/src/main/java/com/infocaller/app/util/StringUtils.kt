package com.infocaller.app.util

fun String?.ifNullOrBlank(default: () -> String): String {
    val value = this
    return if (value.isNullOrBlank()) default() else value
}