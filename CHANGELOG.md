# Changelog

## 3.1.1

Three things a user hit on the ordinary path through 3.1.0 - open a model, edit its
properties, connect an agent - and one of them had been there since long before this fork.

### Fixed

- **Double-clicking a model raised a second window that then went away by itself.** The editor
  opened correctly, but the "create a new file / open an existing one" chooser appeared beside
  it and disappeared once the model was up. Not a race: the application decided whether to ask
  from its command-line arguments alone, and macOS never passes a double-clicked document as an
  argument - it sends an Apple event, which arrives through a callback. So on a cold start the
  answer was always "there is nothing to open", every single time, and taking the window away
  afterwards was a workaround rather than a fix. It is no longer shown in the first place, and
  the decision is not a timer: the request has provably already been delivered by the moment it
  is made. If the document then fails to open, the chooser appears after all, so a failed open
  never leaves a running application with no window.
- **With "use this as default and do not ask again" set, a double-click opened two models.**
  The chooser performed its remembered choice from its own constructor, so building a window
  nobody had decided to show opened the previous file - or created an empty model - alongside
  the one that was actually asked for. Worse, if the remembered file had since been deleted,
  merely constructing that window ran into a modal error dialog during startup.
- **Changing the author, the project or a diagram's dates did not show until the application
  was restarted.** Two faults sharing one branch. The frame around a diagram is drawn by two
  panels sitting BESIDE the drawing area rather than inside it, so repainting the diagram could
  never reach them - and nothing in the application had ever asked them to paint. Separately, a
  change to the model's own properties reached only the context diagram, because it was matched
  against the sheet that happened to be open, while PROJECT, USED AT, the reader table and an
  inherited author are printed on every sheet. The status marker and the revision stamp were
  handled by no branch at all. All of it now refreshes as it is changed, on every sheet that
  prints it and on no sheet that does not - including through Undo. Two more staleness bugs
  went with it: the context thumbnail after a box moves, and the TITLE cell after a rename,
  which the tab updated and the frame did not.

### Documentation

- **The MCP server is documented for more than one client.** There is now a section each for
  Claude Desktop, Claude Code, Codex CLI, Gemini CLI and the editors, plus one for any client
  that does not exist yet - which needs only the fact that this is an ordinary stdio server and
  its single line of usage. Windows is no longer a footnote: every section carries both paths,
  with the two traps named, since JSON and TOML both read a lone backslash as an escape and the
  installed path contains two spaces. And it says plainly that `Ramus Next.exe` is not the
  command - that one is the application, with nothing attached to its standard input, and a
  client pointed at it hangs.

### Build

- **The build checks the command the documentation hands out.** Nothing ever had: the MCP
  launcher appeared in no workflow at all, so it could have gone missing from an installer
  without a single test noticing. Both installers are now checked for it, for a configuration
  file whose options have not been split into fragments, and for a real answer to `initialize`
  and `tools/list` over standard input and output. That last check is the only place the
  documented Windows command is actually run.

## 3.1.0

Long-standing complaints from the original Ramus issue tracker, closed here. All 31 of
those issues were read and checked against this code; fourteen needed nothing, because this
fork had already closed them or the feature had been there all along.

### Fixed

- **Exporting diagrams as pictures could freeze the application and leave a picture that
  will not open.** The export ran on a thread that caught only one kind of failure, so
  anything else killed it silently: the progress window stayed up, the export dialog was
  never released, and the application looked hung. Separately, the file was created before
  anything had been drawn into it, so a failure left a zero-byte file with a valid
  extension - and re-exporting after a failure **destroyed the previous good picture**,
  because the old one was emptied before the new render was attempted. The picture is now
  written beside the target and only takes its name once it is complete, so a failure leaves
  the previous export untouched and tells you what went wrong.
- **Arrows stopped being carried into child diagrams, permanently.** One exception during a
  diagram switch left a re-entrancy flag stuck, after which every later navigation quietly
  did nothing at all - which is why the workaround people found was to decompose again and
  get a fresh panel.
- **Reports could come back empty with no error.** Two separate causes. Links attached
  through the arrow properties dialog carry a hidden status string, and the report looked
  them up without it, so they were simply not found; the dialog stamps that string on every
  link even when you type nothing, so this was the common case rather than a corner one. And
  an activity whose decomposition held only an external reference was mistaken for a
  decomposed one and dropped from every report except the All… ones.
- **IDL export wrote coordinates in whatever format the machine's language used.** A
  comma-decimal locale produced `(0,123;0,456)` where another machine produced
  `(0.123,0.456)`. Ramus read both, so this was invisible until the file reached any other
  tool. Export is now identical everywhere; reading still accepts both, so old files open.
- **57 corrections to the interface text - this time in Russian.** The previous release fixed
  the English. Nothing had ever proofread the Russian, and since this fork makes Russian the
  default for everyone, its mistakes were what everyone saw: two different settings shared
  one label, a chart type was in the wrong gender, one string was still Ukrainian, and a
  font in italic printed as `Arial 12 null`. Six missing entries were added and three
  lookups pointed at the wrong place - one of which left a dialog with no title at all.
- A menu whose text was missing came out blank rather than falling back to something
  readable, and at the top level it could stop the menu bar from being built at all.

### macOS

- **Double-clicking a model in Finder now opens it.** The application never told macOS it
  could open `.rsf` files, so a double-click did nothing, while the same thing worked on
  Windows.
- **Quit from the application menu no longer discards unsaved work.** It now goes through
  exactly the same path as closing the window, so it asks about unsaved changes and shuts
  the model down cleanly.
- The window icon is sharp on a high-resolution display. It was a single 32-pixel bitmap.

### AI agents can read and edit a model

The installer now carries a second program beside the application: an
[MCP](https://modelcontextprotocol.io) server, with its own copy of Java, that serves Ramus
models to an AI agent. Point Claude at it and it can list the catalogs and their elements,
walk the IDEF0 tree with its codes, read a diagram's arrows by role, run report queries in
the language the report editor uses, look at a rendered diagram, edit the catalogs, and
**build the diagrams themselves** - a context diagram with its border arrows, and the
decompositions below it. See [docs/mcp.md](docs/mcp.md).

**The agent chooses the file.** No model is named in the configuration: it lists what is in a
directory, opens one, creates new ones - IDEF0, DFD or DFDS - saves a copy elsewhere, and
deletes. One model is open at a time and `open_model` switches; switching writes out anything
unsaved first and says which file it wrote. There is no sandbox, and deletion is real - the
one check is that the target really is a Ramus model, read as an archive rather than trusted
by its extension.

**It draws.** An activity or an arrow is not only a row - it also carries the coordinates and
geometry that the drawing panel produces, and writing those from outside is the way to end up
with a model that opens with an empty diagram. So nothing here writes them: the server runs
the application's own drawing panel without a window, exactly as the rendering already does,
and lets it produce the geometry. Boxes are sized to fit their names and laid out on the
IDEF0 diagonal. Decomposing behaves as it does in the application, including the part that
is easy to get wrong: the arrows of the diagram above arrive with one end loose, and drawing
one of them by name connects that loose end rather than adding a second arrow with the same
name.

A model built this way also opens on its diagram, which took finding: the application does
not decide what to show from the model at all - a file carries a list of the diagrams that
were open when it was last saved and replays it. A file that had never been through the
application had no such list, so a model that was correct in every field opened on an empty
canvas. Saving now leaves that list behind; a file the application saved keeps the tabs the
person had open.

**Arrows are named the way a person names them.** Each one gets its name written clear of its
own line and joined back to it by the zig-zag the notation asks for. That reads like
decoration and is not: an arrow drawn from outside had no label object at all, so nothing
about it was stored, and the panel manufactured one at the middle of the line on every open -
three arrows into one side of a box printed three names in one place. The size is written into
the file too, because a label takes its font from the sector and is re-measured and re-centred
on open, so a layout computed on one machine only survives on another if the file says what it
was computed for. Sizes can be set for boxes and for names.

**One flow reaching several activities is one arrow that forks.** Naming an arrow as an end of
add_arrow branches it through a crosspoint, so the segments share a single stream and a report
sees one thing arriving in four places - which is what the diagram means. Several arrows with
equal names look the same and are a different model.

**The title block can be filled in**: project, author, dates, purpose, readers and the status
marker, split as the application splits them - the model's own on one side, each sheet's on
the other. `rename_model` renames a model where the name really lives - the row the Models
panel reads, which is the only one of its two homes that carries to the other.

**The drawing can be read back, not only looked at.** `get_diagram` built the drawing panel,
walked every arrow and kept two strings from each, so an agent that had just drawn a diagram
could not find out what it looked like: it had to carry its own idea of the page, and that idea
was wrong, because nothing anywhere said the page is 800 by 444 with a seven-unit margin.
`include_geometry` reports the page, the rectangle of every box, and the route, label and tilde
of every arrow, in the same units the drawing tools take. `list_arrows` gives a row per
segment with both ends said in full - the activity and role, or the side of the page, or the
junction - and a group number, so one flow forking to four activities reads as one flow rather
than four arrows that share a name. On a context diagram every arrow has one end on the frame,
and those ends used to be dropped outright, which left the whole sheet as four lists of names
with no directions in them.

**The context diagram can be asked for.** It belongs to an activity that is not a node of the
function tree, so its id was a number nothing had ever reported - and the commonest request of
all was the one that could not be made. `get_function_tree` now carries it as a field, and
every tool that takes a sheet defaults to it.

**A failure says what failed.** An error reached the agent as whatever `getMessage` happened to
return, which for a wrapped exception is the cause's `toString` - so an unreadable date arrived
as `java.sql.SQLException: ...` and there was nothing to be done with it. The chain is now
walked to the deepest thing that said something, the class name is dropped, and the tool that
refused is named.

**A script can be run twice.** `create_model` and `save_model_as` take `overwrite`, with three
conditions that are not optional: the target must be a Ramus model, it must not be open in the
application, and a copy of it is kept. Before this the only way past "already exists" was to
delete the file by hand outside the server. `save_model_as` into the path already open is now a
save, rather than a save-as that reported the file it had just written over as
`original_untouched`.

What it does not do is route arrows. Lines cross on a busy diagram, and the label rules are
this server's own rules of thumb, since nothing in Ramus lays labels out - a very long name or
five arrows down one side will still want a hand. Structurally everything is where it was put,
every arrow attached to the side it was given; a diagram straight from an agent is a good
draft rather than a finished drawing.

**It stays off your screen.** Reading or drawing a diagram means building the application's own
drawing panel, and that starts AppKit - so the first such tool call raised a Dock icon and took
the focus, pulling whoever was working out of a fullscreen window. The server asks macOS to
treat it as an accessory instead, which is what a process talking over a pipe should always
have been.

**It says which build it is.** The version is a constant in the source, so two builds a day
apart both call themselves 3.1.0 - and when an update is installed while a session is running,
that session goes on talking to the old process with half the tools missing and nothing saying
why. `serverInfo` and the instructions now carry the build stamp, and the instructions say what
to do about a short tool list: start a new session, because re-listing returns the same list. It
also stopped advertising a `listChanged` notification it never sent.

Four things protect the model. A backup is written beside it before the first change, not
before the first save - and not at all for a model the session created itself, which used to
leave a `.backup` of an empty model in somebody's repository. A save goes through a temporary file, so a full disk leaves the model
that was there. Writing is refused while the same model is open in the application, because
a `.rsf` is never locked and the last save would otherwise win silently. And each change is
one transaction, so what an agent did is one press of Undo.

### Documentation

- **How an agent talks to a model is written down** in [docs/mcp.md](docs/mcp.md).
- **How to write a report is written down**, in [docs/report-queries.md](docs/report-queries.md):
  the query language, the complete keyword table in English and Russian, and the fact that
  keywords are not tied to the interface language. It also explains why the sample reports
  that ship with the application do not run - both English ones use a keyword that was
  renamed years ago, and three Russian ones use a Cyrillic spelling that has never existed.

### Housekeeping

- 817 lines of menu code that nothing could reach were removed from one file, along with a
  class and a resource bundle that nothing loaded.


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

**Dates were disappearing from older models — and stopping newer ones from opening at all.** Java 9
changed its locale data, and with it the format this application had always used to write dates. Files
saved by every released version of Ramus say `9/7/24 9:21 AM`; a modern Java expects
`9/7/24, 9:21 AM`. The parse failure was caught and ignored, so the date was not reported as bad —
**it was silently left empty**. On a real model that was seven dates lost behind fourteen swallowed
errors.

Then Java 20 moved again. Its locale data writes a **narrow no-break space** before AM and PM instead
of an ordinary space — the same three characters to look at, a different character to a parser. So a
model saved by Ramus 2.0.2 on any current Java carries a date this application could not read, and
what a person saw was a model that would not open: not a lost field, the whole file. It was invisible
in development, because the JDK used there is newer still and tolerates the mismatch, and fatal on
the Java that ships in the installer. Reading now normalises every kind of no-break space before it
parses, so all three spellings open; writing stays in the plain-ASCII one, so older builds can still
read what this one saves. An unparseable date raises an error that names the value and says what to
do about it.

### Fixed

- **Opening a model announced that other models were being restored.** Double-clicking one file
  raised a "Restoring session" window naming a different one, once per session left behind by an
  earlier run. The indicator went up before anything had looked inside, and a session with no
  journals in it is deleted without a word - so what was announced was exactly the sessions that
  had nothing to recover. The window is now raised only once the answer to "is there anything
  here" is yes. Two things found on the same path: the session lock was read with `available()`
  as a length and the result of `read()` discarded, so a short read would have put NUL bytes
  inside a recovered file name; and its write is now truncating and self-contained rather than
  relying on each caller to have made it so.
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