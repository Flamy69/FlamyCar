# FlamyCar

Shows what the engine is doing on the display of the car: boost pressure, oil temperature, oil
pressure, coolant, intake air temperature, exhaust gas temperature, battery voltage, engine
speed, torque, load, ignition advance, fuel pressure and more – the things a turbocharged engine
is worth watching for and the dashboard does not show.

It needs two things:

- an **ELM327 OBD-II adapter** with classic Bluetooth in the diagnostic socket of the car, paired
  with the phone
- a car with **Android Auto**; without one the app shows the same gauges on the phone alone

No accounts, no ads, no tracking. The app does not even have the permission to use the internet.

## Features

- Two pages of gauges; the button on the display, or a touch where the car passes touches on,
  turns the page
- Boost is the big gauge: manifold pressure minus barometric pressure (the pressure at sea level
  where the car has no barometer), with a marker of the recent peak
- Oil and coolant are shown blue while cold and red when too hot
- Only the values the car really gives are shown; the app finds out which once after connecting
- Demo mode: a simulated car instead of the adapter, to try the gauges without one
- *Survey of the engine*: asks the engine control unit for some 850 values known from Volkswagen
  group cars and notes down which it answers, to find the ones the app does not read yet
- *When something does not work*: a record of what the app and the adapter said to each other,
  which can be copied or shared. It tells what the car supports and where things went wrong;
  attach it to an [issue](https://github.com/Flamy69/FlamyCar/issues) when a value is wrong or
  missing.
- The app only reads. It uses OBD service 01 and UDS service 22 and never writes to a control
  unit, clears fault codes or changes a diagnostic session.
- English and Czech

## Download

Grab the APK of the [latest release](https://github.com/Flamy69/FlamyCar/releases/latest) and
open it on your phone. Android will ask you to allow installing apps from your browser or file
manager the first time. Newer releases install over older ones.

The `.aab` file next to it is the same app packaged for Google Play; it cannot be installed
directly. It is what gets the app onto the display of a car, see [Android Auto](#android-auto).

Requires Android 8.0 or newer.

## Using it

1. Plug the adapter into the diagnostic socket and pair it in the Bluetooth settings of the
   phone; the PIN is usually 1234 or 0000.
2. Open FlamyCar, allow it to use Bluetooth devices and choose the adapter.
3. Switch on the ignition. *Values of this car* lists what the car gives, and the preview shows
   the gauges the way the display of the car will; tap them for the next page.
4. Get the app onto the display of the car, which takes more than installing the APK; see
   [Android Auto](#android-auto) below.

An adapter left in the socket draws current and can keep some cars from going to sleep. Unplug
it when the car stands for long.

## Android Auto

Android Auto gives apps a free canvas in one place only: where a navigation app draws its map.
The gauges are drawn there, so to Android Auto FlamyCar is a navigation app whose "map" happens
to consist of gauges. Three things follow:

- The app is not on Google Play, because it is not the navigation app it presents itself as.
- The gauges and the map of a navigation app cannot be on the display at the same time. Android
  Auto also takes the navigation app opened last for the one to send spoken destinations to, so
  open your navigation again before asking for a route.
- Android Auto shows apps built this way only when Google Play installed them. The switch
  *Unknown sources* in its developer settings, which lets in media apps installed by hand, does
  [not apply to them](https://developer.android.com/training/cars/testing). An APK installed by
  hand works on the phone and stays hidden in the car.

Ways to get it onto the display of the car:

- **Internal testing in your own Google Play Console**: create an app with the same package
  name, upload the `.aab` file of the release to the internal testing track with yourself as
  the tester, and install it from the Play Store. Google Play signs the app with a key of its
  own, so uninstall the copy from the APK first, and update only through Play afterwards: an
  update installed by hand hides the app in the car again.
- **Installers that make the install look like one by Google Play**, such as KingInstaller or
  UnknownAuto with Shizuku. They need no computer and no account, but work on some phones and
  Android versions only.
- **Without a car**, the [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
  of the Android SDK shows on a computer what the display of the car would, for an app installed
  in any way.

Installing with `adb install -i com.android.vending` does not help: Android Auto looks at who
started the installation, which then is the shell.

## Which values a car gives

The standard OBD-II values (coolant, intake air, manifold pressure, engine speed, load, ignition
advance, fuel pressure and the like) work on any car with a CAN bus, which is every car sold in
the EU since 2008.

Oil temperature, oil pressure, exhaust gas temperature and torque are read by identifiers
specific to Volkswagen group cars (MQB platform: SEAT Leon, Škoda Octavia, VW Golf and their
relatives). Which of them an engine knows differs, so the app tries a list of candidates once
after connecting and uses those that give a believable value.

These identifiers are **unverified**: they were written for a 2019 SEAT Leon 1.5 TSI and have
not been confirmed on other engines. Compare such values with what you know before trusting
them. The identifiers and their scalings were looked up in the
[OBDb](https://github.com/OBDb) project (CC BY-SA 4.0).

## Building

Requirements: JDK 17 and the Android SDK (either set `ANDROID_HOME`, or create a
`local.properties` file with `sdk.dir=/path/to/sdk`). Android Studio takes care of both.

```sh
./gradlew assembleRelease        # optimized build
./gradlew bundleRelease          # the same as an app bundle for Google Play
./gradlew testDebugUnitTest      # unit tests
```

One of the tests draws the gauges with Android's real drawing code, through Robolectric, and
leaves PNG pictures in `app/build/dashboards`: the quickest way to look at a change of the layout.

### Signing

On a fresh checkout the release build is signed with the Android debug key, so it installs straight
away but cannot replace an official release (Android refuses updates signed with a different key).
To sign with your own key, create a `keystore.properties` file in the project root; it is ignored
by git:

```properties
FLAMY_KEYSTORE_FILE=my-key.jks
FLAMY_KEYSTORE_PASSWORD=...
FLAMY_KEY_ALIAS=...
FLAMY_KEY_PASSWORD=...
```

The same four names work as environment variables, which is how the CI build gets them.

### Releases

Every push to `main` is tested and built by [GitHub Actions](.github/workflows/build.yml), and the
APK and the app bundle are attached to the workflow run. Pushing a version tag of the form `vMAJOR.MINOR.PATCH`
publishes it as a release and sets the version of the app. The workflow reads the signing key
from the repository secrets `KEYSTORE_BASE64` (the keystore file, Base64 encoded),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Project layout

```
app/src/main/kotlin/io/github/flamy69/flamycar/
├── obd/                talking to the adapter: Elm327, CanFrames, ObdSession, Signals with the
│                       catalog of values, SimulatedCar, BluetoothLink
├── ObdManager          keeps the connection while somebody is looking
├── Dashboard           which gauges go where
├── DashboardPainter    drawing them
├── car/                the screen for Android Auto
└── ui/                 the app on the phone
```

Written in Kotlin with Jetpack Compose (Material 3) and the Android for Cars App Library.

## License

The source code of FlamyCar is under the [MIT](LICENSE) license.
