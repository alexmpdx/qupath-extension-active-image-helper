# QuPath Active Image Helper

A small QuPath 0.7 extension that makes the currently open image obvious in the
project image list and adds right-click actions for it. Published as
`io.github.alexmpdx:qupath-extension-active-image-helper`, GPL-3.0.

- GitHub: https://github.com/alexmpdx/qupath-extension-active-image-helper
- Current release: **v0.2.2** (June 25, 2026). Version lives in `build.gradle.kts`.
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
4. Verify: open a project with many images; the open image is bold + colored;
   right-click → *Scroll to current image* / *Remove current image*; change
   *Edit → Preferences → Objects → Default color* and confirm the highlight
   follows it; remove an image from the middle of a long list and confirm the
   list doesn't jump to the top.

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
