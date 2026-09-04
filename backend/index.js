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

// Fields the registry may ever store. Anything else (local names, contact IDs,
// notes, photo URIs, messages, raw numbers, lookup keys) is dropped on write.
// Mirrors the client's ContributionPolicy allowlist plus public enrichment.
const ALLOWED_REGISTRY_FIELDS = new Set([
    'number', 'phoneNumber', 'publicName', 'alternateName', 'profileImageUrl',
    'about', 'city', 'country', 'region', 'timezone', 'email', 'carrier',
    'lineType', 'isBusiness', 'socialProfilesJson', 'whatsappStatus',
    'telegramStatus', 'googleResult', 'confidence', 'source', 'lastChecked'
]);

function mergeRecords(existing, incoming) {
    if (!existing) {
        const clean = {};
        for (const k of ALLOWED_REGISTRY_FIELDS) {
            if (incoming[k] !== null && incoming[k] !== undefined) clean[k] = incoming[k];
        }
        clean.updatedAt = Date.now();
        return clean;
    }

    // Privacy-safe merge: allowlist only (mirrors ContributionPolicy.ALLOWED_FIELDS
    // plus public caller-ID enrichment). Everything else is dropped, never merged.
    const result = { ...existing };

    for (const key in incoming) {
        if (!ALLOWED_REGISTRY_FIELDS.has(key)) continue;
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

// ─────────────────────────────────────────────────────────────
// OWNER-CONSENT MODEL (strict): contacts permission != publish consent.
// Only the number owner (OTP-verified) can publish their own profile.
// Bulk contact upload is intentionally NOT implemented.
// Supabase tables: owner_profiles, otp_verifications, owner_sessions,
// consent_audits, spam_reports (see supabase/owner_profiles.sql).
// ─────────────────────────────────────────────────────────────
const crypto = require('crypto');
const SUPABASE_URL = (process.env.SUPABASE_URL || '').replace(/\/+$/, '');
const SUPABASE_ANON_KEY = process.env.SUPABASE_ANON_KEY || '';
const SUPABASE_SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_SERVICE_KEY || '';
const ENCRYPTION_KEY_HEX = process.env.OWNER_ENCRYPTION_KEY || '';
const OWNER_DEV_OTP = process.env.OWNER_DEV_OTP || '';
const IS_PROD = (process.env.NODE_ENV || '').toLowerCase() === 'production';

function sha256Hex(s) { return crypto.createHash('sha256').update(String(s), 'utf8').digest('hex'); }
function normalizeE164(input) {
    if (!input) return null;
    let p = String(input).trim().replace(/[^\d+]/g, '');
    if (!p.startsWith('+')) p = '+' + p.replace(/[^0-9]/g, '');
    return isValidE164(p) ? p : null;
}
function maskE164(e164) {
    if (!e164 || e164.length < 6) return '***';
    return e164.slice(0, 5) + '******' + e164.slice(-2);
}
function getClientIp(req) {
    const fwd = req.headers['x-forwarded-for'];
    if (fwd) return String(fwd).split(',')[0].trim();
    return req.ip || 'unknown';
}
// Stricter per-endpoint limits (in-memory; use Redis for multi-instance).
const ownerHits = new Map(); // key -> {count, resetAt}
function ownerLimit(key, max, windowMs) {
    const now = Date.now();
    const e = ownerHits.get(key) || { count: 0, resetAt: now + windowMs };
    if (now > e.resetAt) { e.count = 0; e.resetAt = now + windowMs; }
    e.count++;
    ownerHits.set(key, e);
    return { allowed: e.count <= max, retryAfter: Math.ceil((e.resetAt - now) / 1000) };
}
function sbHeaders(service) {
    const key = service ? SUPABASE_SERVICE_KEY : SUPABASE_ANON_KEY;
    return { apikey: key, Authorization: `Bearer ${key}`, 'Content-Type': 'application/json', Accept: 'application/json' };
}
async function sbRest(path, method, body, service) {
    if (!SUPABASE_URL) throw new Error('SUPABASE_URL not configured');
    if (service && !SUPABASE_SERVICE_KEY) throw new Error('Service key not configured');
    if (!service && !SUPABASE_ANON_KEY) throw new Error('Anon key not configured');
    const r = await axios({
        method, url: `${SUPABASE_URL}/rest/v1/${path}`,
        headers: sbHeaders(service), data: body, timeout: 10000,
        validateStatus: () => true
    });
    if (r.status >= 200 && r.status < 300) return r.data;
    const err = new Error(`Supabase ${r.status}`);
    err.status = r.status; err.data = r.data;
    throw err;
}
async function sbAuth(path, body) {
    if (!SUPABASE_URL || !SUPABASE_ANON_KEY) throw new Error('Supabase Auth not configured');
    const r = await axios({
        method: 'post', url: `${SUPABASE_URL}/auth/v1/${path}`,
        headers: { apikey: SUPABASE_ANON_KEY, 'Content-Type': 'application/json' },
        data: body, timeout: 10000, validateStatus: () => true
    });
    if (r.status >= 200 && r.status < 300) return r.data;
    const err = new Error(`Auth ${r.status}`);
    err.status = r.status; err.data = r.data;
    throw err;
}
async function audit(phoneHash, action, detail, req) {
    try {
        await sbRest('consent_audits', 'post', {
            phone_hash: phoneHash, action, detail: String(detail || '').slice(0, 500),
            ip: getClientIp(req)
        }, true);
    } catch (e) { console.error('Audit failed:', e.message); }
}
function encKey() {
    if (!ENCRYPTION_KEY_HEX || !/^[0-9a-fA-F]{64}$/.test(ENCRYPTION_KEY_HEX)) return null;
    return Buffer.from(ENCRYPTION_KEY_HEX, 'hex');
}
function encryptE164(e164) {
    const key = encKey();
    if (!key) throw new Error('OWNER_ENCRYPTION_KEY not configured (64 hex chars)');
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const enc = Buffer.concat([cipher.update(e164, 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();
    return Buffer.concat([iv, tag, enc]).toString('base64');
}
function decryptE164(b64) {
    const key = encKey();
    if (!key) throw new Error('OWNER_ENCRYPTION_KEY not configured');
    const raw = Buffer.from(b64, 'base64');
    const iv = raw.subarray(0, 12), tag = raw.subarray(12, 28), enc = raw.subarray(28);
    const d = crypto.createDecipheriv('aes-256-gcm', key, iv);
    d.setAuthTag(tag);
    return Buffer.concat([d.update(enc), d.final()]).toString('utf8');
}
function newOwnerToken() {
    const raw = crypto.randomBytes(32).toString('hex');
    return { raw, hash: sha256Hex(raw) };
}
async function requireOwner(req, res, next) {
    try {
        const h = String(req.headers.authorization || '');
        const m = h.match(/^Bearer\s+(.+)$/i);
        if (!m) return res.status(401).json({ error: 'Owner token required' });
        const tokenHash = sha256Hex(m[1].trim());
        const rows = await sbRest(`owner_sessions?token_hash=eq.${tokenHash}&select=phone_hash,expires_at`, 'get', null, true);
        const s = Array.isArray(rows) ? rows[0] : null;
        if (!s) return res.status(401).json({ error: 'Invalid session' });
        if (new Date(s.expires_at).getTime() < Date.now()) return res.status(401).json({ error: 'Session expired' });
        req.ownerPhoneHash = s.phone_hash;
        next();
    } catch (e) { console.error('Session check failed:', e.message); res.status(503).json({ error: 'Auth service unavailable' }); }
}
function validProfileInput(b) {
    const errors = [];
    const name = String(b.displayName || '').trim();
    if (name.length < 2 || name.length > 80) errors.push('displayName must be 2-80 chars');
    if (b.photoUrl && !/^https:\/\/.{4,2000}$/.test(String(b.photoUrl))) errors.push('photoUrl must be https URL');
    if (b.visibility && !['public', 'unlisted', 'private'].includes(b.visibility)) errors.push('visibility must be public|unlisted|private');
    if (b.country && String(b.country).length > 80) errors.push('country too long');
    if (b.businessName && String(b.businessName).length > 120) errors.push('businessName too long');
    return { errors, name };
}

// POST /api/v1/owner/otp/request {phone}
app.post('/api/v1/owner/otp/request', async (req, res) => {
    const phone = normalizeE164(req.body && req.body.phone);
    if (!phone) return res.status(400).json({ error: 'phone must be E164' });
    const hash = sha256Hex(phone);
    const ip = getClientIp(req);
    const a = ownerLimit(`otp:req:phone:${hash}`, 5, 3600 * 1000);
    const b = ownerLimit(`otp:req:ip:${ip}`, 20, 3600 * 1000);
    if (!a.allowed || !b.allowed) { await audit(hash, 'request_otp', 'rate-limited', req); return res.status(429).json({ error: 'Too many OTP requests. Try later.' }); }
    try {
        try {
            await sbRest('otp_verifications', 'post', {
                phone_hash: hash, code_hash: 'supabase-managed',
                expires_at: new Date(Date.now() + 10 * 60 * 1000).toISOString()
            }, true);
        } catch (e) { console.error('otp track failed:', e.message); }
        // Dev fallback ONLY when explicitly enabled and NOT production.
        if (OWNER_DEV_OTP && !IS_PROD) {
            await audit(hash, 'request_otp', 'dev-mode sent', req);
            return res.json({ sent: true, ttl: 600, dev: true });
        }
        await sbAuth('otp', { phone });
        await audit(hash, 'request_otp', 'sent via Supabase SMS', req);
        res.json({ sent: true, ttl: 600 });
    } catch (e) {
        console.error('OTP request failed:', e.status || '', e.message);
        await audit(hash, 'request_otp', `failed: ${e.status || e.message}`, req);
        if (e.status === 422 || e.status === 400) return res.status(400).json({ error: 'Invalid phone number for SMS' });
        res.status(503).json({ error: 'SMS provider not configured. Enable phone auth in Supabase (Twilio) to receive OTP.' });
    }
});

// POST /api/v1/owner/otp/verify {phone, code}
app.post('/api/v1/owner/otp/verify', async (req, res) => {
    const phone = normalizeE164(req.body && req.body.phone);
    const code = String((req.body && req.body.code) || '').replace(/\D/g, '');
    if (!phone || code.length < 4 || code.length > 10) return res.status(400).json({ error: 'phone and code required' });
    const hash = sha256Hex(phone);
    const lim = ownerLimit(`otp:verify:${hash}`, 10, 3600 * 1000);
    if (!lim.allowed) { await audit(hash, 'verify_fail', 'rate-limited', req); return res.status(429).json({ error: 'Too many attempts. Try later.' }); }
    try {
        if (OWNER_DEV_OTP && !IS_PROD) {
            if (code !== String(OWNER_DEV_OTP)) { await audit(hash, 'verify_fail', 'dev code mismatch', req); return res.status(401).json({ error: 'Invalid code' }); }
        } else {
            try { await sbAuth('verify', { phone, token: code, type: 'sms' }); }
            catch (e) { await audit(hash, 'verify_fail', `auth ${e.status || e.message}`, req); return res.status(401).json({ error: 'Invalid or expired code' }); }
        }
        const t = newOwnerToken();
        const expires = new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString();
        await sbRest('owner_sessions', 'post', { token_hash: t.hash, phone_hash: hash, expires_at: expires }, true);
        await audit(hash, 'verify_ok', 'ownership verified', req);
        res.json({ ownerToken: t.raw, expiresAt: expires });
    } catch (e) {
        console.error('OTP verify failed:', e.message);
        res.status(503).json({ error: 'Verification service unavailable' });
    }
});

// GET /api/v1/owner/profile/me
app.get('/api/v1/owner/profile/me', requireOwner, async (req, res) => {
    try {
        const rows = await sbRest(`owner_profiles?phone_hash=eq.${req.ownerPhoneHash}&select=*`, 'get', null, true);
        const p = Array.isArray(rows) ? rows[0] : null;
        if (!p) return res.json({ profile: null });
        let phone = null;
        try { phone = decryptE164(p.phone_e164_enc); } catch (e) { phone = null; }
        res.json({ profile: { ...p, phone_e164_enc: undefined, phone } });
    } catch (e) { console.error('profile/me failed:', e.message); res.status(503).json({ error: 'Profile service unavailable' }); }
});

// POST /api/v1/owner/profile (create/claim own number)
app.post('/api/v1/owner/profile', requireOwner, async (req, res) => {
    const b = req.body || {};
    const { errors, name } = validProfileInput(b);
    if (errors.length) return res.status(400).json({ error: errors.join('; ') });
    if (b.consentGranted !== true) return res.status(400).json({ error: 'Explicit consent (consentGranted=true) is required' });
    try {
        const existing = await sbRest(`owner_profiles?phone_hash=eq.${req.ownerPhoneHash}&select=phone_hash`, 'get', null, true);
        if (Array.isArray(existing) && existing.length) return res.status(409).json({ error: 'Profile already exists. Use PATCH to update.' });
        // Owner proves number via session; recover E164 only if client sends it and hash matches.
        const claimedPhone = normalizeE164(b.phone);
        if (!claimedPhone || sha256Hex(claimedPhone) !== req.ownerPhoneHash) {
            return res.status(400).json({ error: 'phone must match the OTP-verified number' });
        }
        const now = new Date().toISOString();
        const row = {
            phone_hash: req.ownerPhoneHash,
            phone_e164_enc: encryptE164(claimedPhone),
            display_name: name,
            photo_url: b.photoUrl || null,
            business_name: b.businessName || null,
            business_category: b.businessCategory || null,
            country: b.country || null,
            is_business: b.isBusiness === true,
            visibility: b.visibility || 'public',
            consent_granted: true, consent_version: 1, consent_at: now,
            verified: true, verified_at: now,
            updated_at: now
        };
        await sbRest('owner_profiles', 'post', row, true);
        await audit(req.ownerPhoneHash, 'publish', `consent v1 visibility=${row.visibility}`, req);
        res.json({ success: true });
    } catch (e) { console.error('profile create failed:', e.status || '', e.message); res.status(503).json({ error: 'Profile service unavailable' }); }
});

// PATCH /api/v1/owner/profile (update / visibility / revoke)
app.patch('/api/v1/owner/profile', requireOwner, async (req, res) => {
    const b = req.body || {};
    try {
        const patch = {};
        if (b.displayName !== undefined) {
            const { errors, name } = validProfileInput({ displayName: b.displayName });
            if (errors.length) return res.status(400).json({ error: errors.join('; ') });
            patch.display_name = name;
        }
        if (b.photoUrl !== undefined) {
            if (b.photoUrl && !/^https:\/\/.{4,2000}$/.test(String(b.photoUrl))) return res.status(400).json({ error: 'photoUrl must be https URL' });
            patch.photo_url = b.photoUrl || null;
        }
        if (b.businessName !== undefined) patch.business_name = b.businessName || null;
        if (b.businessCategory !== undefined) patch.business_category = b.businessCategory || null;
        if (b.country !== undefined) patch.country = b.country || null;
        if (b.isBusiness !== undefined) patch.is_business = b.isBusiness === true;
        if (b.visibility !== undefined) {
            if (!['public', 'unlisted', 'private'].includes(b.visibility)) return res.status(400).json({ error: 'bad visibility' });
            patch.visibility = b.visibility;
            await audit(req.ownerPhoneHash, 'visibility', b.visibility, req);
        }
        if (b.consentGranted === false) {
            patch.consent_granted = false;
            patch.visibility = 'private';
            await audit(req.ownerPhoneHash, 'revoke', 'consent revoked', req);
        }
        if (!Object.keys(patch).length) return res.status(400).json({ error: 'Nothing to update' });
        patch.updated_at = new Date().toISOString();
        await sbRest(`owner_profiles?phone_hash=eq.${req.ownerPhoneHash}`, 'patch', patch, true);
        await audit(req.ownerPhoneHash, 'update', Object.keys(patch).join(','), req);
        res.json({ success: true });
    } catch (e) { console.error('profile update failed:', e.message); res.status(503).json({ error: 'Profile service unavailable' }); }
});

// DELETE /api/v1/owner/profile (delete + sessions)
app.delete('/api/v1/owner/profile', requireOwner, async (req, res) => {
    try {
        await sbRest(`owner_sessions?phone_hash=eq.${req.ownerPhoneHash}`, 'delete', null, true);
        await sbRest(`owner_profiles?phone_hash=eq.${req.ownerPhoneHash}`, 'delete', null, true);
        await audit(req.ownerPhoneHash, 'delete', 'profile deleted', req);
        res.json({ success: true });
    } catch (e) { console.error('profile delete failed:', e.message); res.status(503).json({ error: 'Profile service unavailable' }); }
});

// POST /api/v1/owner/spam-report {phone, reason}
app.post('/api/v1/owner/spam-report', async (req, res) => {
    const phone = normalizeE164(req.body && req.body.phone);
    const reason = String((req.body && req.body.reason) || 'spam');
    if (!phone) return res.status(400).json({ error: 'phone must be E164' });
    if (!['spam', 'scam', 'telemarketing', 'abuse', 'other'].includes(reason)) return res.status(400).json({ error: 'bad reason' });
    const lim = ownerLimit(`spam:${getClientIp(req)}`, 30, 3600 * 1000);
    if (!lim.allowed) return res.status(429).json({ error: 'Too many reports. Try later.' });
    try {
        const hash = sha256Hex(phone);
        let reporter = null;
        const h = String(req.headers.authorization || '');
        const m = h.match(/^Bearer\s+(.+)$/i);
        if (m) reporter = sha256Hex(m[1].trim()).slice(0, 32);
        await sbRest('spam_reports', 'post', { phone_hash: hash, reporter_hash: reporter, reason }, true);
        try {
            const rows = await sbRest(`owner_profiles?phone_hash=eq.${hash}&select=report_count,spam_score`, 'get', null, true);
            const cur = Array.isArray(rows) && rows[0] ? rows[0] : null;
            if (cur) {
                const rc = (cur.report_count || 0) + 1;
                await sbRest(`owner_profiles?phone_hash=eq.${hash}`, 'patch', {
                    report_count: rc, spam_score: Math.min(100, (cur.spam_score || 0) + 5), updated_at: new Date().toISOString()
                }, true);
            }
        } catch (e) { console.error('spam count update failed:', e.message); }
        res.json({ success: true });
    } catch (e) { console.error('spam report failed:', e.message); res.status(503).json({ error: 'Report service unavailable' }); }
});

// GET /api/v1/owner/lookup?phone=+...  (public, verified+consented+public only)
app.get('/api/v1/owner/lookup', async (req, res) => {
    const phone = normalizeE164(req.query.phone);
    if (!phone) return res.status(400).json({ error: 'phone must be E164' });
    const lim = ownerLimit(`lookup:${getClientIp(req)}`, 120, 60 * 1000);
    if (!lim.allowed) return res.status(429).json({ error: 'Rate limited. Try later.' });
    const hash = sha256Hex(phone);
    const cacheKey = `owner_lookup:${hash}`;
    const cached = cache.get(cacheKey);
    if (cached) return res.json(cached);
    try {
        const rows = await sbRest(
            `owner_profiles?phone_hash=eq.${hash}&verified=eq.true&consent_granted=eq.true&visibility=eq.public&select=display_name,photo_url,business_name,business_category,country,is_business,report_count,spam_score,updated_at`,
            'get', null, true
        );
        const p = Array.isArray(rows) ? rows[0] : null;
        if (!p) return res.status(404).json({ found: false });
        const out = {
            found: true,
            profile: {
                displayName: p.display_name,
                photoUrl: p.photo_url,
                businessName: p.business_name,
                businessCategory: p.business_category,
                country: p.country,
                isBusiness: p.is_business === true,
                reportCount: p.report_count || 0,
                spamScore: p.spam_score || 0,
                updatedAt: p.updated_at
            },
            confidence: (p.report_count || 0) > 5 ? 0.9 : 0.95,
            source: 'owner-verified'
        };
        cache.set(cacheKey, out, 600);
        res.json(out);
    } catch (e) { console.error('owner lookup failed:', e.message); res.status(503).json({ error: 'Lookup service unavailable' }); }
});

// 4. COMMUNITY CONTRIBUTE (consent-gated, privacy-safe).
// Client sends ONLY { phone_hash (64 hex), display_name? (2-80 chars public name) }.
// Plain phone numbers, local contact names, contact IDs, notes, photos are NEVER accepted.
// Server-side writes to Supabase community_lookups with service_role (never in APK).
// Optional GitHub mirror uses server-side GITHUB_TOKEN only.
app.post('/api/v1/community/contribute', async (req, res) => {
    const lim = ownerLimit(`contribute:${getClientIp(req)}`, 60, 3600 * 1000);
    if (!lim.allowed) return res.status(429).json({ error: 'Too many contributions. Try later.' });
    const b = req.body || {};
    const keys = Object.keys(b);
    const allowed = new Set(['phone_hash', 'display_name']);
    for (const k of keys) {
        if (!allowed.has(k)) return res.status(400).json({ error: `forbidden field: ${k}` });
    }
    const hash = String(b.phone_hash || '').toLowerCase().trim();
    if (!/^[0-9a-f]{64}$/.test(hash)) return res.status(400).json({ error: 'phone_hash must be 64 hex chars' });
    let name = b.display_name == null ? null : String(b.display_name).trim();
    if (name != null && (name.length < 2 || name.length > 80)) return res.status(400).json({ error: 'display_name must be 2-80 chars' });
    if (name != null) {
        const lower = name.toLowerCase();
        if (['unknown', 'unknown caller', 'unnamed contact'].includes(lower)) name = null;
        else if (name.replace(/[^0-9]/g, '').length >= 7 && name.replace(/[^A-Za-z]/g, '').length === 0) name = null;
    }
    try {
        const existing = await sbRest(`community_lookups?phone_hash=eq.${hash}&select=phone_hash,display_name,report_count`, 'get', null, true)
            .catch(() => []);
        const row = Array.isArray(existing) ? existing[0] : null;
        if (!row) {
            await sbRest('community_lookups', 'post', {
                phone_hash: hash, display_name: name, report_count: 0, updated_at: new Date().toISOString()
            }, true);
        } else if (name && name !== row.display_name) {
            await sbRest(`community_lookups?phone_hash=eq.${hash}`, 'patch', {
                display_name: name, updated_at: new Date().toISOString()
            }, true);
        } else {
            await sbRest(`community_lookups?phone_hash=eq.${hash}`, 'patch', {
                updated_at: new Date().toISOString()
            }, true).catch(() => {});
        }
        try { await audit(hash, 'publish', `community contribute name=${name ? 'yes' : 'no'}`, req); } catch (e) {}
        res.json({ success: true });
    } catch (e) {
        console.error('contribute failed:', e.message);
        res.status(503).json({ error: 'Contribution service unavailable' });
    }
});

// 5. PHONE LOOKUP RELAY - input validated, token never logged
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
