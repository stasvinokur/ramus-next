# Writing a report

A report walks your model and prints what it finds. You describe what to walk with a
**query**: a chain of words separated by dots, starting from the name of a catalog.

```
Documents.Inputs.Name
```

That reads: take every element of the catalog named `Documents`, find the activities each
one is an input to, and print their names.

This page is written from the code. Where it disagrees with the help document shipped in
`dest/doc/en/Ramus-Help.odt`, the code is right and the difference is called out — that
document predates a rename and has been wrong for years, which is why the sample reports
that ship with the application do not work. See [What is broken](#what-is-broken).

## Before anything else: set the model

Every IDEF0 keyword refuses to run until the report knows which model to walk. The report
has an attribute the editor labels **Model**; until it is set, the report fails with
*"Base catalog for the report is not set"*.

Its value is the model's name, or several names joined with `|`, or the literal
`[ALL MODELS]`.

## The shape of a query

- Words are separated by `.`, and the **first word is always a catalog name** — the base of
  the query, not a keyword.
- Every word after the first is either a keyword from the tables below, the name of one of
  your own catalogs, the name of a matrix projection, or the name of an attribute.
- **Your own names win.** If you have an attribute called `Name`, then `Name` in a query
  means your attribute, not the built-in keyword. This is worth knowing before you name a
  catalog `Inputs`.
- A query on a **table** may be written bare or in brackets. A query in a **label** or in a
  **table column** must be bracketed: `[Documents.Name]`.
- A column's query is resolved relative to its table's query and must start with the same
  first word.
- To print a literal `[`, `]`, `<`, `>` or `\` in report text, escape it with a backslash:
  `\[`, `\]`, `\<`, `\>`, `\\`.

## Keywords

Each keyword takes a set of things and returns another set, so the type of what you have so
far decides what you may write next.

### After an activity

| Keyword | Returns |
|---|---|
| `Inputs` | the inputs of the activity |
| `Outputs` | the outputs |
| `Controls` | the controls |
| `Mechanisms` | the mechanisms |
| `InputsControls`, `InputsMechanisms`, `InputsControlsMechanisms` | the corresponding unions |
| `Owners` | the owners of the activity |

### After a stream

The same seven arrow keywords, read the other way round — from a stream to the activities it
is attached to — plus:

| Keyword | Returns |
|---|---|
| `Catalogs` | every element of any catalog linked to the stream |
| *a catalog name* | only the linked elements belonging to that catalog |

**The arrow keywords return only activities that have no decomposition.** That is deliberate:
a report about "what feeds this process" wants the leaves, not the boxes above them. When you
want every activity regardless, use the `All…` forms — `AllInputs`, `AllOutputs`,
`AllControls`, `AllMechanisms` and their unions.

### After one of your own catalogs

The seven arrow keywords again — the activities reached through the streams this element is
linked to — plus `Owners`, `Roles`, the name of a matrix projection, and the name of a
"catalog element" attribute.

### Every keyword, in both languages

| Query keyword (English) | Query keyword (Russian) |
|---|---|
| `Inputs` | `Входы` |
| `Outputs` | `Выходы` |
| `Controls` | `Управление` |
| `Mechanisms` | `Механизмы` |
| `InputsControls` | `ВходыУправление` |
| `InputsMechanisms` | `ВходыМеханизмы` |
| `InputsControlsMechanisms` | `ВходыУправлениеМеханизмы` |
| `AllInputs` | `ВсеВходы` |
| `AllOutputs` | `ВсеВыходы` |
| `AllControls` | `ВсеУправление` |
| `AllMechanisms` | `ВсеМеханизмы` |
| `AllInputsControls` | `ВсеВходыУправление` |
| `AllInputsMechanisms` | `ВсеВходыМеханизмы` |
| `AllInputsControlsMechanisms` | `ВсеВходыУправлениеМеханизмы` |
| `Catalogs` | `Классификаторы` |
| `Owners` | `Собственники` |
| `Roles` | `Роли` |
| `Streams` | `Потоки` |
| `Name` | `Название` |
| `Text` | `Текст` |
| `CodeIDEF0` | *(English only — see below)* |

## Queries are not tied to your interface language

This surprises people, in both directions.

The parser loads the English, Russian and German keyword files **all at once** and merges
them into a single table. So a query written as `Входы` keeps working on an English
interface, and `Inputs` keeps working on a Russian one. You never have to translate a report
because someone changed the interface language.

The two consequences:

- **`CodeIDEF0` has no Russian spelling.** It is defined in the English file only. A query
  written as `КодIDEF0` has never parsed and never will; write `CodeIDEF0` whatever language
  you work in.
- **Ukrainian queries no longer parse.** The Ukrainian locale was removed in this fork, and
  its keyword file went with it. A report written with Ukrainian keywords must be rewritten.

## The three kinds of report

| Type | What it is | Use it when |
|---|---|---|
| **XML** | The visual editor: labels, tables and columns, each with a query. | Almost always. Start here. |
| **JSSP** | A page of HTML with JavaScript embedded in it, run at generation time — the report is a program. | The layout depends on the data in a way tables cannot express. |
| **JSSP DocBook** | The same engine, without the HTML fix-ups, so the output can be DocBook. | You are feeding a publishing pipeline. |

JSSP runs on Rhino, which this fork bundles because the JDK removed Nashorn — before that,
every JSSP report threw on first use.

## What is broken

Stated plainly, because you will hit these before you hit anything else.

**The shipped sample reports do not run.** Verified by opening the files:

- `dest/doc/en/Enterprise activity.rsf` — both of its reports use `.Qualifiers`, nine times
  between them. The keyword is `Catalogs`. `Qualifiers` is the *internal* name, and the help
  document below documents that internal name rather than the one you type.
- `dest/doc/ru/Model example.rsf` and `dest/doc/ru/Пример модели.rsf` — three report streams
  use `КодIDEF0`, which, as above, is not a keyword in any language.

Replace `Qualifiers` with `Catalogs` and `КодIDEF0` with `CodeIDEF0` and they work.

**A failed query looks like an empty report, not like an error.** If a report comes out
blank, suspect the query first: a word the parser does not recognise is taken for a catalog
name, and a catalog that does not exist simply matches nothing.

## The original documentation

The authors' own specification ships with the application:

- `dest/doc/en/Ramus-Help.odt` — chapter 4.4 covers report attributes, labels, tables and the
  keyword tables. Accurate except for `Qualifiers`/`Catalogs`.
- `dest/doc/ru/Формирование отчётов с помощью технологии JavaScript Server Pages.odt` and
  `dest/doc/ru/Технология JSSP.pdf` — JSSP in depth, in Russian.
- `dest/doc/ru/Работа с формулами.odt` — the formula attribute, which is a different feature
  that also runs JavaScript.
