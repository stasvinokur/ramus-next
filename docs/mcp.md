# Giving an AI agent access to a model

Ramus Next ships an [MCP](https://modelcontextprotocol.io) server. An agent can read your
models, answer questions about them, look at the diagrams, edit the catalogs, draw
activities and arrows — and choose, create and delete the model files itself.

It runs as its own process and talks over standard input and output, which is what every MCP
client expects. The application does not have to be running — or even open.

## Setting it up

The installer puts the server beside the application, with its own copy of Java. Nothing
else has to be installed.

| Platform | Command |
|---|---|
| macOS | `/Applications/RamusNext.app/Contents/MacOS/ramus-mcp` |
| Windows | `C:\Program Files\Ramus Next\ramus-mcp.exe` |

In Claude Desktop, add it to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "ramus": {
      "command": "/Applications/RamusNext.app/Contents/MacOS/ramus-mcp"
    }
  }
}
```

In Claude Code, add it for every project rather than just the current one — models live
wherever you keep them, not in a repository:

```bash
claude mcp add --scope user ramus /Applications/RamusNext.app/Contents/MacOS/ramus-mcp
```

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

No file is named, and that is deliberate: the agent finds models with `list_files` and opens
one with `open_model`. Name a file anyway if you want it open from the start —
`"args": ["/Users/you/models/enterprise.rsf"]` — everything else still works the same.

Add `--read-only` when you want the agent to look but not touch. The tools that change
anything are then not offered at all, rather than offered and refused: 12 tools instead of
27.

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
  original alone.
- **`close_model`**, **`delete_model`**.

### Catalogs

- **`list_catalogs`** — the catalogs in the file, how many elements each holds, and their
  attributes with types. The place to start.
- **`list_elements`** — the elements of one catalog with their values, paged.
- **`get_element`** — one element in full.

### Diagrams

- **`list_models`** — the IDEF0 and DFD models in the file.
- **`get_function_tree`** — the activity tree with IDEF0 codes: A0, A1, A11, and so on.
- **`get_diagram`** — one diagram: the child activities, and for each of them the arrows
  grouped by role — input, control, mechanism, output.
- **`render_diagram`** — the same diagram as a PNG, laid out as the modeller drew it. Use it
  when the arrangement matters and not just the names.

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
  it is written in.
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
one somewhere else, and `font_size` to change how big it is written; the size is stored in the
file, so the diagram looks the same on a machine whose settings differ.

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
   never overwritten.
2. **A save that cannot half-finish.** The new model is written to a temporary file first and
   only takes the real name once it is complete. A full disk leaves the model that was there.
3. **It refuses to write while you have the model open.** Checked every time a model is
   opened, not only at startup. A `.rsf` file is never locked, only
   the session beside it — so two things holding one model each keep their own, and whichever
   saves last silently discards the other's work. If the model is open in Ramus Next, the
   write tools say so and do nothing.
4. **One tool call, one Undo.** Each change runs in a single transaction, so what an agent
   did appears in the application as one step and reverses in one press.

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
