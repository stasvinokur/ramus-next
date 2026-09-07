# Giving an AI agent access to a model

Ramus Next ships an [MCP](https://modelcontextprotocol.io) server. An agent can read your
models, answer questions about them, look at the diagrams, edit the catalogs, draw
activities and arrows — and choose, create and delete the model files itself.

It runs as its own process and talks over standard input and output, which is what every MCP
client expects. The application does not have to be running — or even open.

## Setting it up

The installer puts the server beside the application, with its own copy of Java. Nothing else
has to be installed — no JDK, no npm, no Python.

| Platform | Command |
|---|---|
| macOS | `/Applications/RamusNext.app/Contents/MacOS/ramus-mcp` |
| Windows | `C:\Program Files\Ramus Next\ramus-mcp.exe` |
| Built yourself | `java -jar mcp-server/build/libs/ramus-mcp.jar` |

Two things about the Windows path, because both bite. The installer lets you choose where to
put the application, so that path is the default rather than a promise — if you changed it,
the command is `ramus-mcp.exe` inside whatever you chose. And `Ramus Next.exe` beside it is
**not** the command: that one is the application, a windowed program with nothing attached to
its standard input, and an MCP client pointed at it will simply hang.

Every client below wants the same three things — a name, the command, and optionally
arguments. Only the file to put them in differs.

### Claude Desktop

`claude_desktop_config.json`, through Settings → Developer → Edit Config:

```json
{
  "mcpServers": {
    "ramus": {
      "command": "/Applications/RamusNext.app/Contents/MacOS/ramus-mcp"
    }
  }
}
```

On Windows the same file, with the backslashes doubled — JSON reads a single one as an escape:

```json
{
  "mcpServers": {
    "ramus": {
      "command": "C:\\Program Files\\Ramus Next\\ramus-mcp.exe"
    }
  }
}
```

### Claude Code

Add it for every project rather than just the current one — models live wherever you keep
them, not in a repository:

```bash
claude mcp add --scope user ramus /Applications/RamusNext.app/Contents/MacOS/ramus-mcp
```

```powershell
claude mcp add --scope user ramus "C:\Program Files\Ramus Next\ramus-mcp.exe"
```

The quotes are not optional on Windows: the path has a space in it twice.

`--scope` decides who gets it, and the default is not the one you want here:

| Scope | Stored in | Applies to |
|---|---|---|
| `local` (default) | `~/.claude.json`, under the project | you, in this project only |
| `project` | `.mcp.json` in the repository | anyone who clones it |
| `user` | `~/.claude.json`, at the top level | you, everywhere |

Passing options to the server needs a `--` first, or the CLI takes them as its own:

```bash
claude mcp add --scope user ramus -- /Applications/RamusNext.app/Contents/MacOS/ramus-mcp --read-only
```

### Codex CLI

`~/.codex/config.toml`:

```toml
[mcp_servers.ramus]
command = "/Applications/RamusNext.app/Contents/MacOS/ramus-mcp"
```

```toml
[mcp_servers.ramus]
command = "C:\\Program Files\\Ramus Next\\ramus-mcp.exe"
```

TOML treats a backslash in a basic string as an escape, exactly as JSON does, so it is doubled
here too. A literal string in single quotes is the other way of saying it:
`command = 'C:\Program Files\Ramus Next\ramus-mcp.exe'`.

Or from the command line, which writes that file for you:

```bash
codex mcp add ramus -- /Applications/RamusNext.app/Contents/MacOS/ramus-mcp
```

Arguments go after the `--` as well: `codex mcp add ramus -- <command> --read-only`. If you
put the server in a project's own `.codex/config.toml` rather than the one in your home
directory, add `trust_level = "trusted"` to the table — Codex ignores project configuration it
does not trust, and the server would silently never start.

### Gemini CLI

`~/.gemini/settings.json` for everywhere, or `.gemini/settings.json` inside a project:

```json
{
  "mcpServers": {
    "ramus": {
      "command": "/Applications/RamusNext.app/Contents/MacOS/ramus-mcp"
    }
  }
}
```

Same doubling on Windows: `"C:\\Program Files\\Ramus Next\\ramus-mcp.exe"`.

Or:

```bash
gemini mcp add -s user ramus /Applications/RamusNext.app/Contents/MacOS/ramus-mcp
```

### VS Code, Cursor, Windsurf and the rest

All of them take the same `mcpServers` object with the same `command` and `args`; what differs
is which file it goes in and which menu writes it — VS Code has `.vscode/mcp.json` and an
**MCP: Add Server** command, Cursor has `~/.cursor/mcp.json` and a panel under Settings, and
so on. Copy the Claude Desktop block above into whichever of those your editor uses. Their
menus move between versions faster than this page can follow, so trust your editor's own
documentation for the file and this page for what goes in it.

### Any other client

There is nothing special to arrange. This is an ordinary stdio MCP server: one executable,
speaking JSON-RPC on its standard input and output, with no port, no daemon and no
configuration file of its own. Anything that can launch a command and talk MCP to it can use
it. The whole contract is:

```
ramus-mcp [model.rsf] [--read-only]
```

Run it in a terminal to see for yourself — it should sit there silently, waiting for input.
That is a working server; anything it prints goes to standard error, so a message there is a
message to you.

```bash
/Applications/RamusNext.app/Contents/MacOS/ramus-mcp
```

```powershell
& "C:\Program Files\Ramus Next\ramus-mcp.exe"
```

### What to put in `args`

No file is named above, and that is deliberate: the agent finds models with `list_files` and
opens one with `open_model`. Name a file anyway if you want it open from the start —
`"args": ["/Users/you/models/enterprise.rsf"]` — everything else still works the same.

Add `--read-only` when you want the agent to look but not touch. The tools that change
anything are then not offered at all, rather than offered and refused: 13 tools instead of
29.

**One model is open at a time.** `open_model` switches. Comparing two means reading one,
then opening the other.

## What it can do

### Files

- **`list_files`** — the `.rsf` files in a directory, with size and date.
- **`current_model`** — which model is open and whether it has unsaved changes.
- **`open_model`** — open a file and make it the one every other tool works on.
- **`create_model`** — a new file with one model in it: a name and a notation, `idef0`,
  `dfd` or `dfds`.
- **`save_model_as`** — write the open model somewhere else and carry on there, leaving the
  original alone. Given the path it is already open at, it is simply a save.
- **`close_model`**, **`delete_model`**.

`create_model` and `save_model_as` refuse a path that already exists unless you pass
`overwrite`. That flag has three conditions and none of them is optional: the target must be a
Ramus model, read as an archive rather than trusted by its extension; it must not be open in
the application, since that session would write the old contents back over the new; and a copy
of what was there is put beside it first. Without the flag a script that builds a model could
only be run once, and the way on was to delete the file by hand outside the server.

### Catalogs

- **`list_catalogs`** — the catalogs in the file, how many elements each holds, and their
  attributes with types. The place to start. `include_system` adds the ones the application
  keeps for itself — `F_MODEL_TREE`, `F_STREAMS` and the rest — which are ordinary catalogs
  once you know their names.
- **`list_elements`** — the elements of one catalog with their values, paged.
- **`get_element`** — one element in full.

### Diagrams

- **`list_models`** — the IDEF0 and DFD models in the file.
- **`get_function_tree`** — the activity tree with IDEF0 codes: A0, A1, A11, and so on.
- **`get_diagram`** — one diagram: the child activities, and for each of them the arrows
  grouped by role — input, control, mechanism, output. Ask for `include_geometry` and it also
  reports the page, the rectangle each box occupies, and the route, label and tilde of every
  arrow.
- **`list_arrows`** — a row per arrow SEGMENT, with both ends said in full: the activity and
  the role there, or the side of the page it runs off, or the junction where it meets the rest
  of its flow. Segments carrying one flow share a group number, so an arrow forking to four
  activities reads as one flow rather than four arrows that happen to share a name — which
  `get_diagram`, grouping by name, cannot tell you.
- **`render_diagram`** — the same diagram as a PNG, laid out as the modeller drew it. Use it
  when the arrangement matters and not just the names.
- **`rename_model`** — the name of a model inside the file, as `list_models` and the
  application's Models panel show it. Not the name of the file; that is `save_model_as`.

**Which diagram a tool means.** A diagram has no id of its own: it is named by the activity it
decomposes. `get_diagram`, `render_diagram`, `list_arrows` and `set_diagram_info` spell that
`activity`, the arrow tools spell it `diagram` and `add_activity` spells it `parent` — three
names for one thing, kept because scripts already use them. All of them are optional, and
leaving one out means the context diagram. `get_function_tree` reports that sheet's id as
`context_diagram`; it is not a node of the tree, because it carries the same code as its only
child and would show A0 twice. Where `activity` appears on `set_activity`, `remove_activity`
and the ends of `add_arrow`, it means a BOX rather than a sheet.

**The page is 800 by 444, with a margin of 7.** So a box or an arrow lives between 7 and 793
across and between 7 and 437 down, in the units `add_activity` takes as `x` and `y`. The margin
is not advice: an arrow told to start at the edge comes back starting at 7, because the panel
substitutes it. `get_diagram` with `include_geometry` reports all three, so nothing has to be
remembered.

### Queries

- **`run_query`** — the report query language, the same one the report editor uses:
  `Документы.Inputs` returns every activity each document is an input to. It is the shortest
  path to most questions, and it is documented in
  [Writing a report](report-queries.md) — worth pointing the agent at.

### Changes

- **`create_element`**, **`update_element`**, **`delete_element`** — editing catalogs.
- **`save`** — writing it back. Nothing touches the file until this is called.

### Drawing

- **`add_activity`** — a box on a diagram. The diagram is the decomposition of whichever
  activity you name as the parent, and it is created when that activity had none. Position
  is optional: without one the boxes step down the diagonal, which is how an IDEF0 diagram is
  read and how the activities get their numbers.
- **`set_activity`** — move it, resize it, rename it, change the size of its text.
- **`add_arrow`** — an arrow with a name. Each end is one of three things: an activity with
  the role the arrow plays there — `{"activity": 12, "role": "input"}` — the edge of the
  page, `{"border": true}`, or an arrow already on the diagram, `{"arrow": "Standard"}`,
  which **branches** it. An arrow leaves an output and arrives at an input, a control or a
  mechanism, so `from` takes the output end; a border end needs no side, it takes the one
  that matches the other end.
- **`set_arrow`** — rename an arrow, move its name, turn its tilde on or off, change the size
  it is written in or the width it wraps at.
- **`remove_arrow`**, **`remove_activity`** — removing a box takes its arrows with it.

Two arrows given the same name carry the same thing. That is not a convenience: one stream
appearing on several diagrams is how a model says the same flow runs through them, and it is
what the report queries follow.

**One flow reaching several activities is a branch, not several arrows.** On one diagram,
`{"arrow": "Standard"}` as an end forks the arrow already there: the segments share one
stream and meet at crosspoints, so a report sees one thing arriving in four places. Four
separate arrows with equal names look the same and mean something else. The mirror case — two
outputs joining into one arrow — is the same call with the arrow named as the `to` end.

**Names are placed for you.** Each arrow gets its name written clear of its own line and
joined to it by the zig-zag the notation asks for — Ramus calls it a tilde. A name beside a
vertical arrow steps along it, an arrow leaving a box is named just outside that box, and a
name that would land on its own box is lifted above it. Give `label_x` and `label_y` to put
one somewhere else, `font_size` to change how big it is written, and `label_width` to say how
wide it may run before it wraps — narrow where the arrows are close together, wider to keep a
long name on one line. The size is stored in the file, so the diagram looks the same on a
machine whose settings differ.

**Decomposing works the way it does in the application.** When you add the first box inside
an activity, the arrows of the diagram above appear here already, each with one end loose at
the edge of the page. Draw an arrow with the name of one of those and it is that stub which
gets connected — not a second arrow with the same name. The two levels stay joined, which is
what makes the model consistent rather than merely similar.

### The title block

- **`set_model_info`** — the part of the frame that belongs to the whole model and shows on
  every diagram of it: the project, the author, what the model is used for, its purpose, the
  readers who have signed it off, and the letter the node codes start with.
- **`set_diagram_info`** — the part that belongs to one sheet: its author, the date it was
  drawn, the date it was last revised, and which of WORKING, DRAFT, RECOMMENDED and
  PUBLICATION the marker sits against.

Call `set_diagram_info` **after** the drawing is finished. Changing a diagram stamps its
revision date with the time of the change — that is what a revision date is, and it is what
the application does too — so a date set before the last arrow is drawn will be replaced.

The node code, the sheet number, the context thumbnail and the row of numbers beside NOTES are
computed or drawn rather than stored, and nothing can set them. The node code follows the
position of the box: leftmost is A1.

**A model an agent builds opens on its diagram.** Ramus Next does not work out what to show
from the model: a file carries a list of the diagrams that were open when it was last saved,
and opening it replays them. A file that has never been through the application has no such
list, so it opens on an empty canvas however complete the model is — which is exactly what a
model built here used to do. Saving now leaves that list behind, pointing at the context
diagram. A file the application has saved keeps its own: those are the tabs a person had
open, and nothing here overwrites them.

## What it deliberately cannot do

**It has no sandbox.** A path is a path: the agent can open, create and delete a model
anywhere you can. `delete_model` is real deletion — no trash, no copy kept. The one thing
between it and the rest of your disk is that it checks the target really is a Ramus model
before removing it, by reading it as an archive rather than by trusting the extension.

**It does not route arrows.** Boxes go on the diagonal and an arrow takes the shortest way the
drawing panel gives it, which on a busy diagram means lines that cross. Names are placed and
kept clear of the boxes, but nothing in Ramus lays labels out — the rules here are this
server's own, and they are rules of thumb: a very long name, or five arrows down one side of
one box, will still want a hand. Everything is in the right place structurally — every arrow
attached to the side it was given, which is what `get_diagram` and the reports read — but a
diagram straight from an agent is a good draft, not a finished drawing.

So: an agent can build a model, restructure your catalogs, fill in attributes and answer
questions about the diagrams. The last tenth of making them look right stays with you.

## Switching models writes them

This is the one behaviour worth knowing before you start. If the open model has changes that
have not been saved and the agent opens another one, **the changes are written to disk
first**. The reply says which file was written, and a well-behaved agent will tell you.

Ask it to call `current_model` before switching if you want to know where you stand, or
start the server `--read-only` if nothing should ever be written.

## What protects the model

Four things, and none of them are optional.

1. **A backup before the first change.** The moment a tool changes anything, `model.rsf` is
   copied to `model.rsf.backup` beside it. Before, not after — so a session that changes
   something and then dies still leaves the model as it was found. An existing backup is
   never overwritten. A model the session created itself is not copied: there is nothing
   there to protect, and the copy was only ever a `.backup` of an empty model left in
   somebody's repository.
2. **A save that cannot half-finish.** The new model is written to a temporary file first and
   only takes the real name once it is complete. A full disk leaves the model that was there.
3. **It refuses to write while you have the model open.** Checked every time a model is
   opened, not only at startup. A `.rsf` file is never locked, only
   the session beside it — so two things holding one model each keep their own, and whichever
   saves last silently discards the other's work. If the model is open in Ramus Next, the
   write tools say so and do nothing.
4. **One tool call, one Undo.** Each change runs in a single transaction, so what an agent
   did appears in the application as one step and reverses in one press.

## After an update

The command does not change, so there is nothing to add or remove: `claude mcp add` and
`claude mcp remove` are for changing the configuration, not for picking up a new build.

What does have to happen is a **new session**. A running server is a running process, and it
has the tools it was built with; installing an update leaves that process untouched and
serving the old set. Asking for the tool list again will not help — there is nothing new for
it to return. This is worth knowing because the symptom is confusing: half the tools missing
with nothing saying why.

To tell which build you are talking to, the server names itself with the build it came from —
`3.1.1 (build 2026-09-07 09:14)` — in `serverInfo` and again in the instructions the agent
reads before it calls anything. Two builds a day apart are both 3.1.1; the timestamp is what
separates them.

## If it does not work

**The agent sees no tools, or the connection drops immediately.** Run the command yourself in
a terminal, with or without a model. It should sit there silently waiting for input — that is
a working server. Anything it prints goes to standard error, so a message there is a
message to you.

**"This model is already open in Ramus Next."** Close the model in the application. Or start
the server with `--read-only`, if you only want to ask questions.

**Nothing was saved.** Changes live in memory until `save` is called — or until the agent
opens another model, which saves them on the way out.

**Something was saved that I did not expect.** That is switching models: see above. The
reply to `open_model` names the file it wrote.

## Building it yourself

```bash
./gradlew :mcp-server:shadowJar
java -jar mcp-server/build/libs/ramus-mcp.jar          # the agent chooses the model
java -jar mcp-server/build/libs/ramus-mcp.jar model.rsf  # or open one straight away
```

That jar carries everything it needs except a Java runtime; any JDK 17 or newer will run it.
