$ErrorActionPreference = 'Continue'
function Probe($n, $u) {
  try {
    $code = & curl.exe -sL -o NUL -w '%{http_code} %{size_download} %{time_total} %{url_effective}' -m 15 -A 'Mozilla/5.0 (Linux; Android 14)' $u 2>$null
    Write-Output "$n => $code"
  } catch { Write-Output "$n => ERR" }
}
Probe 'x-follow' 'https://cdn.syndication.twimg.com/widgets/followbutton/info.json?screen_names=jubairdadu143'
Probe 'gh-page' 'https://github.com/jubairdadu143'
Probe 'tg-handle' 'https://t.me/jubairdadu143'
Probe 'tg-phone' 'https://t.me/+8801785917145'
Probe 'tc-bd' 'https://www.truecaller.com/bd/1785917145'
Probe 'syncme' 'https://sync.me/search/?number=%2B8801785917145'
Probe 'shouldianswer' 'https://www.shouldianswer.net/phone/8801785917145'
Probe 'whocallsme' 'https://whocallsme.com/Phone-Number-8801785917145'
Probe 'spamcalls' 'https://spamcalls.net/en/phone/8801785917145'
Probe 'eyecon' 'https://api.eyecon-app.com/app/getnames.jsp?cli=8801785917145&lang=en&is_callerid=true&is_ic=true&cv=vc_786&requestApi=URLconnection&source=StatisticBars'
Probe 'grav-json-L' 'https://www.gravatar.com/a34c06da97b9a6d4d628b7637d8108fc.json'
Probe 'disify-L' 'https://www.disify.com/api/email/jubairhossen441%40gmail.com'
Probe 'yt-oembed' 'https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2F%40jubairdadu143&format=json'
Write-Output '--- bodies ---'
function Body($n, $u) {
  try {
    $b = & curl.exe -sL -m 15 -A 'Mozilla/5.0 (Linux; Android 14)' $u 2>$null
    if ($b -eq $null) { $b = '' }
    $s = [string]$b
    if ($s.Length -gt 600) { $s = $s.Substring(0,600) }
    $s = $s -replace "`r", ' ' -replace "`n", ' '
    Write-Output "$n BODY :: $s"
  } catch { Write-Output "$n BODY ERR" }
}
Body 'gitlab' 'https://gitlab.com/api/v4/users?username=jubairhossen441'
Body 'xposed' 'https://api.xposedornot.com/v1/check-email/jubairhossen441%40gmail.com'
Body 'disify' 'https://www.disify.com/api/email/jubairhossen441%40gmail.com'
Body 'spamcsv' 'https://raw.githubusercontent.com/tareknahas85-star/block-number-data/main/spamdb.csv'
Body 'grav-json' 'https://www.gravatar.com/a34c06da97b9a6d4d628b7637d8108fc.json'
Body 'x-follow' 'https://cdn.syndication.twimg.com/widgets/followbutton/info.json?screen_names=jubairdadu143'
Body 'yt-oembed' 'https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2F%40jubairdadu143&format=json'
Body 'tg-handle' 'https://t.me/jubairdadu143'
Body 'eyecon' 'https://api.eyecon-app.com/app/getnames.jsp?cli=8801785917145&lang=en&is_callerid=true&is_ic=true&cv=vc_786&requestApi=URLconnection&source=StatisticBars'
