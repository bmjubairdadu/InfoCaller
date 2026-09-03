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

// Security: rate-limit + helmet helpers (lightweight, no extra deps required for core fix)
const RATE_WINDOW_MS = 15 * 60 * 1000;
const RATE_MAX = 120;
const ipHits = new Map();
function rateLimit(req, res, next) {
    const now = Date.now();
    const ip = req.ip || req.headers['x-forwarded-for'] || 'global';
    const entry = ipHits.get(ip) || { count: 0, resetAt: now + RATE_WINDOW_MS };
    if (now > entry.resetAt) { entry.count = 0; entry.resetAt = now + RATE_WINDOW_MS; }
    entry.count++;
    ipHits.set(ip, entry);
    if (entry.count > RATE_MAX) return res.status(429).json({ error: 'Rate limited. Try later.', retryAfter: Math.ceil((entry.resetAt-now)/1000) });
    next();
}
app.use(rateLimit);
app.use((req, res, next) => {
    res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('X-Frame-Options','DENY');
    res.setHeader('Referrer-Policy','no-referrer');
    next();
});

// Phone validation
function isValidE164(phone){ return /^\+[1-9]\d{7,14}$/.test(phone); }

// Auth Middleware - FIXED: fail-closed when API_KEY not configured
const authenticate = (req, res, next) => {
    if (!API_KEY) {
        console.error('SECURITY: INFOCALLER_API_KEY not set - rejecting authenticated route');
        return res.status(500).json({ error: 'Server misconfigured' });
    }
    const authHeader = req.headers['x-api-key'];
    if (authHeader === API_KEY) return next();
    return res.status(401).json({ error: 'Unauthorized' });
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
    let number = req.params.number;
    // normalize to E164 before path
    if (number && !number.startsWith('+')) number = '+' + number.replace(/[^0-9]/g,'');
    if (number && !isValidE164(number)) return res.status(400).json({ error: 'Invalid E164 number' });
    const cleanNumber = number.replace(/\+/g, '');
    const path = `registry/numbers/${cleanNumber}.json`;

    try {
        const response = await axios.get(`https://api.github.com/repos/${GITHUB_REPO}/contents/${path}`, {
            headers: {
                'Authorization': `Bearer ${GITHUB_TOKEN}`,
                'Accept': 'application/vnd.github.v3+json'
            },
            timeout: 8000
        });
        const content = Buffer.from(response.data.content, 'base64').toString('utf8');
        const record = JSON.parse(content);
        res.json(record);
    } catch (error) {
        if (error.response && error.response.status === 404) return res.status(404).json({ error: 'Not found in shared registry' });
        if (error.response && error.response.status === 403) return res.status(429).json({ error: 'Registry rate-limited, retry later' });
        console.error('Registry lookup error:', error.response?.status, error.message);
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

// 4. PHONE LOOKUP RELAY - input validated, token never logged
app.post('/api/v1/lookup/phone', authenticate, async (req, res) => {
    const { phoneNumber } = req.body;
    if (!phoneNumber) return res.status(400).json({ error: 'phoneNumber is required' });
    if (!isValidE164(String(phoneNumber))) return res.status(400).json({ error: 'phoneNumber must be E164 (+[1-9]...) '}); 
    if (!APIFY_TOKEN) return res.status(500).json({ error: 'Apify not configured' });

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
