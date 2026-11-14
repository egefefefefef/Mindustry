![Logo](core/assets-raw/sprites/ui/logo.png)

[![Build Status](https://github.com/Anuken/Mindustry/workflows/Tests/badge.svg?event=push)](https://github.com/Anuken/Mindustry/actions)
[![Discord](https://img.shields.io/discord/391020510269669376.svg?logo=discord&logoColor=white&logoWidth=20&labelColor=7289DA&label=Discord&color=17cf48)](https://discord.gg/mindustry)  

The automation tower defense RTS, written in Java.

_[Trello Board](https://trello.com/b/aE2tcUwF/mindustry-40-plans)_  
_[Wiki](https://mindustrygame.github.io/wiki)_  
_[Javadoc](https://mindustrygame.github.io/docs/)_ 

## Contributing

See [CONTRIBUTING](CONTRIBUTING.md) for general code style and PR guidelines.

If you are a first-time contributor looking for features to implement or bugs to fix, see the issues tagged with 'candidate' [in the Mindustry-Suggestions repostiory](https://github.com/Anuken/Mindustry-Suggestions/issues?q=is%3Aissue%20state%3Aopen%20label%3Acandidate).

## Building

Bleeding-edge upstream builds are available [here](https://github.com/Anuken/MindustryBuilds/releases).

### GitHub Actions CI

The `Tests` and `Pull Request Tests` workflows run on pushes and pull requests, respectively; either can also be started manually from the Actions tab. They run the Java and Rust tests, build the desktop and server JARs, and check that the desktop JAR can load its bundled Linux x86-64 Rust library. Download both JARs from the `mindustry-linux-jars` artifact. Java test reports are uploaded when available, including on failure. The `Mindustry v1.2 platform builds` workflow builds and verifies platform packages when run manually. Its `publish` option publishes the verified packages together under the v1.2 tag; it refuses to move an existing tag. A signed iOS build requires `IOS_SIGNING_CERTIFICATE_BASE64`, `IOS_CERTIFICATE_PASSWORD`, `IOS_PROVISIONING_PROFILE_BASE64`, and `IOS_SIGN_IDENTITY` Actions secrets.

If you'd rather compile on your own, follow these instructions.
First, make sure you have [JDK 17](https://adoptium.net/temurin/releases/?os=any&arch=any&version=17) installed. **Other JDK versions will not work.** Open a terminal in the Mindustry directory and run the following commands:

### Windows

For a ready-to-run Windows x64 package, download `Mindustry-v1.2-Windows-x64.zip` from the fork's Releases, extract it, and run `Start-Mindustry.bat`. It uses the Java runtime in the included `jre` folder; Java does not need to be installed separately.

_Running:_ `gradlew desktop:run`  
_Building:_ `gradlew desktop:dist`  
_Sprite Packing:_ `gradlew tools:pack`

### Linux/Mac OS

_Running:_ `./gradlew desktop:run`  
_Building:_ `./gradlew desktop:dist`  
_Sprite Packing:_ `./gradlew tools:pack`

### Server

The v1.2 fork release contains desktop packages only. To build the server yourself, replace 'desktop' with 'server', e.g. `gradlew server:dist`.

### Android

1. Install the Android SDK [here.](https://developer.android.com/studio#command-tools) Make sure you're downloading the "Command line tools only", as Android Studio is not required.
2. In the unzipped Android SDK folder, find the cmdline-tools directory. Then create a folder inside of it called `latest` and put all of its contents into the newly created folder.
3. In the same directory run the command `sdkmanager --licenses` (or `./sdkmanager --licenses` if on linux/mac)
4. Set the `ANDROID_HOME` environment variable to point to your unzipped Android SDK directory.
5. Enable developer mode on your device/emulator. If you are on testing on a phone you can follow [these instructions](https://developer.android.com/studio/command-line/adb#Enabling), otherwise you need to google how to enable your emulator's developer mode specifically.
6. Run `gradlew android:assembleDebug` (or `./gradlew` if on linux/mac). This will create an unsigned APK in `android/build/outputs/apk`.

To debug the application on a connected device/emulator, run `gradlew android:installDebug android:run`.

### Troubleshooting

#### Permission Denied

If the terminal returns `Permission denied` or `Command not found` on Mac/Linux, run `chmod +x ./gradlew` before running `./gradlew`. *This is a one-time procedure.*

#### Where is the `mindustry.gen` package?

As the name implies, `mindustry.gen` is generated *at build time* based on other code. You will not find source code for this package in the repository, and it should not be edited by hand.

The following is a non-exhaustive list of the "source" of generated code in `mindustry.gen`:

- `Call`, `*Packet` classes: Generated from methods marked with `@Remote`.
- All entity classes (`Unit`, `EffectState`, `Posc`, etc): Generated from component classes in the `mindustry.entities.comp` package, and combined using definitions in `mindustry.content.UnitTypes`.
- `Sounds`, `Musics`, `Tex`, `Icon`, etc: Generated based on files in the respective asset folders.

---

Gradle may take up to several minutes to download files. Be patient. <br>
After building, the output .JAR file should be in `/desktop/build/libs/Mindustry.jar` for desktop builds, and in `/server/build/libs/server-release.jar` for server builds.

## Feature Requests

Post feature requests and feedback [here](https://github.com/Anuken/Mindustry-Suggestions/issues/new/choose).

## Downloads

| [![](https://static.itch.io/images/badge.svg)](https://anuke.itch.io/mindustry)    |    [![](https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png)](https://play.google.com/store/apps/details?id=io.anuke.mindustry)   |    [![](https://fdroid.gitlab.io/artwork/badge/get-it-on.png)](https://f-droid.org/packages/io.anuke.mindustry)	| [![](https://flathub.org/assets/badges/flathub-badge-en.svg)](https://flathub.org/apps/details/com.github.Anuken.Mindustry)  
|---	|---	|---	|---	|

### Experimental features

**Caves** are an experimental feature in the current branch, not in the
published v1.3 release. For a local single-player custom game, turn on
**Settings → Experimental features → Caves** and enable **Generate a cave
entrance** in the custom-game rules. The entrance appears on unoccupied dry
ground near the player's core; click it to enter and click the cave exit to
return. If no suitable ground is found nearby, no entrance is placed. The
surface is paused while you are underground. The two worlds share the core inventory; the cave's
storage limit is fixed to the surface core's capacity when the cave is created.
Resources and structures are saved with the game when it is saved or autosaved;
a transition alone is not a disk save. The new ores can be mined with a laser
drill or better. Disabling either switch prevents entering again, but still
allows an existing cave to be exited. Caves are not available in campaign,
multiplayer or the editor. A save containing paired cave worlds cannot be
hosted for multiplayer, even after returning to the surface. Back up important
saves before testing.

On the surface only, a limited background model approximates production from
warmed-up, enabled standard drills connected to the cave core directly or by a
non-branching chain of regular/titanium conveyors. Powered drills require an
adequate solar-only power network with no non-drill loads. Rates exclude water
boosts, temporarily boosted buildings and transport delay; drills with shared
conveyor routes, fuel generators, batteries as the sole supply, bridges,
routers, processors and other unsupported layouts do not run in the background.
Products are credited directly to the shared core as game time passes, within
both worlds' storage limits; this model does not animate or consume fuel or
water. Other underground buildings, units and items on conveyors remain paused.
Drill progress, resource counts and the approximate background plan persist in
normal saves. While paused or after either cave toggle is disabled, background
production stops. Existing paired saves start background production after their
next visit to and exit from the cave.

Open **Settings → Experimental features** to enable the optional automatic
weather cycle for both local custom games and campaign. The toggle affects the
current local game as well as subsequently loaded worlds. Old custom saves may
also enable it via **Customize → Environment**; this per-world rule remains
supported when the global preference is off. Weather and day/night are not
applied in multiplayer or the editor.

In the same section, **Rain intensity** selects automatic, normal rain or
downpour. This is a local preference, not a saved world rule: it changes the
strength of cycle rain and local map rain marked **Always**, including current
rain, but does not force rain or alter the phase schedule. **Rust unit physics** enables the experimental native unit
collision backend; when unavailable or unsuitable, Java is used instead. Its
default depends on the build, and `-Dmindustry.experimentalNativePhysics` takes
priority over the menu. Changing it applies to subsequent simulation steps.

Clear weather, rain, fog, snow and rain with fog each last three minutes of
active game time. Their order and intensity vary. Visual daylight and darkness
follow a twelve-minute cycle. Rain and snow use existing weather effects and
can affect units and environmental attributes. Snow cover builds and melts
and slows grounded units, including walkers, by up to 25%; flying units and
non-walking hovercraft are unaffected.
Temporary footprints are bounded, visual only, and not saved. Puddles appear on exposed dry ground during the cycle, grow and
dry visually but are not physical liquid. Weather graphics must be enabled to see them. Neither effect replaces terrain.
Normal map weather scheduling is paused while the cycle is active, but map
weather entries marked **Always** continue alongside the cycle. They start
immediately, including rain during the initial clear phase. Other weather
already present can continue until it expires. Turning off weather graphics hides these overlays,
and disabling lighting hides the visual night. Back up important saves before
using experimental features.
