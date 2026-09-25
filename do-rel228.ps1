$env:Path += ';C:\Program Files\GitHub CLI'
cd d:\InfoCaller
Remove-Item bf228.ps1 -Force -ErrorAction SilentlyContinue
git add -A
git commit -m 'Self-update system: GitHub release check, download, install (Settings plus launch banner)'
git push origin main 2>&1 | Select-Object -Last 1
git tag -a v2.2.8 -m 'InfoCaller v2.2.8 - working self-update from GitHub Releases'
git push origin v2.2.8
Copy-Item app\build\outputs\apk\debug\app-debug.apk InfoCaller-v2.2.8-debug.apk -Force
gh release create v2.2.8 InfoCaller-v2.2.8-debug.apk --title 'InfoCaller v2.2.8' --notes 'Clean debug APK built from main at v2.2.8.

New in this release:
- Auto-update now WORKS: the app checks GitHub Releases once a day at launch plus on demand from Settings About. New version shows a banner on the main screen and an entry in About - tap Download, watch progress, then the system installer opens. No more manual APK hunting.' --latest
Remove-Item InfoCaller-v2.2.8-debug.apk -Force -ErrorAction SilentlyContinue
git status --short
Write-Host 'release done'
