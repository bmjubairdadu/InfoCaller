const express = require('express');
const axios = require('axios');
const NodeCache = require('node-cache');
const cors = require('cors');
require('dotenv').config();

const app = express();
const port = process.env.PORT || 3000;
const cache = new NodeCache({ stdTTL: 1800 });
app.use(cors());
app.use(express.json({ limit: '32kb' }));

const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
const GITHUB_REPO = process.env.GITHUB_REPO || 'bmjubairdadu/InfoCaller-Provider-Registry';
const API_KEY = process.env.INFOCALLER_API_KEY;
const RATE_WINDOW_MS = 15 * 60 * 1000;
const RATE_MAX = 120;
const ipHits = new Map();

function rateLimit(req, res, next) {
  const now = Date.now(); const ip = req.ip || 'global';
  const entry = ipHits.get(ip) || { count: 0, resetAt: now + RATE_WINDOW_MS };
  if (now > entry.resetAt) { entry.count = 0; entry.resetAt = now + RATE_WINDOW_MS; }
  entry.count++; ipHits.set(ip, entry);
  if (entry.count > RATE_MAX) return res.status(429).json({ error: 'Rate limited' });
  next();
}
app.use(rateLimit);
app.use((req,res,next)=>{ res.setHeader('X-Content-Type-Options','nosniff'); res.setHeader('X-Frame-Options','DENY'); res.setHeader('Referrer-Policy','no-referrer'); next(); });

function isValidE164(phone) { return /^\+[1-9]\d{7,14}$/.test(phone); }
function authenticate(req,res,next) {
  if (!API_KEY) return res.status(500).json({ error:'Server misconfigured' });
  if (req.headers['x-api-key'] !== API_KEY) return res.status(401).json({ error:'Unauthorized' });
  next();
}

function safeRegistryRecord(input) {
  if (!input || typeof input !== 'object') throw new Error('Invalid record');
  const allowed = ['phoneHash','normalizedPhone','displayName','country','carrier','photoUrl','source','confidence','updatedAt','expiresAt'];
  const out = {};
  for (const key of allowed) if (input[key] !== undefined && input[key] !== null) out[key] = input[key];
  if (out.normalizedPhone && !isValidE164(out.normalizedPhone)) throw new Error('normalizedPhone must be E.164');
  return out;
}

// Read-only shard relay. GitHub credential stays server-side.
app.get('/api/v1/registry/shard', async (req,res) => {
  const shard = String(req.query.path || '');
  if (!/^database\/[1-9]\d{0,2}\/[0-9]{3}\.json$/.test(shard)) return res.status(400).json({error:'Invalid shard'});
  try {
    const r = await axios.get(`https://api.github.com/repos/${GITHUB_REPO}/contents/${shard}`, {
      headers: { Authorization:`Bearer ${GITHUB_TOKEN}`, Accept:'application/vnd.github.raw+json' },
      validateStatus: s => s < 500, timeout: 8000
    });
    if (r.status === 404) return res.status(404).json({error:'Shard not found'});
    if (r.status >= 400) return res.status(502).json({error:'Registry unavailable'});
    let payload = typeof r.data === 'string' ? r.data : JSON.stringify(r.data);
    const parsed = JSON.parse(payload);
    if (!parsed || !Array.isArray(parsed.records)) return res.status(502).json({error:'Corrupted registry shard'});
    res.setHeader('ETag', r.headers.etag || '');
    if (r.headers['last-modified']) res.setHeader('Last-Modified', r.headers['last-modified']);
    res.setHeader('Cache-Control','public, max-age=300');
    return res.json(parsed);
  } catch (e) { console.error('Shard relay:', e.message); return res.status(502).json({error:'Registry lookup failed'}); }
});

// Secure write path: only authorized server callers; never exposed to the Android APK.
app.post('/api/v1/registry/publish', authenticate, async (req,res) => {
  try {
    const record = safeRegistryRecord(req.body);
    if (!record.normalizedPhone) return res.status(400).json({error:'normalizedPhone is required'});
    const digits = record.normalizedPhone.slice(1);
    const cc = record.normalizedPhone.startsWith('+880') ? '880' : digits.slice(0, Math.min(3,digits.length));
    const prefix = digits.slice(-10,-7).padStart(3,'0');
    const path = `database/${cc}/${prefix}.json`;
    const headers = { Authorization:`Bearer ${GITHUB_TOKEN}`, Accept:'application/vnd.github+json' };
    let existingSha = null, shard = { version:'1', records:[] };
    try {
      const old = await axios.get(`https://api.github.com/repos/${GITHUB_REPO}/contents/${path}`, {headers, timeout:8000});
      existingSha = old.data.sha;
      shard = JSON.parse(Buffer.from(old.data.content,'base64').toString('utf8'));
      if (!Array.isArray(shard.records)) throw new Error('Corrupt shard');
    } catch (e) { if (e.response?.status !== 404) throw e; }
    const idx = shard.records.findIndex(x => x.normalizedPhone === record.normalizedPhone || x.phoneHash === record.phoneHash);
    if (idx >= 0) shard.records[idx] = {...shard.records[idx], ...record}; else shard.records.push(record);
    shard.version = String(Date.now());
    const body = {message:`Update authorized registry shard ${path}`, content:Buffer.from(JSON.stringify(shard,null,2)).toString('base64')};
    if (existingSha) body.sha = existingSha;
    await axios.put(`https://api.github.com/repos/${GITHUB_REPO}/contents/${path}`, body, {headers, timeout:10000});
    return res.json({success:true, path});
  } catch (e) { console.error('Publish:', e.message); return res.status(502).json({error:'Publish failed'}); }
});

app.listen(port, () => console.log(`InfoCaller Backend listening at http://localhost:${port}`));
