$env:JAVA_HOME = 'C:\Windows\Temp\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\AndroidSdk'
$env:ANDROID_SDK_ROOT = 'C:\AndroidSdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd d:\InfoCaller
$r = & .\gradlew.bat assembleDebug --no-configuration-cache 2>&1 | Out-String
($r -split "`n" | Select-String -Pattern 'BUILD|FAILED|^e: ' | Select-Object -First 10) -join "`n"
Get-ChildItem app\build\outputs\apk\debug\app-debug.apk
Get-Date
