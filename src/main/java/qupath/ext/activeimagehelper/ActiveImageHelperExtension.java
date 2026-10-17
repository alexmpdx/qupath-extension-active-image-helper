package qupath.ext.activeimagehelper;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.Shape;
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

    /**
     * Highlight color chosen by the user, as a packed RGB value. Only used when
     * {@link #useDefaultObjectColor} is false.
     */
    private static final IntegerProperty highlightColor = PathPrefs.createPersistentPreference(
            "activeImageHelper.highlightColor", ColorTools.packRGB(255, 0, 0));

    /**
     * Whether to follow QuPath's default object color rather than {@link #highlightColor}.
     * True by default, which was the behavior before the color became user-selectable.
     */
    private static final BooleanProperty useDefaultObjectColor = PathPrefs.createPersistentPreference(
            "activeImageHelper.useDefaultObjectColor", true);

    private boolean contextMenuInstalled = false;
    private Path dynamicCssPath;

    /**
     * Color currently being previewed in the color dialog, or null if no dialog is open.
     * Kept out of the preferences so that cancelling the dialog reverts the preview.
     */
    private Integer previewColor;

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

            // Update whenever anything feeding the effective color changes
            PathPrefs.colorDefaultObjectsProperty().addListener((obs, oldVal, newVal) ->
                    updateStylesheet(qupath));
            highlightColor.addListener((obs, oldVal, newVal) -> updateStylesheet(qupath));
            useDefaultObjectColor.addListener((obs, oldVal, newVal) -> updateStylesheet(qupath));
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
        int packed = getHighlightColor();
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

        // Only shown when the right-clicked row is the current image, so its visibility is
        // recomputed each time the menu opens
        var colorItem = new MenuItem("Set image name color...");
        colorItem.setOnAction(e -> promptForHighlightColor(qupath));

        contextMenu.getItems().addAll(new SeparatorMenuItem(), scrollItem, removeItem, colorItem);

        // Add a handler rather than using setOnShowing, which QuPath's project browser already
        // uses for its own items (see ProjectBrowser.getPopup) - setting it would replace theirs.
        contextMenu.addEventHandler(WindowEvent.WINDOW_SHOWING, e ->
                colorItem.setVisible(isCurrentImageSelected(qupath, treeView)));

        contextMenuInstalled = true;
        return true;
    }

    /**
     * Checks whether the row that was right-clicked is the currently open image.
     * <p>
     * The tree selects the row that is right-clicked, and QuPath's own project browser menu items
     * use the selection in the same way to decide what to show.
     */
    private boolean isCurrentImageSelected(QuPathGUI qupath, TreeView<?> tree) {
        var imageData = qupath.getImageData();
        var project = qupath.getProject();
        if (imageData == null || project == null)
            return false;

        var selected = tree.getSelectionModel().getSelectedItem();
        if (selected == null)
            return false;

        var entry = extractImageEntry(selected.getValue());
        return entry != null && entry == project.getEntry(imageData);
    }

    // ---- Highlight color ----

    /**
     * The color used to highlight the active image, as a packed RGB value.
     */
    private int getHighlightColor() {
        // A color being previewed in the dialog wins until the dialog is dismissed
        if (previewColor != null)
            return previewColor;
        return useDefaultObjectColor.get()
                ? PathPrefs.colorDefaultObjectsProperty().get()
                : highlightColor.get();
    }

    private void promptForHighlightColor(QuPathGUI qupath) {
        var picker = new ColorPicker(toColor(getHighlightColor()));
        picker.setMaxWidth(Double.MAX_VALUE);

        var followDefault = new CheckBox("Follow QuPath's default object color");
        followDefault.setSelected(useDefaultObjectColor.get());
        // Nothing to pick while the color is being taken from the preference
        picker.disableProperty().bind(followDefault.selectedProperty());

        // Show the chosen color in the image list straight away, without storing it: the
        // preferences are only written if the dialog is accepted, so Cancel reverts
        picker.valueProperty().addListener((obs, oldVal, newVal) -> {
            previewColor = newVal == null ? null : packColor(newVal);
            updateStylesheet(qupath);
        });
        followDefault.selectedProperty().addListener((obs, oldVal, newVal) -> {
            previewColor = newVal
                    ? PathPrefs.colorDefaultObjectsProperty().get()
                    : packColor(picker.getValue());
            updateStylesheet(qupath);
        });

        var label = new Label("Set text color for the active image in the Project tab's image list:");
        label.setWrapText(true);

        var pane = new VBox(8, label, picker, followDefault);
        pane.setPrefWidth(360);

        boolean accepted = Dialogs.showConfirmDialog("Set image name color", pane);

        // Stop previewing before touching the preferences, so the stored values take over
        previewColor = null;
        if (accepted) {
            // Set the color before the flag, so the stylesheet is never rebuilt with a stale
            // color in between
            var color = picker.getValue();
            if (color != null)
                highlightColor.set(packColor(color));
            useDefaultObjectColor.set(followDefault.isSelected());
        }
        // Apply the stored color, which also reverts the preview when cancelled
        updateStylesheet(qupath);
    }

    private static int packColor(Color color) {
        return ColorTools.packRGB(
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    private static Color toColor(int packed) {
        return Color.rgb(ColorTools.red(packed), ColorTools.green(packed), ColorTools.blue(packed));
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
                armViewRestore(viewer);
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
     * Captures the viewer's zoom and center, and restores them if the next image opened in that
     * viewer uses the same server path.
     * <p>
     * {@link QuPathViewer#setImageData} leaves the zoom and center alone when the previous and
     * next image share a server path - which happens when the same image file is present in a
     * project more than once. Closing the image to remove it leaves no previous image for that
     * check, so the view would otherwise be reset to fit the image.
     */
    private void armViewRestore(QuPathViewer viewer) {
        var imageData = viewer.getImageData();
        if (imageData == null)
            return;

        String serverPath = imageData.getServerPath();
        double downsample = viewer.getDownsampleFactor();
        double centerX = viewer.getCenterPixelX();
        double centerY = viewer.getCenterPixelY();

        // The restore has to happen after setImageData() has applied its zoom-to-fit, which rules
        // out a listener on imageDataProperty, but before the viewer repaints, which rules out
        // Platform.runLater (the first painted frame would show the reset view).
        // QuPathViewerListener.imageDataChanged sits exactly between the two.
        viewer.addViewerListener(new QuPathViewerListener() {

            @Override
            public void imageDataChanged(QuPathViewer source, ImageData<BufferedImage> imageDataOld,
                                         ImageData<BufferedImage> imageDataNew) {
                // Still empty - stay armed until an image is actually opened
                if (imageDataNew == null)
                    return;
                source.removeViewerListener(this);
                if (!serverPath.equals(imageDataNew.getServerPath()))
                    return;
                // -1, -1 means zoom without anchoring on a point; the single-argument overload
                // would anchor on the mouse position if the cursor happened to be over the viewer
                source.setDownsampleFactor(downsample, -1, -1);
                source.setCenterPixelLocation(centerX, centerY);
            }

            @Override
            public void visibleRegionChanged(QuPathViewer source, Shape shape) {}

            @Override
            public void selectedObjectChanged(QuPathViewer source, PathObject pathObjectSelected) {}

            @Override
            public void viewerClosed(QuPathViewer source) {
                source.removeViewerListener(this);
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
