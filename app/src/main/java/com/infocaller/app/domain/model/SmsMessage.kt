package com.infocaller.app.domain.model

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // Telephony.Sms.MESSAGE_TYPE_INBOX, etc.
    val read: Int
)
