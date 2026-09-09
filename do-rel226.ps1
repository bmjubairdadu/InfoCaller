$env:Path += ';C:\Program Files\GitHub CLI'
cd d:\InfoCaller
Remove-Item build-v226.ps1 -Force -ErrorAction SilentlyContinue
git add -A
git commit -m 'Remove Deep OSINT Links tab - reverse-image is fully automatic inside scans'
git push origin main 2>&1 | Select-Object -Last 1
git tag -a v2.2.6 -m 'InfoCaller v2.2.6 - Deep OSINT tab removed, reverse-image automatic'
git push origin v2.2.6
Copy-Item app\build\outputs\apk\debug\app-debug.apk InfoCaller-v2.2.6-debug.apk -Force
gh release create v2.2.6 InfoCaller-v2.2.6-debug.apk --title 'InfoCaller v2.2.6' --notes 'Clean debug APK built from main at v2.2.6.

Change in this release:
- Deep OSINT Links tab removed from manual scan details: reverse-image search now runs automatically inside every scan (face-matched HD pass), so no manual links section is needed anymore' --latest
Remove-Item InfoCaller-v2.2.6-debug.apk -Force -ErrorAction SilentlyContinue
git status --short
Write-Host 'release done'
