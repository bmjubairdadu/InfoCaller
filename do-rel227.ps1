$env:Path += ';C:\Program Files\GitHub CLI'
cd d:\InfoCaller
Remove-Item build-v227.ps1, b227.ps1, bf227.ps1, chk-apk.ps1, build-v225.ps1, build-v226.ps1 -Force -ErrorAction SilentlyContinue
git add -A
git commit -m 'Auto NID smart-card status (ML Kit OCR captcha solver) - manual NID Portal screen removed'
git push origin main 2>&1 | Select-Object -Last 1
git tag -a v2.2.7 -m 'InfoCaller v2.2.7 - auto NID smart-card status with OCR captcha solver'
git push origin v2.2.7
Copy-Item app\build\outputs\apk\debug\app-debug.apk InfoCaller-v2.2.7-debug.apk -Force
gh release create v2.2.7 InfoCaller-v2.2.7-debug.apk --title 'InfoCaller v2.2.7' --notes 'Clean debug APK built from main at v2.2.7.

Changes in this release:
- NID Portal manual screen REMOVED from Settings - no more typing captcha by hand
- Automatic smart-card status: number matched with database.json gives NID plus DOB instantly (no captcha, no network), then the app itself fetches the captcha image, solves it on-device with ML Kit OCR, and shows the live smart-card status automatically
- OCR retry: up to 3 fresh captchas per scan, silent fallback to NID plus DOB when unsolvable' --latest
Remove-Item InfoCaller-v2.2.7-debug.apk -Force -ErrorAction SilentlyContinue
git status --short
Write-Host 'release done'
