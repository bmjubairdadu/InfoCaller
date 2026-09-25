package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

class MaigretSweepProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "maigret_sweep"
    override val name = "Maigret Mega Sweep (120 sites)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.SOCIAL_MATCH, Capability.SERVICE_PRESENCE, Capability.PUBLIC_PROFILE)
    override val priority = 60
    override val costClass = CostClass.FREE

    private val sites = listOf(
        "LinkedIn" to "https://www.linkedin.com/in/%s",
        "GitHub" to "https://github.com/%s",
        "Instagram" to "https://www.instagram.com/%s/",
        "TikTok" to "https://www.tiktok.com/@%s",
        "Twitter" to "https://twitter.com/%s",
        "X" to "https://x.com/%s",
        "Facebook" to "https://www.facebook.com/%s",
        "YouTube" to "https://www.youtube.com/@%s",
        "Twitch" to "https://www.twitch.tv/%s",
        "Steam" to "https://steamcommunity.com/id/%s",
        "Reddit" to "https://www.reddit.com/user/%s",
        "Pinterest" to "https://www.pinterest.com/%s/",
        "Tumblr" to "https://%s.tumblr.com",
        "Medium" to "https://medium.com/@%s",
        "Vimeo" to "https://vimeo.com/%s",
        "SoundCloud" to "https://soundcloud.com/%s",
        "Spotify" to "https://open.spotify.com/user/%s",
        "DeviantArt" to "https://www.deviantart.com/%s",
        "Flickr" to "https://www.flickr.com/people/%s",
        "Behance" to "https://www.behance.net/%s",
        "Dribbble" to "https://dribbble.com/%s",
        "Patreon" to "https://www.patreon.com/%s",
        "KoFi" to "https://ko-fi.com/%s",
        "BuyMeACoffee" to "https://www.buymeacoffee.com/%s",
        "OnlyFans" to "https://onlyfans.com/%s",
        "Fansly" to "https://fansly.com/%s",
        "Tinder" to "https://tinder.com/@%s",
        "Bumble" to "https://bumble.com/@%s",
        "OkCupid" to "https://www.okcupid.com/profile/%s",
        "Discord" to "https://discord.com/users/%s",
        "Telegram" to "https://t.me/%s",
        "WhatsApp" to "https://wa.me/%s",
        "Signal" to "https://signal.me/#p/%s",
        "Viber" to "https://viber.com/%s",
        "Line" to "https://line.me/%s",
        "WeChat" to "https://wechat.com/%s",
        "Snapchat" to "https://www.snapchat.com/add/%s",
        "VSCO" to "https://vsco.co/%s",
        "Wattpad" to "https://www.wattpad.com/user/%s",
        "Quora" to "https://www.quora.com/profile/%s",
        "StackOverflow" to "https://stackoverflow.com/users/%s",
        "GitLab" to "https://gitlab.com/%s",
        "Bitbucket" to "https://bitbucket.org/%s",
        "Kaggle" to "https://www.kaggle.com/%s",
        "HackerNews" to "https://news.ycombinator.com/user?id=%s",
        "ProductHunt" to "https://www.producthunt.com/@%s",
        "Replit" to "https://replit.com/@%s",
        "Codepen" to "https://codepen.io/%s",
        "Glitch" to "https://glitch.com/@%s",
        "DevTo" to "https://dev.to/%s",
        "Hashnode" to "https://hashnode.com/@%s",
        "HackerRank" to "https://www.hackerrank.com/%s",
        "LeetCode" to "https://leetcode.com/%s",
        "Codeforces" to "https://codeforces.com/profile/%s",
        "Topcoder" to "https://profiles.topcoder.com/%s",
        "Dailymotion" to "https://www.dailymotion.com/%s",
        "Rumble" to "https://rumble.com/user/%s",
        "Odysee" to "https://odysee.com/@%s",
        "Bitchute" to "https://www.bitchute.com/channel/%s",
        "PeerTube" to "https://peer.tube/a/%s",
        "Mixcloud" to "https://www.mixcloud.com/%s/",
        "Bandcamp" to "https://%s.bandcamp.com",
        "LastFm" to "https://www.last.fm/user/%s",
        "Letterboxd" to "https://letterboxd.com/%s/",
        "IMDb" to "https://www.imdb.com/user/%s/",
        "Goodreads" to "https://www.goodreads.com/%s",
        "Duolingo" to "https://www.duolingo.com/profile/%s",
        "Strava" to "https://www.strava.com/athletes/%s",
        "Chess" to "https://www.chess.com/member/%s",
        "Lichess" to "https://lichess.org/@/%s",
        "Roblox" to "https://www.roblox.com/users/%s/profile",
        "Minecraft" to "https://namemc.com/minecraft-names/%s",
        "EpicGames" to "https://www.epicgames.com/id/%s",
        "Xbox" to "https://xboxgamertag.com/search/%s",
        "PlayStation" to "https://psnprofiles.com/%s",
        "Nintendo" to "https://www.nintendo.com/%s",
        "Origin" to "https://www.origin.com/%s",
        "Epic" to "https://www.epicgames.com/%s",
        "ItchIo" to "https://%s.itch.io",
        "GameJolt" to "https://gamejolt.com/@%s",
        "BoardGameGeek" to "https://boardgamegeek.com/user/%s",
        "Chess24" to "https://chess24.com/en/profile/%s",
        "Keybase" to "https://keybase.io/%s",
        "AboutMe" to "https://about.me/%s",
        "Gravatar" to "https://en.gravatar.com/%s",
        "Blogger" to "https://%s.blogspot.com",
        "WordPress" to "https://%s.wordpress.com",
        "Foursquare" to "https://foursquare.com/%s",
        "Slideshare" to "https://www.slideshare.net/%s",
        "TripAdvisor" to "https://www.tripadvisor.com/Profile/%s",
        "Pastebin" to "https://pastebin.com/u/%s",
        "Gist" to "https://gist.github.com/%s",
        "NPM" to "https://www.npmjs.com/~%s",
        "PyPI" to "https://pypi.org/user/%s/",
        "RubyGems" to "https://rubygems.org/profiles/%s",
        "DockerHub" to "https://hub.docker.com/u/%s",
        "Crunchbase" to "https://www.crunchbase.com/person/%s",
        "AngelList" to "https://angel.co/u/%s",
        "Dribbble2" to "https://dribbble.com/%s/about",
        "Ello" to "https://ello.co/%s",
        "Mastodon" to "https://mastodon.social/@%s",
        "Pixelfed" to "https://pixelfed.social/%s",
        "Peertube2" to "https://video.hardlimit.com/a/%s",
        "Lemmy" to "https://lemmy.world/u/%s",
        "Kbin" to "https://kbin.social/u/%s",
        "Taringa" to "https://www.taringa.net/%s",
        "VK" to "https://vk.com/%s",
        "Odnoklassniki" to "https://ok.ru/%s",
        "Weibo" to "https://weibo.com/%s",
        "Bilibili" to "https://space.bilibili.com/%s",
        "Niconico" to "https://www.nicovideo.jp/user/%s",
        "ShareChat" to "https://sharechat.com/%s",
        "Moj" to "https://mojapp.in/@%s",
        "Chingari" to "https://chingari.io/%s",
        "Josh" to "https://myjosh.in/%s",
        "Roposo" to "https://www.roposo.com/%s",
        "Likee" to "https://likee.video/@%s",
        "Triller" to "https://triller.co/@%s",
        "Kwai" to "https://www.kwai.com/%s",
        "SnackVideo" to "https://www.snackvideo.com/@%s",
        "Zili" to "https://www.zili.com/%s",
        "TakaTak" to "https://mxtakatak.com/%s",
        "Chingari2" to "https://chingari.app/%s",
    ).distinctBy { it.first }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME && type != IdentifierType.EMAIL) return@withContext null
        val username = if (type == IdentifierType.EMAIL) identifier.substringBefore("@") else identifier.trim()
        if (username.length < 3 || username.length > 30 || username.contains(" ")) return@withContext null
        val profiles = UsernameExistenceChecker.mapBounded(sites.distinctBy { it.first }) { (name, tmpl) ->
            val url = try { tmpl.format(username) } catch (_: Exception) { return@mapBounded null }
            if (UsernameExistenceChecker.exists(httpClient, url)) SocialProfile(name, username, url, SocialLookupStatus.PUBLIC_MATCH) else null
        }
        if (profiles.isEmpty()) return@withContext null
        PartialResult(socialProfiles = profiles, confidence = 0.7f, source = "Maigret Sweep (120 sites)", providerId = id, providerVersion = version)
    }
}
