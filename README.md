# Ramus Next

**Java-based IDEF0 & DFD Modeler**

Maintained by [Stanislav Vinokur](https://github.com/stasvinokur).

Ramus Next continues [Ramus](https://ramussoftware.com/), created by Vitaliy Yakovchuk and Oleksiy Chizhevskiy (2005-2025), with macOS packaging and integration work by [Vladislav Pavlik](https://github.com/Inv1x). Released under the [GNU General Public License, version 3](https://www.gnu.org/licenses/gpl-3.0.en.html).

<img width="1792" alt="Screenshot 2019-11-18 at 11 14 26" src="https://user-images.githubusercontent.com/2261228/69039713-23c56d00-09f5-11ea-99c5-b6714efe3037.png">

<img width="1792" alt="Screenshot 2019-11-18 at 11 14 59" src="https://user-images.githubusercontent.com/2261228/69039723-27f18a80-09f5-11ea-9a8d-508069ce7bbd.png">

---

## How to Start the Application

### Step 1: Install JDK

Install a **full JDK in the 17-21 range** (not a JRE). [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) is the recommended build.

> JDK 22 and newer will not work. The Gradle 8.5 wrapper only runs on Java 21 and below, and from JDK 23 onward `:report-core:compileJava` fails outright because `Thread.stop()` was removed from the JDK (`JSSPReportEngine.java:99`, `JSSPDocBookReportEngine.java:81`).

### Step 2: Run the Application

In the console, navigate to the project folder and run:

```bash
./gradlew :local-client:runLocal
```

### Step 3: Test the Application

#### For Linux (Tested on Ubuntu 20.04 and Fedora 34)

1. **Clone the Repository:**

   ```bash
   git clone https://github.com/stasvinokur/ramus-next.git
   ```

2. **Navigate to the Project Folder:**

   ```bash
   cd ramus-next
   ```

3. **Run the Application:**

   ```bash
   ./gradlew :local-client:runLocal
   ```

### Optional: Create a Shortcut to Launch the Application

1. Open your `.bash_aliases` file:
   ```bash
   nano ~/.bash_aliases
   ```

2. Add the following alias to easily launch the application:

   ```bash
   alias ramus='cd ~/path/to/ramus-next/ && ./gradlew :local-client:runLocal &'
   ```

3. Save the file and reload it:

   ```bash
   source ~/.bash_aliases
   ```

4. Now, you can simply run `ramus` in the terminal to launch the application.

## macOS

Ramus Next focuses on native macOS integration and packaging. The Windows installer and Java Web Start
descriptors are inherited from upstream, are not maintained here, and are not expected to work.

Download: the latest macOS DMG is available in this repository's GitHub Releases section.

## Requirements (macOS)

- macOS with developer tools (preinstalled utilities: `sips`, `iconutil`).
- A full JDK in the **17-21** range with `jdeps`, `jlink`, and `jpackage` (not a JRE). Temurin 21 is recommended; JDK 22+ is unsupported, see Step 1 above.
- Optional (icon conversion fallback): `dwebp` from the `webp` package (e.g., `brew install webp`).

Tip: This project supports local overrides without changing your shell’s `JAVA_HOME`.

## Quick Start

1) Build a macOS .app for quick testing

```
./gradlew :local-client:createMacApp
open local-client/build/mac-app/RamusNext.app
```

2) Build a standalone DMG (recommended)

```
./gradlew :local-client:macDmg
open dest/macos
```

The DMG contains a standalone app that does not require users to install Java. On disk the bundle is
`RamusNext.app` and the DMG is `RamusNext-2.0.2.dmg`; the application presents itself as **Ramus Next**
in the macOS menu bar.

Alternatively, download the latest DMG from this repository’s GitHub Releases section.

## Configuration (Optional)

If you keep multiple JDKs, add a local file to point packaging to a specific JDK. These files are ignored by git.

- Create `gradle-local.properties` in the repo root (example):

```
# Full JDK used for packaging (jdeps, jlink, jpackage)
packagingJavaHome=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home

# Disable jlink and bundle a full JDK instead (bigger DMG but simpler)
# packagingUseJlink=false

# Optional explicit module path for jlink (if you want to use modules from a different JDK)
# packagingJmodsPath=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/jmods
```

Alternatively, you can set `org.gradle.java.home` in `gradle.properties` (also ignored by git) if you want Gradle itself to run on a particular JDK.

## Build Tasks Summary

- `:local-client:createMacApp`
  - Creates a dev `.app` under `local-client/build/mac-app/RamusNext.app`.
  - Uses a generated `.icns` and sets Dock icon flags for a native look.

- `:local-client:macDmg`
  - Full packaging pipeline: generates `.icns` → optional `jlink` runtime → `jpackage` DMG.
  - Outputs to `dest/macos/`.
  - If `jlink` isn’t available, it automatically bundles the full JDK at `packagingJavaHome`.

- `:local-client:makeIcns`
  - Converts `packaging/macos/AppIcon.appiconset` into a `.icns` using `sips`/`iconutil`.

## Running From Source (Optional)

You can still run directly from sources:

```
./gradlew :local-client:runLocal
```

Note: the dev run uses your local Java installation; for the full native experience use the `.app` or DMG.

## Contributing

Contributions are very welcome—bug reports, macOS improvements, docs, and packaging tweaks. Please open issues or pull requests.

## Upgrading from Ramus

Ramus Next stores its settings in a directory named after the application, so the rename moves it:

| Platform | Before | After |
|---|---|---|
| macOS | `~/Library/Application Support/Ramus` | `~/Library/Application Support/Ramus Next` |
| Windows | `%APPDATA%\Ramussoft\Ramus` | `%APPDATA%\Ramussoft\Ramus Next` |
| Linux | `~/.ramus` | `~/.ramus-next` |

Nothing is deleted — the old directory is simply ignored, and Ramus Next starts with defaults.
**To keep your window layout, preferences and dictionaries, rename the directory by hand before the
first launch:**

```bash
mv ~/Library/Application\ Support/Ramus ~/Library/Application\ Support/"Ramus Next"
```

Your models are unaffected: the `.rsf` format did not change, and files stay readable by both the old
and the new build in either direction.

If you would rather not move anything, two JVM properties override the defaults. Pass them as JVM
arguments, not application arguments:

- `-Duser.ramus.options=/path/to/dir` pins the settings directory to an explicit path.
- `-Duser.ramus.application.name=Ramus` restores the old name everywhere it is derived, including the
  settings directory and the window titles.

## Copyright and License

Ramus Next is free software, released under the [GNU General Public License, version 3](https://www.gnu.org/licenses/gpl-3.0.en.html). The full text is in [LICENSE](LICENSE).

- Copyright (C) 2005-2025 Vitaliy Yakovchuk, Oleksiy Chizhevskiy - original Ramus.
- macOS version modifications by [Vladislav Pavlik](https://github.com/Inv1x).
- Copyright (C) 2026 Stanislav Vinokur - Ramus Next.

Ramus Next adds to the original copyright notices; it does not replace them. Bundled third-party components retain their own licenses, listed in the application's About > Credits tab.

## What’s new in 2.0.2

- macOS app bundle and DMG packaging via Gradle + jpackage.
- Proper Dock icon and Info.plist; icons are sourced from `packaging/macos/AppIcon.appiconset` and converted to `.icns` during build (uses macOS `sips`/`iconutil`; falls back to `dwebp` if needed).
- Uses the macOS system menu bar (`apple.laf.useScreenMenuBar=true`).
- macOS keyboard shortcuts use the Command key (⌘) via the platform menu shortcut mask (e.g., ⌘S, ⌘O, ⌘Z, ⌘⇧S, etc.).
- Standalone distribution: bundles a Java runtime. Optionally uses `jlink` to create a minimized runtime; falls back to bundling the full JDK if `jlink` isn’t available.
- Modernized build/toolchain: project compiles for Java 17 (upstream used Java 8) and builds with Gradle 8.5. Supported build JDKs are 17-21; packaging targets JDK 21 for the bundled runtime.
