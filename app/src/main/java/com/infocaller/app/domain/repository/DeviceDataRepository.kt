package com.infocaller.app.domain.repository

import com.infocaller.app.domain.model.CallLogEntry
import com.infocaller.app.domain.model.Contact
import kotlinx.coroutines.flow.Flow

interface DeviceDataRepository {
    fun getRecentCalls(): Flow<List<CallLogEntry>>
    fun getContacts(): Flow<List<Contact>>
    fun fetchRecentCallsSync(): List<CallLogEntry>
    fun fetchContactsSync(): List<Contact>
}
