package com.infocaller.app.domain.repository

import com.infocaller.app.domain.model.CallLogEntry
import com.infocaller.app.domain.model.Contact
import com.infocaller.app.domain.model.SmsMessage
import kotlinx.coroutines.flow.Flow

interface DeviceDataRepository {
    fun getRecentCalls(): Flow<List<CallLogEntry>>
    fun getContacts(): Flow<List<Contact>>
    fun getMessages(): Flow<List<SmsMessage>>
    fun fetchRecentCallsSync(): List<CallLogEntry>
    fun fetchContactsSync(): List<Contact>
    fun fetchMessagesSync(): List<SmsMessage>
    suspend fun deleteCallLogEntry(number: String, date: Long)
    suspend fun clearCallLog()
    suspend fun deleteSms(id: Long)
}
