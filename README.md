<p align="center"><img src="screenshots/home.png" alt="Stremli home screen" width="800"></p>

# Stremli

Stremli is a free anime app for Fire TV and Android TV. Browse thousands of series and films, follow what's airing, keep your lists in sync, and watch from the couch with nothing but your remote. It's built to stay fast and light, even on a 1 GB Fire TV Stick.

No ads, no analytics.

## Get it

**[Download the latest version](https://github.com/stremli/stremli/releases/latest/download/stremli.apk)** or see all versions on the [Releases page](https://github.com/stremli/stremli/releases/latest).

On a Fire TV:

1. Install the free **Downloader** app from the Amazon Appstore.
2. Go to **Settings → My Fire TV → Developer options** and allow Downloader to install unknown apps.
3. Open Downloader and enter `https://github.com/stremli/stremli/releases/latest/download/stremli.apk`.
4. Choose **Install**, then open Stremli.

On other Android TV devices, open the same link in a browser or copy the APK over and install it with a file manager. Stremli needs Android 7.1 or newer.

After that, Stremli updates itself. It checks once a day and asks before installing anything.

## Screenshots

| Explore | Choosing a server |
|---|---|
| ![Explore](screenshots/explore.png) | ![Servers](screenshots/servers.png) |

| Episodes in the player | Home |
|---|---|
| ![Episodes](screenshots/episodes.png) | ![Home](screenshots/home.png) |

## Features

### Browse and discover

- **Home** with a rotating banner, Continue watching, new episodes, what's airing now, popular series and what's coming up. Home refreshes itself when you open it.
- **Explore** every series and film, with filters for type, season, year, genre and status.
- **Search** with suggestions, past searches and series or film filters.
- **Title pages** with the synopsis, seasons and related titles, and an episode list that stays easy to use on shows with over a thousand episodes.
- **Portrait or landscape cards**, whichever you prefer.

### Schedule

- See what's airing by week or by month.
- Show everything, or just the shows on your lists.
- Pick a day on the calendar to see what airs that day.

### Watching

- **Press Play and it starts.** Your preferred source and version are used every time, and if they aren't available the next best option plays and tells you.
- **Everything on the right.** Servers, episodes, audio and subtitles, quality and speed open in one side panel, without leaving the video.
- **Choose how you watch.** Servers are grouped by subbed and dubbed, and show their subtitle type and available qualities before you pick one. Switching server carries on from the same moment, and your pick stays for the show until you close the app.
- **Skip intros, outros and recaps** with a button, or skip them automatically when exact times are known. These parts are marked on the seek bar.
- **Picks up where you left off**, with autoplay for the next episode.
- **Remote and voice friendly.** Seek with the D-pad, use media keys, and control playback with Alexa.

### Your lists and sync

- **Sign in to AniList and MyAnimeList by scanning a QR code** on the TV with your phone. No typing passwords with a remote.
- **Two-way sync.** Changes go out as you make them, and changes made elsewhere come back in.
- **Automatic progress.** Finish an episode and your list moves forward.
- **Lists that work without an account.** Use Watching, Plan to watch, Completed, On hold, Dropped and Rewatching, or make your own.
- **Move titles around** by holding OK on any poster, and view several lists at once.

### Profiles

- Everyone gets their own profile, with their own lists, history, sign-ins and settings.
- Protect a profile with a PIN.
- Adult titles stay off unless a profile turns them on.

### Settings

- **Playback:** preferred source and version, speed, skip step, resume, autoplay and how intros, outros and recaps are handled.
- **Lists and sync:** automatic list updates and how often to check for changes.
- **Home and Schedule:** choose which rows appear, the banner, and what opens first.
- **This TV:** updates, the video buffer with advice for your device's memory, and storage clean-up.

### Built for small devices

Stremli sizes its memory use to your device, doesn't animate anything while you're sitting still, and works entirely with a remote.

## Help make Stremli better

Stremli is open source, and it gets better with every person who pitches in.

- **Found a bug?** [Open an issue](https://github.com/stremli/stremli/issues/new) and tell us your device, what you did and what happened.
- **Have an idea?** [Suggest a feature](https://github.com/stremli/stremli/issues/new). Small ideas are welcome too.
- **Know Kotlin or Jetpack Compose?** Pull requests are very welcome, from typo fixes to new features. If it's a big change, open an issue first so we can talk it through.

To build it, open this folder in Android Studio with JDK 17 or newer, or run `./gradlew assembleDebug`.

## Stay in touch

If this page ever moves, Stremli finds the new location by itself. You can also follow updates on Nostr: `npub12hph5p0sr9nre9g7cq5cl5s95psyw8kudrw3kpgjqscet7zakjds0jn9te`

Licences for the fonts, icons and other third-party work are in `app/src/main/assets`.
