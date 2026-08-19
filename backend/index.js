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
const API_KEY = process.env.INFOCALLER_API_KEY; // Secured endpoint
const APIFY_TOKEN = process.env.APIFY_TOKEN;

// Auth Middleware
const authenticate = (req, res, next) => {
    const authHeader = req.headers['x-api-key'];
    if (!API_KEY || authHeader === API_KEY) {
        next();
    } else {
        res.status(401).json({ error: 'Unauthorized' });
    }
};

// 1. PROVIDER REGISTRY RELAY
app.get('/api/v1/providers/manifest', async (req, res) => {
    const cacheKey = 'provider_manifest';
    const cachedData = cache.get(cacheKey);

    if (cachedData) return res.json(cachedData);

    try {
        const response = await axios.get(`https://api.github.com/repos/${GITHUB_REPO}/contents/manifest.json`, {
            headers: {
                'Authorization': `Bearer ${GITHUB_TOKEN}`,
                'Accept': 'application/vnd.github.v3+json'
            }
        });

        const content = Buffer.from(response.data.content, 'base64').toString('utf8');
        const manifest = JSON.parse(content);
        cache.set(cacheKey, manifest);
        res.json(manifest);
    } catch (error) {
        console.error('Registry Error:', error.message);
        res.status(502).json({ error: 'Failed to fetch provider manifest' });
    }
});

// 2. SHARED REGISTRY LOOKUP
app.get('/api/v1/registry/lookup/:number', async (req, res) => {
    const number = req.params.number;
    const cleanNumber = number.replace(/\+/g, '');
    const path = `registry/numbers/${cleanNumber}.json`;

    try {
        const response = await axios.get(`https://api.github.com/repos/${GITHUB_REPO}/contents/${path}`, {
            headers: {
                'Authorization': `Bearer ${GITHUB_TOKEN}`,
                'Accept': 'application/vnd.github.v3+json'
            }
        });

        const content = Buffer.from(response.data.content, 'base64').toString('utf8');
        const record = JSON.parse(content);
        res.json(record);
    } catch (error) {
        if (error.response && error.response.status === 404) {
            return res.status(404).json({ error: 'Not found in shared registry' });
        }
        res.status(502).json({ error: 'Registry lookup failed' });
    }
});

// 3. SHARED REGISTRY PUBLISH (Secured)
app.post('/api/v1/registry/publish', authenticate, async (req, res) => {
    const record = req.body;
    const number = record.number || record.phoneNumber;
    if (!number) return res.status(400).json({ error: 'number is required' });

    const cleanNumber = number.replace(/\+/g, '');
    const path = `registry/numbers/${cleanNumber}.json`;

    try {
        let existingSha = null;
        let existingContent = null;

        try {
            const getRes = await axios.get(`https://api.github.com/repos/${GITHUB_REPO}/contents/${path}`, {
                headers: { 'Authorization': `Bearer ${GITHUB_TOKEN}` }
            });
            existingSha = getRes.data.sha;
            existingContent = JSON.parse(Buffer.from(getRes.data.content, 'base64').toString('utf8'));
        } catch (e) {}

        const mergedRecord = mergeRecords(existingContent, record);

        await axios.put(`https://api.github.com/repos/${GITHUB_REPO}/contents/${path}`, {
            message: `Update registry for ${number}`,
            content: Buffer.from(JSON.stringify(mergedRecord, null, 2)).toString('base64'),
            sha: existingSha
        }, {
            headers: { 'Authorization': `Bearer ${GITHUB_TOKEN}` }
        });

        res.json({ success: true, record: mergedRecord });
    } catch (error) {
        console.error('Publish Error:', error.message);
        res.status(502).json({ error: 'Failed to publish to registry' });
    }
});

function mergeRecords(existing, incoming) {
    if (!existing) return { ...incoming, updatedAt: Date.now() };

    // Privacy Safe Merge: Skip sensitive fields
    const blacklist = ['localName', 'contactId', 'privateNote'];
    const result = { ...existing };

    for (const key in incoming) {
        if (blacklist.includes(key)) continue;
        if (incoming[key] !== null && incoming[key] !== undefined) {
            // Priority Logic: Prefer "HIGH" confidence over "MEDIUM"
            if (incoming.confidence === 'HIGH' || !existing[key]) {
                result[key] = incoming[key];
            }
        }
    }

    result.updatedAt = Date.now();
    return result;
}

// 4. PHONE LOOKUP RELAY
app.post('/api/v1/lookup/phone', authenticate, async (req, res) => {
    const { phoneNumber } = req.body;
    if (!phoneNumber) return res.status(400).json({ error: 'phoneNumber is required' });

    try {
        const apifyRequest = {
            numbers: [phoneNumber],
            concurrency: 10,
            fbProfilePic: true,
            includeAbout: true,
            includeCarrier: true,
            includeGoogle: true,
            includeLookup: true,
            includeProfilePic: true,
            includeTelegram: true
        };

        const response = await axios.post(
            `https://api.apify.com/v2/acts/eduair94~whatsapp-data-lookup/run-sync-get-dataset-items?token=${APIFY_TOKEN}`,
            apifyRequest
        );

        const item = response.data[0];
        if (!item) return res.status(404).json({ error: 'No data found' });

        const normalizedResponse = {
            phoneNumber: item.number,
            publicName: item.lookup?.name || item.lookup?.displayName || null,
            profileImageUrl: item.urlImage || item.lookup?.profilePicture || null,
            about: item.about || item.lookup?.about || item.description || null,
            carrier: item.carrier || item.lookup?.carrier || null,
            country: item.country || 'Bangladesh',
            region: item.region || null,
            whatsappStatus: item.exists ? 'CONFIRMED' : 'UNKNOWN',
            telegramStatus: item.telegram?.username ? 'CONFIRMED' : 'UNKNOWN',
            googleResult: item.google?.success || null,
            isBusiness: item.isBusiness || false,
            source: 'whatsapp_intel',
            confidence: item.exists ? 'HIGH' : 'MEDIUM',
            lastChecked: Date.now()
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
