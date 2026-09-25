$env:JAVA_HOME = 'C:\Windows\Temp\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\AndroidSdk'
$env:ANDROID_SDK_ROOT = 'C:\AndroidSdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd d:\InfoCaller
Remove-Item bfull.ps1, bfull2.ps1, bfull3.ps1 -Force -ErrorAction SilentlyContinue
& .\gradlew.bat --stop 2>&1 | Out-Null
Remove-Item -Recurse -Force app\build -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force .gradle -ErrorAction SilentlyContinue
& .\gradlew.bat assembleDebug --no-configuration-cache 2>&1 | Select-String -Pattern 'BUILD|FAILED|^e: ' | Select-Object -First 10
Get-ChildItem app\build\outputs\apk\debug\app-debug.apk
