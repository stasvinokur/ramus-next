# Changelog

## 3.0.0

The first release of Ramus Next, a continuation of [Ramus](https://ramussoftware.com/) by Vitaliy
Yakovchuk and Oleksiy Chizhevskiy. Same GPL-3 licence, same `.rsf` files, same IDEF0 and DFD
modelling — brought onto a supported Java, given installers that carry their own runtime, and with a
long list of things that were quietly broken put right.

The version jumps from 2.0.2 to 3.0.0 because the work is substantial, not because the file format
changed. It did not. Models written by Ramus 2.x open here, and models written here open in Ramus 2.x.

### Your files are safer than they were

**The database engine was 14 years old.** Moving H2 from 1.3.163 to 2.3.232 could have destroyed
model files in three separate ways, each found and closed before the upgrade landed:

- Model files recorded whatever the driver happened to call each column type. H2 2.x renames four of
  the eight types this schema uses, so a plain driver swap would have produced files that **neither
  the new build nor the old one could open**. The type is now written from a stable token, and the
  output is byte-for-byte what the old driver produced.
- `CREATE SEQUENCE … START` is a syntax error on H2 2.x, and the whole schema is one statement — the
  first rejection abandons everything after it. Measured on the new driver with the old schema: **3
  tables and 0 sequences instead of 20 and 5**. One of the eight occurrences was assembled at runtime
  from a value read out of the model, so it threw on opening any real file.
- `VALUE` became a reserved word, and it is the column name every simple attribute uses.

Checked in all four directions on a real 53-table model, including against a pre-upgrade build.

**Dates were disappearing from older models.** Java 9 changed its locale data, and with it the format
this application had always used to write dates. Files saved by every released version of Ramus say
`9/7/24 9:21 AM`; a modern Java expects `9/7/24, 9:21 AM`. The parse failure was caught and ignored,
so the date was not reported as bad — **it was silently left empty**. On a real model that was seven
dates lost behind fourteen swallowed errors. Reading now accepts both spellings, writing stays in the
old one so older builds can still read what this one saves, and an unparseable date raises an error
naming the value instead of vanishing.

### Fixed

- **Excel import failed with no message at all.** Choosing an `.xlsx` did nothing — no dialog, no
  error. Worse, an import that failed partway could **commit half a catalogue into your model**; it
  now rolls back and keeps the dialog open so the column mapping is not lost. Imported dates also
  arrived as raw numbers, because both date branches were unreachable.
- **Image export picked the format by its position in the list.** An unrecognised value made the
  writer do nothing and return successfully, so you got a **zero-byte file with the right extension
  and a success message**. The size labels also lied: the entry reading 904x601 wrote 905x700.
- **There was no JavaScript engine.** Nashorn left the JDK in version 15, so every scripted formula
  and every JSSP report had been throwing on first use. Rhino is now bundled. `Thread.stop`, which
  Java 20 made throw unconditionally, is gone from three places — switching between the report editor
  tabs twice was enough to hit it.
- **Clicks did not reach the fields of the new-project wizard** for about a minute. A leftover
  progress window, 292x16 pixels at the centre of the screen, sat exactly over the three text fields
  and stopped short of the buttons — which is why the buttons worked, why typing worked, and why
  switching applications fixed it.
- The workspace switcher truncated its own labels to "Cata…", "Proj…", "Diag…" by counting
  characters rather than pixels, and the About window cut its first line in half.
- Toolbars were accidentally draggable, so their drag handles looked like stray separators.

### Interface

- One consistent look on macOS and Windows, using FlatLaf. Panel headers no longer paint themselves
  with the menu highlight colour.
- All 113 interface icons are vector now, so they stay sharp on high-resolution displays. 109 come
  from Fluent UI System Icons; the four IDEF0 and DFD notation marks — the arrow tool, the squiggle,
  the external reference and the data store — are drawn for this application, because no general icon
  set contains them.
- About is a small panel with the icon, name, version and attribution, instead of a 600x380 window
  with four tabs.
- **93 English strings were wrong and are fixed.** Six began with a Cyrillic character that looks
  identical to a Latin one. Nine printed the internal identifier instead of text — the interface
  literally showed `next_page` and `cant_remove_rows`. Three error messages lost characters to a
  formatting rule. A German locale got **an interface with no text on it at all**, from a translation
  file with 625 keys and 625 empty values.

### Installation

Three installers, each carrying its own Java runtime — nothing has to be installed first:

| Platform | File |
|---|---|
| macOS, Apple Silicon | `RamusNext-arm64.dmg` |
| macOS, Intel | `RamusNext-x86_64.dmg` |
| Windows 64-bit | `RamusNext-x64.msi` |

There are two macOS builds because the bundled runtime is native code and Rosetta only translates
one way: the Apple Silicon disk image does not start on an Intel Mac.

**Both installers are unsigned.** macOS Gatekeeper and Windows SmartScreen will warn about them.

### If you are coming from Ramus

- **Your settings do not move automatically.** The application now stores them under its own name, so
  the old directory is left alone and Ramus Next starts with defaults — window layout, preferences
  and dictionaries included. To keep them, rename the directory before the first launch:
  `~/Library/Application Support/Ramus` → `Ramus Next` on macOS,
  `%APPDATA%\Ramussoft\Ramus` → `Ramussoft\Ramus Next` on Windows. Your models are not affected.
- **The interface is Russian by default**, whatever your system language says. Preferences still
  wins, and now tells you the change takes effect after a restart.
- **The built-in help is gone**, along with F1. It had not been maintained.
- **Ukrainian is gone.** Reports whose queries were written with Ukrainian keywords will no longer
  parse.
- **The client/server mode and Java Web Start are gone.** Neither had been buildable for years.
- Bar charts are flat: the 3D chart API was removed by the charting library.
- `.xlsx` still is not supported. What changed is that the refusal is now visible instead of silent.

### Credits

Ramus Next is free software under the [GNU General Public License, version 3](LICENSE).

- Copyright © 2005–2025 Vitaliy Yakovchuk, Oleksiy Chizhevskiy — original Ramus.
- macOS version modifications by [Vladislav Pavlik](https://github.com/Inv1x).
- Copyright © 2026 [Stanislav Vinokur](https://github.com/stasvinokur) — Ramus Next.

Ramus Next adds to the original copyright notices; it does not replace them.