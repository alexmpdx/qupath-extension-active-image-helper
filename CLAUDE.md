# QuPath Active Image Helper

A small QuPath 0.7 extension that makes the currently open image obvious in the
project image list and adds right-click actions for it. Published as
`io.github.alexmpdx:qupath-extension-active-image-helper`, GPL-3.0.

- GitHub: https://github.com/alexmpdx/qupath-extension-active-image-helper
- Current release: **v0.3.1** (October 16, 2026). Version lives in `build.gradle.kts`.
  Note that **v0.3.0 was released without the toolbar icon color feature** — it was
  tagged and published from the version-bump commit before the feature was written,
  so v0.3.1 is the first release that actually contains it. A released version's
  artifacts can't usefully be swapped in place: QuPath's extension manager keys on
  the version number and would never notice.
- Originally built June 24–25, 2026 with Claude Code. Those session transcripts
  were lost to transcript cleanup; `git log` is the only history. Keep this file
  and the commit messages informative for that reason.

## Features (see README.md for the user-facing version)

1. **Color highlight** — the active image's name in the project tree is bold and
   colored, updating live when any input to the color changes.
2. **Scroll to current image** — context-menu item on the project tree.
3. **Remove current image** — context-menu item that closes the image in every
   viewer, removes it from the project (optionally deleting data on disk),
   syncs, refreshes, and restores the tree's scroll position, display settings
   and view.
4. **Set image name color** — context-menu item, shown *only* when the
   right-clicked row is the current image. Opens a color picker with a
   "follow QuPath's default object color" checkbox.
5. **Toolbar icon color** — two rows added to QuPath's own *Objects*
   preference section, giving the toolbar's annotation icons a color of their
   own so that the default object color is free to mean "None annotation
   color". Added v0.3.1.

## Layout

```
src/main/java/qupath/ext/activeimagehelper/ActiveImageHelperExtension.java  # the whole extension (one class)
src/main/resources/css/highlight-active-image.css        # static fallback CSS (red) if the dynamic stylesheet can't be written
src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension  # ServiceLoader registration
docs/removeImage.gif                                     # README demo; docs/javadoc/ is gitignored
build.gradle.kts / settings.gradle.kts                   # qupath-conventions plugin, targets QuPath 0.7.0
```

Sibling repos (same parent folder `C:\Users\Alex\Work\Claude\QuPath\`):

- `qupath-catalog-alexmpdx/` — `catalog.json` that QuPath's extension manager
  reads. **Every release must be added here** or users (and this machine) won't
  see it.
- `qupath-extension-smooth-annotation/` — the other extension in the catalog;
  same build setup, useful as a second example.

## How it works — read before changing anything

- **Reflection on package-private QuPath classes.** The tree rows are
  `ProjectTreeRow.ImageRow`, which extensions cannot see. `extractImageEntry()`
  matches on `getClass().getSimpleName().equals("ImageRow")` and calls
  `getEntry()` via `setAccessible(true)`. This is the first thing to check if a
  QuPath update breaks "Scroll to current image".
- **CSS selector is QuPath-internal.** The highlight relies on QuPath applying
  the `current-image` style class to tree cells inside `.project-browser`
  (`ProjectBrowser.ProjectTreeRowCell.updateItem`, which does
  `getStyleClass().setAll("tree-cell")` then adds `current-image`). Fallback CSS
  and the dynamic CSS both target `.project-browser .tree-cell.current-image`.
- **Two preferences drive the color**, both persistent via
  `PathPrefs.createPersistentPreference` under `activeImageHelper.*`:
  `useDefaultObjectColor` (default true, the pre-0.3 behavior) and
  `highlightColor`. `getHighlightColor()` resolves them; `addStylesheet` listens
  to both *and* to `colorDefaultObjectsProperty`. In `promptForHighlightColor()`
  the color is written **before** the flag so the stylesheet is never rebuilt
  with a stale color mid-update.
- **Live preview without persisting.** The dialog updates the image list as soon
  as a color is picked, so `previewColor` (a nullable field that
  `getHighlightColor()` checks first) holds the in-flight color and the
  preferences are written only if the dialog is accepted — that is what makes
  Cancel revert. Clear `previewColor` *before* committing, and call
  `updateStylesheet` afterwards either way: committing an unchanged value fires
  no preference listener, so without that call a cancelled or no-op dialog would
  leave the preview on screen. Note JavaFX fires `ColorPicker.valueProperty`
  when a palette swatch is clicked, but the *Custom Color…* sub-dialog only
  commits when it is closed — so previewing is instant for swatches and
  deferred for custom colors. That is JavaFX behavior, not ours.
- **Context-sensitive menu item.** *Set image name color* is shown only when the
  right-clicked row is the current image, decided in a `WINDOW_SHOWING` handler
  on the tree's context menu. Two things matter here: use
  `addEventHandler(WindowEvent.WINDOW_SHOWING, ...)`, **never** `setOnShowing` —
  QuPath's project browser already owns that property
  (`ProjectBrowser.getPopup`) and setting it would silently break QuPath's own
  menu items. And the "right-clicked row" is read from the tree's
  **selection**, because JavaFX selects a cell on secondary-button press and
  QuPath's own menu items use the selection the same way. A consequence worth
  knowing: right-clicking blank space below the rows leaves the old selection,
  so the item can appear there too — QuPath's own items behave identically.
- **Dynamic stylesheet = temp file.** `addStylesheet()` writes a `.css` to a
  temp file (`deleteOnExit`) and swaps it in/out of `scene.getStylesheets()` on
  every `PathPrefs.colorDefaultObjectsProperty()` change. Color is packed ARGB
  int → `#RRGGBB` via `ColorTools`.
- **Context menu install is deferred.** The project browser may not exist at
  `installExtension()` time, so it tries in `Platform.runLater`, and if that
  fails, retries on `projectProperty()` changes. Guarded by
  `contextMenuInstalled` so items are never added twice.
- **Scroll restore is a timing hack.** After `refreshProject()` the tree
  rebuilds asynchronously; the vertical `ScrollBar` value is captured before
  removal and restored via a 100 ms `PauseTransition`. Three of the nine commits
  were spent getting this right — don't "simplify" it without testing in QuPath.
- **Display settings must survive removal.** QuPath's *keep display settings*
  preference carries brightness/contrast to the next image only while the
  viewer's `ImageDisplay` still holds the previous image. `resetImageData()`
  clears that, so without help the next image opened would get default
  brightness/contrast. `armDisplayRestore()` captures `ImageDisplay.toJSON(false)`
  plus a channel fingerprint, then a **one-shot listener on
  `viewer.imageDataProperty()`** injects the JSON as the
  `ImageDisplay.class.getName()` property of the next image. That listener must
  stay on `imageDataProperty` (it fires *before* the display is configured);
  `QuPathViewerListener.imageDataChanged` is too late. Two details that look
  droppable but aren't: the compatibility gate copies QuPath's own test
  (channel count, pixel type, channel names), and `setChanged()` is restored
  after `setProperty()` because `ImageData.setProperty` sets the modified flag
  and would otherwise cause bogus "save changes?" prompts.
- **Zoom/pan restore uses a *different* hook on purpose.** `setImageData` keeps
  the zoom and center when the old and new image share a server path (the same
  file listed twice in a project); passing through `null` defeats that, so
  `armViewRestore()` captures downsample + center and reapplies them.
  The two restores are **not** interchangeable: display settings must be
  injected *before* the display is configured (`imageDataProperty`, fires at
  `setImageData` line ~1518), while zoom/pan must be applied *after*
  zoom-to-fit (line ~1578) but *before* the repaint (~1613) — which is exactly
  `QuPathViewerListener.imageDataChanged` (~1583). Using `Platform.runLater`
  for the zoom instead would paint one frame at the reset view first.
  Removing the listener from inside its own callback is safe because
  `fireImageDataChanged` iterates `listeners.toArray(...)`.
- Both restores are one-shot and can't double-arm: the loop only arms a viewer
  that currently holds the removed image, so an empty viewer is never re-armed.
  Snapshots hold only strings/doubles, never the removed server, so nothing of
  the deleted image is kept alive.
### Toolbar icon color (v0.3.1)

- **Only the icon side can be overridden.** Unclassified ("None") objects are
  painted with `PathPrefs.colorDefaultObjectsProperty()` read *at paint time*
  (`PathObjectPainter` → `ColorToolsFX.getDisplayedColorARGB`), and an
  unclassified object has a **null** `PathClass` — `PathClassPane` even disables
  the color picker for `PathClass.NULL_CLASS`. There is no hook there, so that
  preference keeps meaning "None annotation color". The toolbar icons are a
  different story: `IconFactory` *binds* their stroke/fill/textFill to the same
  preference when it builds them, and a binding can be replaced. Hence the split
  is implemented on the icon side only.
- **Which buttons are targeted.** `recolorToolbarIcons` walks
  `getToolBar().getItems()` and matches each button's action
  (`ActionTools.getActionProperty`) against `toolManager.getToolAction(tool)` for
  every tool in `getTools()` — that is how the wand, which arrives with another
  extension, is included — plus `getSelectionModeAction()` and
  `getOverlayActions().SHOW_ANNOTATIONS/FILL_ANNOTATIONS/SHOW_NAMES`.
- **Only *bound* color properties are rebound.** That is what leaves fixed colors
  alone (the detection-colored dots in the points icon, transparent fills). Glyph
  icons that take their color from the theme carry the `use-text-fill` style
  class and are skipped outright: `GuiTools.ensureDuplicatableGlyph` warns that
  merely *requesting* `textFillProperty()` on one can lock its color to black.
  For the same reason, never walk a `Labeled`'s skin children — only its graphic.
- **ControlsFX gives the button a duplicate** of the action's graphic
  (`ActionUtils.copyNode` → `Duplicatable.duplicate()`), not the node itself, and
  builds a fresh one whenever the action's graphic changes — as it does when
  switching between the line and arrow tools. That is why each button's
  `graphicProperty` is watched, and why `toolbar.getItems()` is watched too (the
  tool buttons are rebuilt when the available tools change).
- **The preference rows are custom `PropertySheet.Item`s.** fxtras'
  `PropertyItemBuilder` cannot express "grey out the picker while the checkbox is
  ticked", so both rows implement `PropertySheet.Item` and the color row names its
  own `PropertyEditor`, which ControlsFX instantiates **reflectively** — hence
  `public static` with a single `PropertySheet.Item` constructor. The greying is a
  **listener, not a binding**: `PropertySheetSkin` calls `setDisable()` on the
  editor node while building the row, which would throw on a bound property;
  `isEditable()` supplies the initial state instead.
- **Category** is the key `Prefs.Objects` resolved through `QuPathResources`, so
  the rows join QuPath's own Objects section in any locale. Within a category
  ControlsFX preserves insertion order (it only sorts categories), so the color
  row is added first. The sheet's search box matches name *and* description.
- **`toolbarIconColor` defaults to the default object color read at startup**, and
  fxtras doesn't write a persistent preference until it changes — so a fresh
  install looks exactly like stock QuPath, and clearing the checkbox without
  picking a color changes nothing on screen.
- **Known and deliberately not chased:** the icons shift very slightly when the
  color changes. QuPath's built-in color preferences do the same thing, so the
  rebinding isn't the cause. Investigated 2026-10-16; don't re-open it without a
  reason.

- No unit tests exist; everything is verified by running QuPath.

## Build

```powershell
.\gradlew build
```

- Output: `build\libs\qupath-extension-active-image-helper-<ver>.jar` plus
  `-javadoc.jar` and `-sources.jar`.
- Requires **JDK 25**. System `java` is 21; the Gradle toolchain already
  auto-downloaded Adoptium 25 into `~\.gradle\jdks`, so `.\gradlew` just works.
  Don't invoke `javac`/`java` directly.
- Gradle 9.4 wrapper. `testRuntimeOnly junit-platform-launcher` is there because
  Gradle 9 no longer adds it automatically.
- Windows/PowerShell: give commands on one line, no `\` continuations.

## Test in QuPath

1. Build the jar.
2. QuPath 0.7.0 is at `C:\Users\Alex\AppData\Local\QuPath-0.7.0\`. The
   catalog-installed copy lives at
   `C:\Users\Alex\QuPath\v0.7\extensions\catalogs\alexmpdx QuPath Extensions\Active Image Helper\v<ver>\main-jar\`.
3. For a quick test, drag the new jar onto QuPath **after removing the
   catalog-installed version** in the extension manager — two copies of the
   extension load twice and the context-menu items duplicate.
   Faster loop once a version folder exists: copy `build\libs\...jar` straight
   over the installed one and restart QuPath. The jar isn't file-locked while
   QuPath runs, but the running instance keeps the old classes until it
   restarts — so **a copy with no restart looks exactly like a build that
   didn't take**. `extensions\catalogs\registry.json` says which version is
   actually loaded (`installedVersion` per extension).
4. Verify: open a project with many images; the open image is bold + colored;
   right-click → *Scroll to current image* / *Remove current image*; change
   *Edit → Preferences → Objects → Default color* and confirm the highlight
   follows it; remove an image from the middle of a long list and confirm the
   list doesn't jump to the top.
5. Verify the icon color: *Edit → Preferences → Objects* → clear *Toolbar icons
   follow default object color*, pick a *Toolbar icon color*, and confirm the
   drawing tools / selection mode / annotation overlay buttons change while
   "None" annotations keep the default object color. The picker must grey out
   when the checkbox is ticked. The move tool and other theme-colored icons must
   **not** change. Preference values can be read without the GUI under
   `HKCU:\Software\JavaSoft\Prefs\io.github.qupath\0.7` (Java encodes camelCase
   with slashes, e.g. `active/Image/Helper.toolbar/Icon/Color`).

## Release checklist

1. Bump `version` in `build.gradle.kts`; commit.
2. `.\gradlew clean build`
3. `git tag vX.Y.Z` and push with tags.
4. `gh release create vX.Y.Z build/libs/qupath-extension-active-image-helper-X.Y.Z.jar build/libs/qupath-extension-active-image-helper-X.Y.Z-javadoc.jar --title vX.Y.Z --notes "..."`
5. In `..\qupath-catalog-alexmpdx\catalog.json`, add a new entry at the **top**
   of the Active Image Helper `releases` array (name, `main_url`,
   `javadoc_urls`, `version_range.min = "v0.7.0"`); commit and push that repo.
6. In QuPath's extension manager, update from the catalog and confirm the new
   version installs.

## Conventions

- Single-class extension; keep it that way unless a feature genuinely needs
  more. Section comments (`// ---- Scroll to current image ----`) separate the
  features.
- Prefer QuPath's `qupath.fx.dialogs.Dialogs` for any dialog.
- `.claude/settings.local.json` is session permission state, not project
  config; it's untracked and can stay that way.
