$env:JAVA_HOME = 'C:\Windows\Temp\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\AndroidSdk'
$env:ANDROID_SDK_ROOT = 'C:\AndroidSdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd d:\InfoCaller
& .\gradlew.bat assembleDebug --no-configuration-cache 2>&1 | Select-String -Pattern 'BUILD|FAILED|e: file' | Select-Object -First 10
Write-Host '=== APK ==='
Get-ChildItem app\build\outputs\apk\debug\app-debug.apk | Format-Table Name, Length, LastWriteTime
