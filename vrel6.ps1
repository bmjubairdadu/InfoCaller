$env:Path += ';C:\Program Files\GitHub CLI'
cd d:\InfoCaller
gh release view v2.2.6 --json tagName,name,assets --jq '{tag: .tagName, title: .name, assets: [.assets[] | {name: .name, size: .size}]}'
Remove-Item do-rel226.ps1 -Force -ErrorAction SilentlyContinue
git add -A
git commit -m 'Remove release helper scripts from tracking' --allow-empty
git push origin main 2>&1 | Select-Object -Last 1
git status --short
Write-Host CLEAN
