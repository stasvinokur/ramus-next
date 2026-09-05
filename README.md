# Ramus Next

**Java-based IDEF0 & DFD Modeler**

Maintained by [Stanislav Vinokur](https://github.com/stasvinokur).

Ramus Next continues [Ramus](https://ramussoftware.com/), created by Vitaliy Yakovchuk and Oleksiy Chizhevskiy (2005-2025), with macOS packaging and integration work by [Vladislav Pavlik](https://github.com/Inv1x). Released under the [GNU General Public License, version 3](https://www.gnu.org/licenses/gpl-3.0.en.html).

<img width="1792" alt="Screenshot 2019-11-18 at 11 14 26" src="https://user-images.githubusercontent.com/2261228/69039713-23c56d00-09f5-11ea-99c5-b6714efe3037.png">

<img width="1792" alt="Screenshot 2019-11-18 at 11 14 59" src="https://user-images.githubusercontent.com/2261228/69039723-27f18a80-09f5-11ea-9a8d-508069ce7bbd.png">

---

## How to Start the Application

### Step 1: Install JDK

Install any JDK **21 or newer**. [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) is a good default, but the build is not fussy: Gradle runs on whatever JDK it finds, and the JDK that actually compiles the code is provisioned automatically.

> The build no longer depends on which JDK you happen to have. A Gradle toolchain pins compilation to Java 21 and `options.release = 17` pins the bytecode and the visible API to Java 17, so the output is identical regardless of the JVM Gradle runs on. If the toolchain JDK is missing, Gradle downloads it.

### Step 2: Run the Application

In the console, navigate to the project folder and run:

```bash
./gradlew :local-client:runLocal
```

## Installers

Ramus Next ships three installers, each with a bundled Java runtime, so nothing has to be installed
first:

| Platform | Artifact | Built on |
|---|---|---|
| macOS, Apple Silicon | `RamusNext-arm64.dmg` | `macos-latest` |
| macOS, Intel | `RamusNext-x86_64.dmg` | `macos-15-intel` |
| Windows 64-bit | `RamusNext-x64.msi` | `windows-latest` |

There are two macOS builds because the bundled runtime is native code and `jpackage` cannot produce a
universal bundle: Rosetta translates x86_64 to arm64 and not the other way round, so the Apple Silicon
DMG does not start on an Intel Mac at all.

All three are built by [`.github/workflows/build.yml`](.github/workflows/build.yml) and attached to the
run as artifacts. `jpackage` only ever builds for the operating system it is running on, which is why
the MSI cannot be produced from a Mac and CI is not optional here.

The upstream client/server modules, the Java Web Start descriptors and the NSIS/IzPack installers have
been removed: none of them reached any distributable, and the Windows installer had not been buildable
for years. The MSI replaces it, keeping what it did - a per-machine install, a Start menu entry and the
`.rsf` file association - and dropping the hand-written installer script.

Download: the latest installers are available in this repository's GitHub Releases section.

## Requirements (packaging)

- Any JDK **21 or newer** (not a JRE). The JDK used for packaging - it supplies `jdeps`, `jlink` and `jpackage`, and becomes the runtime inside the installer - is resolved through a Gradle toolchain and downloaded if absent.
- For the DMG: macOS with the preinstalled `sips` and `iconutil`. Nothing else - the icon sources under `packaging/macos/AppIcon.appiconset` are plain PNGs.
- For the MSI: Windows with **WiX Toolset 3.x** on the `PATH`. Version 3 specifically: `jpackage` before JDK 24 invokes `candle.exe` and `light.exe` by name, and WiX 4 and 5 do not provide them. The Windows icon is the committed `packaging/windows/RamusNext.ico`, not generated, because `sips` exists only on macOS.

Tip: This project supports local overrides without changing your shell’s `JAVA_HOME`.

## Quick Start

1) Build a macOS .app for quick testing

```
./gradlew :local-client:createMacApp
open local-client/build/mac-app/RamusNext.app
```

2) Build a standalone DMG (recommended, macOS only)

```
./gradlew :local-client:macDmg
open dest/macos
```

3) Build a Windows MSI (Windows only)

```
./gradlew :local-client:windowsMsi
```

Outputs `dest/windows/Ramus Next-2.0.2.msi`: a per-machine install into `Program Files`, with a Start
menu entry and the `.rsf` association registered.

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

`gradle.properties` in the repository root holds the three JDK settings, which are deliberately separate:

| Property | Meaning |
|---|---|
| `javaToolchainVersion` | which JDK runs `javac` |
| `javaRelease` | the bytecode level and the JDK API `javac` is allowed to see |
| `packagingJdkVersion` | supplies `jdeps`/`jlink`/`jpackage`, and is the runtime bundled into the installer |

Only the last one is visible to users. Override any of them with `-P` on the command line.

## Build Tasks Summary

- `:local-client:createMacApp`
  - Creates a dev `.app` under `local-client/build/mac-app/RamusNext.app`.
  - Uses a generated `.icns` and sets Dock icon flags for a native look.

- `:local-client:macDmg`
  - Full packaging pipeline: generates `.icns` → optional `jlink` runtime → `jpackage` DMG.
  - Outputs to `dest/macos/`.
  - If `jlink` isn’t available, it automatically bundles the full JDK at `packagingJavaHome`.

- `:local-client:windowsMsi`
  - Full packaging pipeline on Windows: `jlink` runtime → `jpackage` MSI.
  - Outputs to `dest/windows/`. Fails rather than skips when run anywhere but Windows, because
    there is no other way to produce the artifact.

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

Ramus Next adds to the original copyright notices; it does not replace them.