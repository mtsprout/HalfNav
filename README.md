# HalfNav

Navigation help for only the part of the trip you need it: guidance to the interstate, or just the last few miles, with the rest of the drive left to you.

The app opens to a full-screen map. Search for any place or address, like "The Alamo", "gas station" or "123 Main St". Results are ranked by how close they are, and each shows its address, what kind of place it is, and its distance. Tap a result to see it on the map, pick a mode, and go.

| Mode | What happens |
|---|---|
| **At the start: get me to the interstate** | Plans the route, finds where it gets on the first interstate (or US highway / freeway), and guides you ~0.3 mi onto it. Then you're on your own. |
| **At the start: the first few miles** | Same, but guidance stops after the number of miles you pick. |
| **At the end: the last few miles** | Watches your location in the background. When you're within the distance you pick, turn-by-turn guidance starts. |

Before a start-mode trip, the map draws your route:
- **solid blue** for the part where you're guided
- **dashed gray** for the part where you're on your own
- **orange** for reported construction

In end mode, the map shades the zone where guidance will kick in.

## Drive view

Once you're under way, HalfNav shows a GPS-style drive view:
- **Camera:** tilted, following you and turning with the road.
- **Vehicle icon:** a car, or an arrow if you prefer (choose in Settings). It snaps onto the route line when you're on it.
- **Route line:** shows only the road ahead. Blue is the part where you're guided, gray is where you're on your own, and orange is construction.
- **Top banner:** the next maneuver ("0.4 mi · Keep right at I-95 S"). It's green while you're guided and gray while you're on your own. An orange chip warns about construction in the next 30 miles.
- **Bottom bar:** your speed, time and miles left, arrival time, **Google from here** (hand the rest of the trip to Google Maps), and **End**.
- **Rerouting:** if you leave the route, HalfNav fetches a new one from where you are.
- **Arrival:** "You've arrived" shows when you get there.
- **Screen:** stays on while the drive view is open.

How you get to it:
- **Help at the start:** after "Guide me to I-xx," the drive view opens and HalfNav talks you onto the interstate, then goes quiet.
- **Help at the end:** "Start watching" opens the drive view right away, and guidance starts when you're close.
- **No guidance at all:** "Drive view only" on the route check.

Press Back to get to the regular map. A "Driving to…" card there takes you back to the drive view.

## Voice directions

HalfNav speaks turn-by-turn directions itself on the guided part of the trip ("In half a mile, keep left at I 35 South"), so it never needs to open Google Maps. While you're on your own it stays quiet except for construction warnings and "You've arrived."

- It briefly lowers music while it talks; most podcast apps pause and resume instead.
- The 🔊 button next to the speed bubble mutes it.
- Trips run in the background, so directions keep coming with the screen off. An ongoing notification shows the next turn and has an **End trip** button.
- Prefer Google's voice? In Settings, set **Turn-by-turn voice** to **Google Maps**. HalfNav then hands the guided part to Google Maps, as in earlier versions.

Before a start-mode trip, HalfNav also checks the **whole route** for reported road work and closures. If any fall on the part you'd drive without guidance, it warns you and offers **Let Google route the whole trip**. You can turn this off in Settings (the gear icon in the search bar).

## Dark mode

By default HalfNav switches to a dark map and dark panels at official sunset where you are, and back at sunrise. It works out the times from your location, so it stays right on a road trip across time zones. In Settings, **Appearance** offers **Auto** (sunset to sunrise), **Light** or **Dark**. The app opens in whichever look it last had, so there's no white flash when you open it at night.

## One-time setup

1. **Get a free TomTom API key** (used for search, routes and construction data):
   - Sign up at <https://developer.tomtom.com>. No credit card is needed.
   - Copy the API key from your dashboard.
   - Create the file `HalfNav/local.properties` (or open it if it exists) and add:
     ```
     TOMTOM_KEY=your-key-here
     ```
2. **Install Android Studio** (a recent version; this project uses Android Gradle Plugin 8.7). Download it from <https://developer.android.com/studio> and follow its setup wizard, which installs the Android SDK.
3. Open the `HalfNav` folder in Android Studio and let it sync.

## Install on your phone

1. On the phone, go to **Settings → About phone** and tap **Build number** 7 times. You'll see "You are now a developer."
2. Go to **Settings → System → Developer options** and turn on **USB debugging**. On some phones Developer options is directly under Settings.
3. Plug the phone into the Mac and tap **Allow** on the "Allow USB debugging?" prompt.
4. In Android Studio, pick your phone in the device dropdown at the top and click **Run ▶**. The app installs and stays on your phone as **HalfNav**.

That installs a debug build, which is handy while developing.

### Release build (smaller, optimized, installable without a cable)

1. **Create a signing key once** and keep it safe. Android only installs an update if it's signed with the same key as the version already on the phone. Keep the key file outside this folder and back it up:
   ```
   keytool -genkeypair -keystore ~/.halfnav/halfnav-release.jks -storetype PKCS12 \
     -alias halfnav -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=HalfNav"
   ```
2. **Tell the build where it is** by adding to `local.properties` (never committed):
   ```
   RELEASE_STORE_FILE=/full/path/to/halfnav-release.jks
   RELEASE_STORE_PASSWORD=...
   RELEASE_KEY_ALIAS=halfnav
   RELEASE_KEY_PASSWORD=...
   ```
3. **Build:** `./gradlew assembleRelease`, or in Android Studio **Build → Generate App Bundles or APKs → Generate APKs** with the release build variant.
4. **Install:** in `app/build/outputs/apk/release/`, copy `HalfNav-<version>.apk` to the phone (Google Drive, email, etc.), tap it, and allow "Install unknown apps" when asked. That's the one for nearly all phones (arm64). The folder also has `-armeabi-v7a` for older 32-bit phones and `-x86_64` for emulators.

Switching between a debug and a release build means uninstalling first, because they're signed with different keys.

## First run

- Allow **precise location** and **notifications** when asked.
- If you use Google Maps as the voice: for end mode to open Google Maps on its own, open Settings (the gear icon), tap **Allow auto-open**, and turn on "Display over other apps." Without it, Android only lets HalfNav show a notification. You tap the notification to start navigating.

## iPhone and iPad

The `ios/` folder has the iPhone and iPad version: the same features, map and voice, built in SwiftUI on the shared core. On iPad, panels sit in a side column next to a full-size map.

**Setup (once):**
1. Install **Xcode** from the Mac App Store and a **JDK 17 or newer** (for example from <https://adoptium.net>). Xcode uses the JDK to build the shared core.
2. Copy `ios/Config/Secrets.example.xcconfig` to `ios/Config/Secrets.xcconfig` and put your TomTom key in it. This file is never committed.
3. Open `ios/HalfNav.xcodeproj` in Xcode. If you change `ios/project.yml`, regenerate the project with [XcodeGen](https://github.com/yonaskolb/XcodeGen): `cd ios && xcodegen generate`.

**Install on your iPhone or iPad:**
1. In Xcode, go to **Settings → Accounts** and sign in with your Apple ID.
2. Select the **HalfNav** target, then **Signing & Capabilities**, and choose your account as the **Team**. If Xcode says the bundle ID is taken, change it to something unique.
3. Plug in the iPhone, pick it as the run destination, and press **Run ▶**.
4. The first time, on the iPhone: **Settings → General → VPN & Device Management**, tap your Apple ID, and choose **Trust**. iOS 16 and later also ask you to turn on **Developer Mode** (Settings → Privacy & Security).

With a free Apple ID, the app stops opening after 7 days; just press **Run** again from Xcode to renew it. A paid Apple Developer account ($99/year) lasts a year and lets you install over the air with TestFlight.

**Differences from Android:**
- iOS never lets one app open another from the background. If you choose Google Maps as the voice in end mode, you get a notification to tap instead of an automatic switch.
- **Google from here** opens Google Maps if it's installed, otherwise Apple Maps.
- When asked, allow location **While Using**. Directions keep going with the screen off during a trip; iOS shows a blue location pill while it's running.

## Project layout

- `core/`: shared Kotlin Multiplatform logic used by both apps. It parses TomTom search and route responses, finds the interstate handoff point, tags construction as before or after the handoff, snaps GPS fixes onto the route, words the spoken directions, and works out sunrise and sunset. Its tests run on both the JVM and the iOS simulator: `./gradlew :core:allTests`.
- `app/`: the Android app, with these files:
  - `MainActivity.kt`: map screen, search bar, place card
  - `TripMap.kt`: the map (MapLibre with OpenFreeMap's free street map), including the drive-view camera
  - `DriveScreen.kt`, `DriveService.kt`: the drive view and the background trip with voice
  - `RouteReviewScreen.kt`: the construction check screen
  - `TomTomClient.kt`, `Locations.kt`, `MapsLauncher.kt`, `Prefs.kt`, `Theme.kt`
- `ios/`: the iPhone/iPad app (SwiftUI):
  - `ContentView.swift`, `SearchViews.swift`, `RouteReviewView.swift`, `DriveOverlay.swift`: screens
  - `TripMap.swift`: the map and drive-view camera
  - `DriveSession.swift`, `LocationService.swift`, `VoiceGuide.swift`: the background trip with voice
  - `HalfNavUITests/`: end-to-end tests that drive the app with simulated GPS

## Limitations

- Construction data comes from TomTom's live incident reports, so it won't include every work zone.
- TomTom's route may differ slightly from Google's. The drive view follows TomTom's route; "Google from here" hands the rest of the trip to Google Maps at any time.

## License

MIT. See [LICENSE](LICENSE).
