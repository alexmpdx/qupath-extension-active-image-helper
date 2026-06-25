package qupath.ext.activeimagehelper;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.util.Duration;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extension that highlights the currently open image in the project image list
 * with a distinct color, and adds right-click options to scroll to or remove
 * the current image.
 */
public class ActiveImageHelperExtension implements QuPathExtension {

    private boolean contextMenuInstalled = false;
    private Path dynamicCssPath;

    @Override
    public void installExtension(QuPathGUI qupath) {
        addStylesheet(qupath);

        // Defer context menu installation to ensure the scene graph is ready
        Platform.runLater(() -> {
            if (!tryInstallContextMenu(qupath)) {
                // Project browser may not exist yet — retry when a project is opened
                qupath.projectProperty().addListener((obs, oldProject, newProject) -> {
                    if (!contextMenuInstalled) {
                        Platform.runLater(() -> tryInstallContextMenu(qupath));
                    }
                });
            }
        });
    }

    private void addStylesheet(QuPathGUI qupath) {
        var stage = qupath.getStage();
        if (stage == null || stage.getScene() == null)
            return;

        try {
            dynamicCssPath = Files.createTempFile("qupath-active-image-", ".css");
            dynamicCssPath.toFile().deleteOnExit();
            updateStylesheet(qupath);

            // Update the color whenever the default object color preference changes
            PathPrefs.colorDefaultObjectsProperty().addListener((obs, oldVal, newVal) ->
                    updateStylesheet(qupath));
        } catch (IOException e) {
            // Fall back to static CSS with hardcoded color
            var url = getClass().getResource("/css/highlight-active-image.css");
            if (url != null)
                stage.getScene().getStylesheets().add(url.toExternalForm());
        }
    }

    private void updateStylesheet(QuPathGUI qupath) {
        if (dynamicCssPath == null)
            return;

        var scene = qupath.getStage().getScene();
        int packed = PathPrefs.colorDefaultObjectsProperty().get();
        String hex = String.format("#%02X%02X%02X",
                ColorTools.red(packed), ColorTools.green(packed), ColorTools.blue(packed));

        String css = ".project-browser .tree-cell.current-image {\n" +
                     "    -fx-text-fill: " + hex + ";\n" +
                     "    -fx-font-weight: bold;\n" +
                     "}\n";

        try {
            String uri = dynamicCssPath.toUri().toString();
            scene.getStylesheets().remove(uri);
            Files.writeString(dynamicCssPath, css);
            scene.getStylesheets().add(uri);
        } catch (IOException e) {
            // ignore
        }
    }

    private boolean tryInstallContextMenu(QuPathGUI qupath) {
        if (contextMenuInstalled)
            return true;

        var treeView = findProjectTreeView(qupath);
        if (treeView == null)
            return false;

        var contextMenu = treeView.getContextMenu();
        if (contextMenu == null)
            return false;

        var scrollItem = new MenuItem("Scroll to current image");
        scrollItem.setOnAction(e -> scrollToCurrentImage(qupath, treeView));
        scrollItem.disableProperty().bind(qupath.imageDataProperty().isNull());

        var removeItem = new MenuItem("Remove current image");
        removeItem.setOnAction(e -> removeCurrentImage(qupath));
        removeItem.disableProperty().bind(qupath.imageDataProperty().isNull());

        contextMenu.getItems().addAll(new SeparatorMenuItem(), scrollItem, removeItem);
        contextMenuInstalled = true;
        return true;
    }

    private TreeView<?> findProjectTreeView(QuPathGUI qupath) {
        var stage = qupath.getStage();
        if (stage == null || stage.getScene() == null)
            return null;

        var projectBrowser = stage.getScene().lookup(".project-browser");
        if (projectBrowser == null)
            return null;

        var node = projectBrowser.lookup(".tree-view");
        return node instanceof TreeView<?> tv ? tv : null;
    }

    // ---- Scroll to current image ----

    @SuppressWarnings("unchecked")
    private void scrollToCurrentImage(QuPathGUI qupath, TreeView<?> tree) {
        var imageData = qupath.getImageData();
        var project = qupath.getProject();
        if (imageData == null || project == null)
            return;

        var currentEntry = project.getEntry(imageData);
        if (currentEntry == null)
            return;

        var matchingItem = findMatchingItem((TreeItem<Object>) tree.getRoot(), currentEntry);
        if (matchingItem == null)
            return;

        // Expand all ancestor nodes so the item is visible in the tree
        expandParents(matchingItem);

        var typedTree = (TreeView<Object>) tree;
        int row = typedTree.getRow(matchingItem);
        if (row >= 0) {
            typedTree.getSelectionModel().select(row);
            typedTree.scrollTo(row);
        }
    }

    private TreeItem<Object> findMatchingItem(TreeItem<Object> parent, ProjectImageEntry<?> targetEntry) {
        if (parent == null)
            return null;

        var entry = extractImageEntry(parent.getValue());
        if (entry != null && entry == targetEntry)
            return parent;

        for (var child : parent.getChildren()) {
            var result = findMatchingItem(child, targetEntry);
            if (result != null)
                return result;
        }
        return null;
    }

    private void expandParents(TreeItem<?> item) {
        var parent = item.getParent();
        while (parent != null) {
            parent.setExpanded(true);
            parent = parent.getParent();
        }
    }

    // ---- Remove current image ----

    private void removeCurrentImage(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        var project = qupath.getProject();
        if (imageData == null || project == null)
            return;

        var entry = project.getEntry(imageData);
        if (entry == null)
            return;

        // Confirm removal
        String message = "Remove '" + entry.getImageName() + "' from the project?";
        if (imageData.isChanged())
            message += "\n\nWarning: this image has unsaved changes that will be lost.";

        if (!Dialogs.showConfirmDialog("Remove image", message))
            return;

        // Ask whether to delete associated data files on disk
        var deleteResult = Dialogs.showYesNoCancelDialog(
                "Remove image",
                "Also delete associated data files on disk?"
        );
        if (deleteResult == ButtonType.CANCEL)
            return;
        boolean deleteData = deleteResult == ButtonType.YES;

        // Save scroll position before modifying the tree
        var treeView = findProjectTreeView(qupath);
        double scrollPos = 0;
        ScrollBar scrollBar = null;
        if (treeView != null) {
            var node = treeView.lookup(".scroll-bar:vertical");
            if (node instanceof ScrollBar sb) {
                scrollBar = sb;
                scrollPos = sb.getValue();
            }
        }

        // Close the image in all viewers that have it open
        for (var viewer : qupath.getAllViewers()) {
            var viewerData = viewer.getImageData();
            if (viewerData != null && project.getEntry(viewerData) == entry) {
                viewer.resetImageData();
            }
        }

        // Remove from project and persist
        project.removeImage(entry, deleteData);
        try {
            project.syncChanges();
        } catch (IOException e) {
            Dialogs.showErrorMessage("Remove image", "Failed to sync project changes: " + e.getMessage());
        }
        qupath.refreshProject();

        // Restore scroll position after the tree finishes rebuilding.
        // The refresh triggers async layout passes, so we use a short
        // delay to ensure we restore after the tree is fully rebuilt.
        if (scrollBar != null) {
            final ScrollBar sb = scrollBar;
            final double pos = scrollPos;
            var pause = new PauseTransition(Duration.millis(100));
            pause.setOnFinished(e -> sb.setValue(pos));
            pause.play();
        }
    }

    // ---- Reflection helper ----

    /**
     * Extracts the ProjectImageEntry from a ProjectTreeRow.ImageRow using reflection,
     * since ProjectTreeRow is package-private and not accessible to extensions.
     */
    @SuppressWarnings("unchecked")
    private ProjectImageEntry<BufferedImage> extractImageEntry(Object treeRow) {
        if (treeRow == null || !treeRow.getClass().getSimpleName().equals("ImageRow"))
            return null;
        try {
            var method = treeRow.getClass().getDeclaredMethod("getEntry");
            method.setAccessible(true);
            return (ProjectImageEntry<BufferedImage>) method.invoke(treeRow);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getName() {
        return "Active Image Helper";
    }

    @Override
    public String getDescription() {
        return "Highlights the active image in the project list and adds convenience actions";
    }
}
