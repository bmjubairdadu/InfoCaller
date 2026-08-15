package com.infocaller.app.domain.model

data class CallLogEntry(
    val number: String,
    val name: String?,
    val type: Int, // CallLog.Calls.INCOMING_TYPE, etc.
    val date: Long,
    val duration: Long
)
