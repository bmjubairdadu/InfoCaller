# Backend চালু করার পুরো গাইড (একবার করলেই হবে)

## যা আমি আগে থেকেই করে দিয়েছি

| জিনিস | অবস্থা |
|---|---|
| API key বানানো | `pMuzljfGSmolxFFuVNt02qIIjh5HgvJenvSo2TK5Gbc` |
| `local.properties`-এ key বসানো | হয়ে গেছে |
| `backend/.env`-এ key বসানো | হয়ে গেছে |
| NID data ফোল্ডার + git commit | হয়ে গেছে — `D:\InfoCaller-PrivateData` |

**আপনাকে শুধু ৩টা জিনিস করতে হবে।** মোট ১০ মিনিট।

---

## ধাপ ১ — Private repo বানান (২ মিনিট)

১. https://github.com/new খুলুন
২. Owner: `bmjubairdadu`
৩. Repository name: `InfoCaller-PrivateData`
৪. **Public না বেছে নিতে বেছে নিন** — ⚠️ **Private** সিলেক্ট করুন
৫. "Add a README file" চেক **করবেন না** (আমাদের ফোল্ডারে আছে)
৬. **Create repository**

> ⚠️ Private না হলে ১১৫ হাজার মানুষের NID সবার চোখে পড়ে যাবে। Create করার পরে
> সাথে সাথে দেখে নিন উপরে "Private" লেখা আছে কিনা।

---

## ধাপ ২ — Data push করুন (১ মিনিট)

PowerShell-এ এই ২টা লাইন চালান:

```powershell
cd D:\InfoCaller-PrivateData
git push -u origin main
```

Credential Manager pop করলে — **Username**: `bmjubairdadu`, **Password**: GitHub-এর
Personal Access Token (ধাপ ৩-এ যা বানাবেন)। সাধারণ GitHub password কাজ করবে না।

---

## ধাপ ৩ — GitHub Token বানান (৩ মিনিট)

১. এই ঠিকানায় যান: https://github.com/settings/tokens/new
২. GitHub-এর password দিন
৩. উপরে **Note** ঘরে লিখুন: `InfoCaller backend`
৪. **Expiration** → `90 days`
৫. **Permissions → Repository permissions** খুলুন, খুঁজুন **Contents** →
   Access: **Read-only** করুন
   - বাকি সব permission **No access** থাকবে
6. নিচে scroll করে **Generate token** চাপুন
7. যে লাল-সাদা string দেখাবে সেটা **অবশ্যই কপি করুন** — একবারই দেখায়!

> এই token-টা `INFOCALLER_API_KEY`-এর সমান গুরুত্বপূর্ণ। কাউকে দেবেন না।

---

## ধাপ ৪ — Vercel-এ backend deploy (৩ মিনিট)

১. https://vercel.com → GitHub দিয়ে sign up (free)
২. **Add New → Project** → `bmjubairdadu/InfoCaller` খুঁজে **Import**
৩. Import screen-এ:
   - **Framework Preset**: `Other`
   - **Root Directory**: `backend` ← এটা বদলাতেই হবে
4. **Environment Variables** section খুলুন, ৪টা যোগ করুন:

| Key | Value |
|---|---|
| `INFOCALLER_API_KEY` | `pMuzljfGSmolxFFuVNt02qIIjh5HgvJenvSo2TK5Gbc` |
| `GITHUB_TOKEN` | ধাপ ৩-এ কপি করা token |
| `GITHUB_REPO` | `bmjubairdadu/InfoCaller-PrivateData` |
| `NID_DATA_URL` | `https://api.github.com/repos/bmjubairdadu/InfoCaller-PrivateData/contents/database.json.gz` |

5. **Deploy** চাপুন

Vercel বলবে `backend/index.js`-এ `app.listen` নেই, serverless function দরকার।
এক্ষেত্রে বলবে — আমাকে বলুন, আমি `backend/vercel.json` + `api/index.js` বানিয়ে
দেব।

---

## ধাপ ৫ — App-এ URL বসান (১ মিনিট)

Deploy শেষ হলে Vercel একটা URL দেবে, যেমন
`https://infocaller-backend.vercel.app`

`D:\InfoCaller\local.properties` খুলুন, লাইনটা বদলান:

```properties
backend.base.url=https://infocaller-backend.vercel.app
```

---

## ধাপ ৬ — Rebuild (৩ মিনিট)

```powershell
cd D:\InfoCaller
.\gradlew assembleDebug
```

APK তৈরি হবে `app\build\outputs\apk\debug\app-debug.apk`।

---

## যাচাই করা

```powershell
curl -H "x-api-key: pMuzljfGSmolxFFuVNt02qIIjh5HgvJenvSo2TK5Gbc" https://YOUR-URL/api/v1/nid/phone/01785917145
```

- Record ফিরলে ✅ কাজ করছে
- `404` মানে ঐ নম্বর ডেটায় নেই (এটাও ঠিক আছে)
- `503 NID database unavailable` মানে `GITHUB_TOKEN` বা `NID_DATA_URL` ভুল

---

## প্রতিটা variable কী করে

| Variable | কী জিনিস | উদাহরণ |
|---|---|---|
| `INFOCALLER_API_KEY` | দরজার পাসওয়ার্ড। app আর backend দুটোই এটা জানে, না জানলে কেউ ঢুকতে পারবে না | `pMuzlj...` |
| `GITHUB_TOKEN` | আপনার GitHub-এর পরিচয়পত্র, যাতে backend private repo পড়তে পারে | `ghp_...` |
| `GITHUB_REPO` | NID data কোন repo-তে আছে | `bmjubairdadu/InfoCaller-PrivateData` |
| `NID_DATA_URL` | ঐ repo-তে ফাইলটা কোথায় | `https://api.github.com/repos/.../contents/database.json.gz` |
| `backend.base.url` | (app-এর লোকাল.properties-এ) backend-এর ঠিকানা | `https://...vercel.app` |

---

## যদি কোথাও আটকে যান

কোন ধাপে, কী error এল — সেটা লিখে দিন, আমি ঠিক করে দেব।
