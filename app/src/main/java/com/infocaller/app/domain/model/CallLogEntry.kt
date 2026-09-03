package com.infocaller.app.domain.model

data class CallLogEntry(
    val number: String,
    val name: String?,
    val type: Int, 
    val date: Long,
    val duration: Long,
    val subscriptionId: String? = null
)
