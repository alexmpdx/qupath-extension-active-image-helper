package qupath.ext.activeimagehelper;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.util.Duration;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

        if (!Dialogs.showConfirmDialog("Remove current image", message))
            return;

        // Ask whether to delete associated data files on disk
        var deleteResult = Dialogs.showYesNoCancelDialog(
                "Remove current image",
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

        // Close the image in all viewers that have it open.
        // Emptying a viewer makes its ImageDisplay forget the previous image, and that memory is
        // what QuPath's "keep display settings" preference uses to carry brightness/contrast over
        // to the next image. So capture the settings first and re-apply them to whatever is opened
        // next, making removal behave like simply switching to another image.
        for (var viewer : qupath.getAllViewers()) {
            var viewerData = viewer.getImageData();
            if (viewerData != null && project.getEntry(viewerData) == entry) {
                armDisplayRestore(viewer);
                viewer.resetImageData();
            }
        }

        // Remove from project and persist
        project.removeImage(entry, deleteData);
        try {
            project.syncChanges();
        } catch (IOException e) {
            Dialogs.showErrorMessage("Remove current image", "Failed to sync project changes: " + e.getMessage());
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

    // ---- Preserve display settings across removal ----

    /**
     * Captures the viewer's current display settings and arranges for them to be applied to the
     * next image opened in that viewer.
     * <p>
     * This mirrors what happens when switching directly from one image to another: QuPath retains
     * the display settings of the previous image, but only while the viewer's {@link ImageDisplay}
     * still holds that image. Closing the image to remove it clears that state, so without this
     * the next image opens with default brightness/contrast.
     */
    private void armDisplayRestore(QuPathViewer viewer) {
        // Respect the user's preference, exactly as an image switch would
        if (!PathPrefs.keepDisplaySettingsProperty().get())
            return;

        var display = viewer.getImageDisplay();
        var server = viewer.getServer();
        if (display == null || server == null)
            return;

        String json = display.toJSON(false);
        if (json == null || json.isEmpty())
            return;

        var snapshot = new DisplaySnapshot(json, server);
        var imageDataProperty = viewer.imageDataProperty();

        // One-shot: apply to the next image opened in this viewer, then stop listening
        imageDataProperty.addListener(new ChangeListener<ImageData<BufferedImage>>() {
            @Override
            public void changed(ObservableValue<? extends ImageData<BufferedImage>> obs,
                                ImageData<BufferedImage> oldValue, ImageData<BufferedImage> newValue) {
                // Still empty - stay armed until an image is actually opened
                if (newValue == null)
                    return;
                imageDataProperty.removeListener(this);
                snapshot.applyTo(newValue);
            }
        });
    }

    /**
     * Display settings captured from a viewer, with enough channel information to decide whether
     * they may be applied to a different image.
     */
    private static class DisplaySnapshot {

        private final String json;
        private final int nChannels;
        private final PixelType pixelType;
        private final List<String> channelNames = new ArrayList<>();

        DisplaySnapshot(String json, ImageServer<BufferedImage> server) {
            this.json = json;
            this.nChannels = server.nChannels();
            this.pixelType = server.getPixelType();
            for (int c = 0; c < nChannels; c++)
                channelNames.add(server.getChannel(c).getName());
        }

        /**
         * Applies the captured settings to newly opened image data, if its channels are compatible.
         */
        void applyTo(ImageData<BufferedImage> imageData) {
            if (!isCompatible(imageData.getServer()))
                return;

            // ImageDisplay reads this property whenever an image is set, so storing it here applies
            // the settings to the image that is about to be displayed. QuPath itself uses the same
            // approach to copy display settings between viewers (see QuPathViewer.setImageData).
            boolean wasChanged = imageData.isChanged();
            imageData.setProperty(ImageDisplay.class.getName(), json);
            // Carrying settings over is not a user edit, so don't leave the image marked as
            // modified - otherwise closing it would prompt to save changes that were never made.
            imageData.setChanged(wasChanged);
        }

        /**
         * Uses the same test as {@link ImageDisplay} when it retains settings between images:
         * matching channel count, pixel type and channel names.
         */
        private boolean isCompatible(ImageServer<BufferedImage> server) {
            if (server == null || server.nChannels() != nChannels || server.getPixelType() != pixelType)
                return false;
            for (int c = 0; c < nChannels; c++) {
                if (!Objects.equals(channelNames.get(c), server.getChannel(c).getName()))
                    return false;
            }
            return true;
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
