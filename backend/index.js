const express = require('express');
const axios = require('axios');
const NodeCache = require('node-cache');
const cors = require('cors');
require('dotenv').config();

const app = express();
const port = process.env.PORT || 3000;
const cache = new NodeCache({ stdTTL: 1800 }); // 30 mins default

app.use(cors());
app.use(express.json());

// CONFIGURATION
const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
const GITHUB_REPO = process.env.GITHUB_REPO || 'bmjubairdadu/InfoCaller-Provider-Registry';
const APIFY_TOKEN = process.env.APIFY_TOKEN;

// 1. PROVIDER REGISTRY RELAY
app.get('/api/v1/providers/manifest', async (req, res) => {
    const cacheKey = 'provider_manifest';
    const cachedData = cache.get(cacheKey);

    if (cachedData) {
        return res.json(cachedData);
    }

    try {
        const response = await axios.get(`https://api.github.com/repos/${GITHUB_REPO}/contents/manifest.json`, {
            headers: {
                'Authorization': `Bearer ${GITHUB_TOKEN}`,
                'Accept': 'application/vnd.github.v3+json'
            }
        });

        const content = Buffer.from(response.data.content, 'base64').toString('utf8');
        const manifest = JSON.parse(content);

        // VALIDATION
        if (!manifest.schemaVersion || !Array.isArray(manifest.providers)) {
            throw new Error('Invalid manifest structure');
        }

        cache.set(cacheKey, manifest);
        res.json(manifest);
    } catch (error) {
        console.error('Registry Error:', error.message);
        if (cachedData) return res.json(cachedData); // Fallback to stale cache
        res.status(502).json({ error: 'Failed to fetch provider manifest from upstream' });
    }
});

// 2. PHONE LOOKUP RELAY (APIFY)
app.post('/api/v1/lookup/phone', async (req, res) => {
    const { phoneNumber } = req.body;

    if (!phoneNumber) {
        return res.status(400).json({ error: 'phoneNumber is required' });
    }

    try {
        const apifyRequest = {
            concurrency: 10,
            fbProfilePic: true,
            includeAbout: true,
            includeCarrier: true,
            includeGoogle: true,
            includeLeaks: false,
            includeLookup: true,
            includeProfilePic: true,
            includeTelegram: true,
            numbers: [phoneNumber],
            onlyCache: false,
            preferCache: true
        };

        const response = await axios.post(
            `https://api.apify.com/v2/acts/eduair94~whatsapp-data-lookup/run-sync-get-dataset-items?token=${APIFY_TOKEN}`,
            apifyRequest
        );

        const item = response.data[0];
        if (!item) {
            return res.status(404).json({ error: 'No data found for this number' });
        }

        // TRANSFORM TO INFOCALLER FORMAT
        const normalizedResponse = {
            phoneNumber: item.number,
            publicName: item.lookup?.name || item.lookup?.displayName || null,
            profileImageUrl: item.urlImage || null,
            about: item.about || null,
            carrier: item.carrier || null,
            country: item.country || 'Bangladesh',
            region: item.region || null,
            whatsappStatus: item.exists ? 'CONFIRMED' : (item.source === 'not-found' ? 'NOT_FOUND' : 'UNKNOWN'),
            telegramStatus: item.telegram?.error ? 'UNKNOWN' : (item.telegram?.username ? 'CONFIRMED' : 'UNKNOWN'),
            googleResult: item.google?.success || null,
            isBusiness: item.isBusiness || false,
            source: item.source || 'fresh',
            confidence: item.exists ? 'HIGH' : 'MEDIUM',
            lastChecked: item.fetchedAt || new Date().toISOString()
        };

        res.json(normalizedResponse);
    } catch (error) {
        console.error('Lookup Error:', error.message);
        res.status(502).json({ error: 'Intelligence provider error' });
    }
});

app.listen(port, () => {
    console.log(`InfoCaller Backend listening at http://localhost:${port}`);
});
