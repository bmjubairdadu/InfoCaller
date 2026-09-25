# Vercel-এ যা paste করবেন

বাকি সব setup হয়ে গেছে। আপনাকে শুধু Vercel-এর ২টা box-এ text বসাতে হবে।

---

## ⚠️ খুব গুরুত্বপূর্ণ: Project Name

Vercel-এ project import করার সময় **Project Name ঠিক এইভাবে দিন:**

```
infocaller-backend
```

নাম এটা হলেই URL ঠিক হবে `https://infocaller-backend.vercel.app` — যেটা
আমি আগে থেকেই app-এ বসিয়ে রেখেছি। অন্য নাম দিলে URL বদলে যাবে, তখন
`local.properties` আবার বদলাতে হবে।

---

## Import সেটিংস

| ঘর | মান |
|---|---|
| Framework Preset | **Other** |
| **Root Directory** | **`backend`** ← এটা না বদলালে কাজ করবে না |

---

## Environment Variables — ৪টা

| Key | Value |
|---|---|
| `INFOCALLER_API_KEY` | `pMuzljfGSmolxFFuVNt02qIIjh5HgvJenvSo2TK5Gbc` |
| `GITHUB_TOKEN` | আপনার বানানো token (ghp_ দিয়ে শুরু) |
| `GITHUB_REPO` | `bmjubairdadu/InfoCaller-Provider-Registry` |
| `NID_DATA_URL` | `https://api.github.com/repos/bmjubairdadu/InfoCaller-Provider-Registry/contents/database.json.gz` |

---

## যা আমি করে দিয়েছি — আপনার করার দরকার নেই

| কাজ | অবস্থা |
|---|---|
| `InfoCaller-Provider-Registry`-তে `database.json.gz` push | হয়ে গেছে, commit `40d86e9` |
| Repo-টা private কিনা verify | হ্যাঁ, anonymous call-এ 404 |
| Repo-তে `.gitignore` (`.env`/token block) | যোগ করা হয়েছে |
| `backend/.env` তৈরি | ৩টা var ready, শুধু `GITHUB_TOKEN` খালি |
| `local.properties`-এ URL বসানো | `https://infocaller-backend.vercel.app` |
| APK rebuild (URL bake হয়েছে) | হয়ে গেছে |

**GITHUB_TOKEN বাদ, বাকি সব variable আমি বসিয়ে দিয়েছি।**

---

## Deploy শেষ হলে যাচাই

নতুন PowerShell-এ:

```powershell
curl -H "x-api-key: pMuzljfGSmolxFFuVNt02qIIjh5HgvJenvSo2TK5Gbc" https://infocaller-backend.vercel.app/api/v1/nid/phone/01785917145
```

| ফল | মানে |
|---|---|
| `{ "number": ..., "nid": ..., "dob": ... }` | ✅ সব ঠিক |
| `404` | ঐ নম্বর ডেটায় নেই — এটাও ঠিক আছে |
| `503 NID database unavailable` | `GITHUB_TOKEN` ভুল, বা token-এর repo access নেই |
| `401` | `INFOCALLER_API_KEY` ভুল |
| `404 Cannot GET` | Root Directory ভুলে `backend` দেওয়া হয়নি |

**প্রথম request ১০-২০ সেকেন্ড লাগতে পারে** — ১.৭ MB ডাউনলোড করে index বানাতে হয়।
পরেরগুলো সাথে সাথে।

---

## GitHub token-এ যা থাকতে হবে

Token-এ **Contents → Read-only** access থাকতে হবে
(`InfoCaller-Provider-Registry` repo-তে)। বানানোর সময় Fine-grained token
নিলে repository-এর লিস্ট থেকে শুধু `InfoCaller-Provider-Registry` select করুন।

Classic token হলে `repo` checkbox দিলেই হবে।
