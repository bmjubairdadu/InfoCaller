package com.infocaller.app.data.local.dao

import com.infocaller.app.data.local.entity.ContactEnrichmentEntity

suspend fun EnrichmentDao.getEnrichmentCompat(number: String): ContactEnrichmentEntity? =
    getEnrichmentSync(number)
