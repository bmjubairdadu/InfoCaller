$ErrorActionPreference = 'Continue'
$email = 'jubairhossen441@gmail.com'
$md5bytes = [System.Security.Cryptography.MD5]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($email.ToLower()))
$md5 = ([System.BitConverter]::ToString($md5bytes)).Replace('-','').ToLower()
Write-Output "MD5=$md5"
$tests = @(
  @('grav-json', ('https://www.gravatar.com/' + $md5 + '.json')),
  @('grav-avatar', ('https://www.gravatar.com/avatar/' + $md5 + '?s=400&d=404')),
  @('gh-prefix', 'https://api.github.com/users/jubairhossen441'),
  @('gh-user', 'https://api.github.com/users/jubairdadu143'),
  @('gitlab', 'https://gitlab.com/api/v4/users?username=jubairhossen441'),
  @('xposed', 'https://api.xposedornot.com/v1/check-email/jubairhossen441%40gmail.com'),
  @('disify', 'https://www.disify.com/api/email/jubairhossen441%40gmail.com'),
  @('wmn-data', 'https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json'),
  @('disp-list', 'https://raw.githubusercontent.com/ip1sms/disposable-phone-numbers/master/number-list.json'),
  @('spamcsv', 'https://raw.githubusercontent.com/tareknahas85-star/block-number-data/main/spamdb.csv'),
  @('grep-phone', 'https://grep.app/api/search?q=%2201785917145%22'),
  @('x-follow', 'https://cdn.syndication.twimg.com/widgets/followbutton/info.json?screen_names=jubairdadu143'),
  @('gh-page', 'https://github.com/jubairdadu143'),
  @('tg-handle', 'https://t.me/jubairdadu143'),
  @('tg-phone', 'https://t.me/+8801785917145'),
  @('tc-bd', 'https://www.truecaller.com/bd/1785917145'),
  @('syncme', 'https://sync.me/search/?number=%2B8801785917145'),
  @('shouldianswer', 'https://www.shouldianswer.net/phone/8801785917145'),
  @('whocallsme', 'https://whocallsme.com/Phone-Number-8801785917145'),
  @('spamcalls', 'https://spamcalls.net/en/phone/8801785917145'),
  @('yt-oembed', 'https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2F%40jubairdadu143&format=json'),
  @('eyecon', 'https://api.eyecon-app.com/app/getnames.jsp?cli=8801785917145&lang=en&is_callerid=true&is_ic=true&cv=vc_786&requestApi=URLconnection&source=StatisticBars')
)
foreach ($t in $tests) {
  $n = $t[0]; $u = $t[1]
  try {
    $r = & curl.exe -s -o NUL -w '%{http_code} %{size_download} %{time_total}' -m 12 -A 'Mozilla/5.0 (Linux; Android 14)' $u 2>$null
    Write-Output "$n => $r :: $u"
  } catch { Write-Output "$n => ERR" }
}
