# InfoCaller Owner Backend

Strict owner-consent model. No bulk contact upload endpoint exists.

## Env

```
PORT=3000
INFOCALLER_API_KEY=...
SUPABASE_URL=https://xyz.supabase.co
SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_ROLE_KEY=...   # server only, never ship in APK
OWNER_ENCRYPTION_KEY=<64 hex chars>
OWNER_DEV_OTP=123456            # dev only, never in production
NODE_ENV=development
```

Supabase phone auth (Twilio) must be enabled for real SMS OTP.

## Run

```
cd backend
npm install
npm start
```

## Endpoints

- `POST /api/v1/owner/otp/request` `{phone}`
- `POST /api/v1/owner/otp/verify` `{phone, code}` -> `{ownerToken, expiresAt}`
- `GET /api/v1/owner/profile/me` (Bearer ownerToken)
- `POST /api/v1/owner/profile` (Bearer) `{phone, displayName, photoUrl?, businessName?, businessCategory?, country?, isBusiness?, visibility?, consentGranted:true}`
- `PATCH /api/v1/owner/profile` (Bearer)
- `DELETE /api/v1/owner/profile` (Bearer)
- `POST /api/v1/owner/spam-report` `{phone, reason}`
- `GET /api/v1/owner/lookup?phone=+880...`
- `POST /api/v1/community/contribute` `{phone_hash, display_name?}` — consent-gated community contribution. Accepts ONLY `phone_hash` (64 hex) + optional public `display_name` (2-80 chars). Rejects all other fields (local names, contact IDs, notes, numbers). Server-side Supabase write; no write tokens in APK.

Lookup returns only verified + consented + public owner profiles.
