$env:Path += ';C:\Program Files\GitHub CLI'
cd d:\InfoCaller
Remove-Item build-v225.ps1 -Force -ErrorAction SilentlyContinue
git add -A
git commit -m 'Embed about-section links as tappable cards (no raw link text)'
git push origin main 2>&1 | Select-Object -Last 1
git tag -a v2.2.5 -m 'InfoCaller v2.2.5 - embedded about links'
git push origin v2.2.5
Copy-Item app\build\outputs\apk\debug\app-debug.apk InfoCaller-v2.2.5-debug.apk -Force
gh release create v2.2.5 InfoCaller-v2.2.5-debug.apk --title 'InfoCaller v2.2.5' --notes 'Clean debug APK built from main at v2.2.5.

Fix in this release:
- About section no longer shows raw link text: every URL inside scan details is now an embedded tappable card (icon plus label plus domain, tap opens, copy button copies); plain sentences stay as centered text' --latest
Remove-Item InfoCaller-v2.2.5-debug.apk -Force -ErrorAction SilentlyContinue
git status --short
Write-Host 'release done'
