package com.anthropic.eclipse.claude.views;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.ElementTreeSelectionDialog;
import org.eclipse.ui.model.WorkbenchContentProvider;
import org.eclipse.ui.model.WorkbenchLabelProvider;

import com.anthropic.eclipse.claude.views.widgets.ThemeManager;

/**
 * Manages file and image attachments for the conversation input area.
 *
 * <p>Encapsulates attachment state (file paths, line ranges, images) and
 * provides UI chip management. Created by {@link ClaudeConversationView}
 * once the attachment bar composite is available.
 */
public class AttachmentManager {

    // State
    private final List<String>       filePaths  = new ArrayList<>();
    private final Map<String, int[]> fileRanges = new LinkedHashMap<>();
    private final List<byte[]>       images     = new ArrayList<>();
    private final List<String>       imageNames = new ArrayList<>();

    // UI refs
    private final Composite attachmentBar;
    private final Shell     shell;
    private final Text      inputField;   // needed for @-mention caret manipulation

    // Callbacks to owner view
    private final Consumer<String> errorCallback;

    public AttachmentManager(Composite attachmentBar, Shell shell,
                             Text inputField, Consumer<String> errorCallback) {
        this.attachmentBar  = attachmentBar;
        this.shell          = shell;
        this.inputField     = inputField;
        this.errorCallback  = errorCallback;
    }

    // ====== State queries ======

    public boolean hasAttachments() {
        return !filePaths.isEmpty() || !images.isEmpty();
    }

    public List<String>       getFilePaths()  { return filePaths; }
    public Map<String, int[]> getFileRanges() { return fileRanges; }
    public List<byte[]>       getImages()     { return images; }
    public List<String>       getImageNames() { return imageNames; }

    /**
     * Builds a display label like "[file.java#10-20] [Image 1] ".
     */
    public String buildDisplayLabel() {
        StringBuilder sb = new StringBuilder();
        for (String path : filePaths) {
            int[] range = fileRanges.get(path);
            String label = Paths.get(path).getFileName().toString();
            if (range != null) label += "#" + range[0] + "-" + range[1];
            sb.append("[").append(label).append("] ");
        }
        for (String name : imageNames) {
            sb.append("[").append(name).append("] ");
        }
        return sb.toString();
    }

    /**
     * Builds the &lt;file&gt; context blocks to prepend to the outgoing message.
     */
    public String buildFileContext() {
        StringBuilder sb = new StringBuilder();
        for (String filePath : new ArrayList<>(filePaths)) {
            try {
                String rawContent = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
                String fileName   = Paths.get(filePath).getFileName().toString();
                int[]  range      = fileRanges.get(filePath);
                String content;
                String fileLabel;
                if (range != null) {
                    String[] fileLines = rawContent.split("\n", -1);
                    int start = Math.max(1, range[0]);
                    int end   = Math.min(fileLines.length, range[1]);
                    StringBuilder lineSb = new StringBuilder();
                    for (int li = start - 1; li < end; li++) {
                        lineSb.append(fileLines[li]).append("\n");
                    }
                    content   = lineSb.toString();
                    fileLabel = fileName + " (lines " + start + "-" + end + ")";
                } else {
                    content   = rawContent;
                    fileLabel = fileName;
                }
                if (content.length() > 50_000) {
                    content = content.substring(0, 50_000) + "\n... [truncated]";
                }
                sb.append("<file path=\"").append(fileLabel).append("\">\n");
                sb.append(content).append("</file>\n\n");
            } catch (Exception ex) {
                sb.append("[Could not read: ").append(Paths.get(filePath).getFileName()).append("]\n\n");
            }
        }
        return sb.toString();
    }

    /** Clears all attachment state and removes UI chips. */
    public void clearAll() {
        filePaths.clear();
        fileRanges.clear();
        images.clear();
        imageNames.clear();
        clearChips();
    }

    // ====== UI actions ======

    /** Show a popup menu to choose between workspace files or filesystem browse. */
    public void attachFile() {
        Menu menu = new Menu(shell, SWT.POP_UP);

        MenuItem workspaceItem = new MenuItem(menu, SWT.PUSH);
        workspaceItem.setText("From Workspace...");
        workspaceItem.addListener(SWT.Selection, e -> attachFileFromWorkspace());

        MenuItem filesystemItem = new MenuItem(menu, SWT.PUSH);
        filesystemItem.setText("Browse Filesystem...");
        filesystemItem.addListener(SWT.Selection, e -> attachFileFromFilesystem());

        // Show at current cursor position
        org.eclipse.swt.graphics.Point pt = Display.getCurrent().getCursorLocation();
        menu.setLocation(pt.x, pt.y);
        menu.setVisible(true);
    }

    /** Open an Eclipse tree dialog to pick one or more workspace files. */
    private void attachFileFromWorkspace() {
        ElementTreeSelectionDialog dialog = new ElementTreeSelectionDialog(
            shell, new WorkbenchLabelProvider(), new WorkbenchContentProvider());
        dialog.setTitle("Add File to Context");
        dialog.setMessage("Select one or more files to include in your message:");
        dialog.setInput(ResourcesPlugin.getWorkspace().getRoot());
        dialog.addFilter(new ViewerFilter() {
            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element) {
                return element instanceof org.eclipse.core.resources.IContainer
                    || element instanceof IFile;
            }
        });
        dialog.setAllowMultiple(true);

        if (dialog.open() == Window.OK) {
            for (Object result : dialog.getResult()) {
                if (result instanceof IFile) {
                    IFile iFile = (IFile) result;
                    String path = iFile.getLocation().toOSString();
                    if (!filePaths.contains(path)) {
                        filePaths.add(path);
                        addChip(iFile.getName(), () -> {
                            filePaths.remove(path);
                            refresh();
                        });
                    }
                }
            }
            refresh();
        }
    }

    /** Open a native OS file dialog to pick any file from the filesystem. */
    private void attachFileFromFilesystem() {
        FileDialog dialog = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
        dialog.setText("Add File to Context");
        String firstPath = dialog.open();
        if (firstPath != null) {
            String filterPath = dialog.getFilterPath();
            for (String fileName : dialog.getFileNames()) {
                String fullPath = filterPath + java.io.File.separator + fileName;
                if (!filePaths.contains(fullPath)) {
                    filePaths.add(fullPath);
                    final String capturedPath = fullPath;
                    addChip(fileName, () -> {
                        filePaths.remove(capturedPath);
                        refresh();
                    });
                }
            }
            refresh();
        }
    }

    /** Open a quick list dialog to attach a file via @-mention. */
    public void handleAtMention() {
        if (inputField == null || inputField.isDisposed()) return;

        List<IFile> allFiles = new ArrayList<>();
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject project : projects) {
            if (project.isOpen()) {
                try {
                    collectFiles(project, allFiles, 500);
                } catch (Exception ignored) {}
            }
        }
        if (allFiles.isEmpty()) return;

        ElementListSelectionDialog dialog = new ElementListSelectionDialog(
            shell, new LabelProvider() {
                @Override
                public String getText(Object element) {
                    if (element instanceof IFile) {
                        IFile f = (IFile) element;
                        return f.getName() + " - " + f.getProjectRelativePath().toString();
                    }
                    return super.getText(element);
                }
            });
        dialog.setTitle("@mention - Select File");
        dialog.setMessage("Type to filter, select a file to attach:");
        dialog.setElements(allFiles.toArray());
        dialog.setMultipleSelection(true);

        if (dialog.open() == Window.OK) {
            for (Object result : dialog.getResult()) {
                if (result instanceof IFile) {
                    IFile iFile = (IFile) result;
                    String path = iFile.getLocation().toOSString();
                    if (!filePaths.contains(path)) {
                        int[] range = askForLineRange(iFile.getName());
                        filePaths.add(path);
                        if (range != null) fileRanges.put(path, range);
                        String chipLabel = range != null
                            ? iFile.getName() + "#" + range[0] + "-" + range[1]
                            : iFile.getName();
                        addChip(chipLabel, () -> {
                            filePaths.remove(path);
                            fileRanges.remove(path);
                            refresh();
                        });
                    }
                }
            }
            refresh();

            // Remove the @ character from input
            String currentText = inputField.getText();
            int caretPos = inputField.getCaretPosition();
            if (caretPos > 0 && caretPos <= currentText.length()
                    && currentText.charAt(caretPos - 1) == '@') {
                inputField.setText(currentText.substring(0, caretPos - 1)
                    + currentText.substring(caretPos));
                inputField.setSelection(caretPos - 1);
            }
        }
    }

    /** Paste an image from the system clipboard. */
    public void pasteImage() {
        Clipboard clipboard = new Clipboard(Display.getDefault());
        try {
            ImageData imageData = (ImageData) clipboard.getContents(ImageTransfer.getInstance());
            if (imageData == null) {
                errorCallback.accept("No image found in clipboard.\n" +
                    "Copy an image first (e.g. Cmd+Ctrl+Shift+4 for a screenshot), then try again.");
                return;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[]{imageData};
            loader.save(baos, SWT.IMAGE_PNG);
            byte[] pngBytes = baos.toByteArray();
            images.add(pngBytes);
            String name = "Image " + images.size();
            imageNames.add(name);
            addImageChip(imageData, name + " (" + (pngBytes.length / 1024) + " KB)", () -> {
                int i = images.indexOf(pngBytes);
                if (i >= 0) {
                    images.remove(i);
                    imageNames.remove(i);
                }
                refresh();
            });
            refresh();
        } catch (Exception ex) {
            errorCallback.accept("Could not paste image: " + ex.getMessage());
        } finally {
            clipboard.dispose();
        }
    }

    // ====== private helpers ======

    private int[] askForLineRange(String fileName) {
        InputDialog rangeDialog = new InputDialog(
            shell,
            "Line Range (optional)",
            "Include specific lines of " + fileName + "?\n" +
            "Enter range like \"10-20\", or leave empty for the whole file:",
            "",
            newText -> {
                if (newText == null || newText.trim().isEmpty()) return null;
                String[] parts = newText.trim().split("-");
                if (parts.length != 2) return "Format must be 'start-end' (e.g. 10-20)";
                try {
                    int start = Integer.parseInt(parts[0].trim());
                    int end   = Integer.parseInt(parts[1].trim());
                    if (start < 1 || end < start)
                        return "Invalid range: start must be >= 1 and end >= start";
                } catch (NumberFormatException ex) {
                    return "Line numbers must be integers";
                }
                return null;
            });
        if (rangeDialog.open() != Window.OK) return null;
        String value = rangeDialog.getValue().trim();
        if (value.isEmpty()) return null;
        try {
            String[] parts = value.split("-");
            return new int[]{ Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
        } catch (Exception e) {
            return null;
        }
    }

    private void addChip(String label, Runnable onRemove) {
        ThemeManager tmChip = ThemeManager.getInstance();
        Color chipBg = tmChip.getColor(tmChip.chipBg);
        Composite chip = new Composite(attachmentBar, SWT.BORDER);
        GridLayout chipLayout = new GridLayout(2, false);
        chipLayout.marginWidth = 5;
        chipLayout.marginHeight = 2;
        chipLayout.horizontalSpacing = 2;
        chip.setLayout(chipLayout);
        chip.setBackground(chipBg);
        chip.addDisposeListener(e -> chipBg.dispose());

        Label chipLabel = new Label(chip, SWT.NONE);
        chipLabel.setText(label);
        chipLabel.setBackground(chipBg);
        Font chipFont = new Font(Display.getDefault(), "Segoe UI", 8, SWT.NORMAL);
        chipLabel.setFont(chipFont);
        chipLabel.addDisposeListener(e -> chipFont.dispose());

        Button removeBtn = new Button(chip, SWT.FLAT);
        removeBtn.setText("\u00D7");
        removeBtn.setBackground(chipBg);
        removeBtn.addListener(SWT.Selection, e -> {
            onRemove.run();
            chip.dispose();
            refresh();
        });
    }

    private void addImageChip(ImageData imageData, String tooltipText, Runnable onRemove) {
        ThemeManager tmChip = ThemeManager.getInstance();
        Color chipBg = tmChip.getColor(tmChip.chipBg);
        Composite chip = new Composite(attachmentBar, SWT.BORDER);
        GridLayout chipLayout = new GridLayout(3, false);
        chipLayout.marginWidth = 4;
        chipLayout.marginHeight = 2;
        chipLayout.horizontalSpacing = 4;
        chip.setLayout(chipLayout);
        chip.setBackground(chipBg);
        chip.addDisposeListener(e -> chipBg.dispose());

        // Create scaled thumbnail (max 40x40, preserve aspect ratio)
        int maxThumb = 40;
        int origW = imageData.width;
        int origH = imageData.height;
        double scale = Math.min((double) maxThumb / origW, (double) maxThumb / origH);
        if (scale > 1.0) scale = 1.0;
        int thumbW = Math.max(1, (int)(origW * scale));
        int thumbH = Math.max(1, (int)(origH * scale));

        Image fullImage = new Image(Display.getDefault(), imageData);
        Image thumbImage = new Image(Display.getDefault(), thumbW, thumbH);
        GC gc = new GC(thumbImage);
        gc.setAntialias(SWT.ON);
        gc.setInterpolation(SWT.HIGH);
        gc.drawImage(fullImage, 0, 0, origW, origH, 0, 0, thumbW, thumbH);
        gc.dispose();
        fullImage.dispose();

        Label imgLabel = new Label(chip, SWT.NONE);
        imgLabel.setImage(thumbImage);
        imgLabel.setBackground(chipBg);
        imgLabel.setToolTipText(tooltipText);
        imgLabel.addDisposeListener(e -> thumbImage.dispose());

        Label textLabel = new Label(chip, SWT.NONE);
        textLabel.setText(tooltipText);
        textLabel.setBackground(chipBg);
        Font chipFont = new Font(Display.getDefault(), "Segoe UI", 8, SWT.NORMAL);
        textLabel.setFont(chipFont);
        textLabel.addDisposeListener(e -> chipFont.dispose());

        Button removeBtn = new Button(chip, SWT.FLAT);
        removeBtn.setText("\u00D7");
        removeBtn.setBackground(chipBg);
        removeBtn.addListener(SWT.Selection, e -> {
            onRemove.run();
            chip.dispose();
            refresh();
        });
    }

    private void refresh() {
        if (attachmentBar == null || attachmentBar.isDisposed()) return;
        boolean hasChips = attachmentBar.getChildren().length > 0;
        GridData gd = (GridData) attachmentBar.getLayoutData();
        gd.exclude = !hasChips;
        attachmentBar.setVisible(hasChips);
        attachmentBar.getParent().layout(true, true);
    }

    private void clearChips() {
        if (attachmentBar == null || attachmentBar.isDisposed()) return;
        for (Control child : attachmentBar.getChildren()) {
            child.dispose();
        }
        refresh();
    }

    private static void collectFiles(org.eclipse.core.resources.IContainer container,
                                     List<IFile> files, int maxFiles) {
        if (files.size() >= maxFiles) return;
        try {
            for (org.eclipse.core.resources.IResource member : container.members()) {
                if (files.size() >= maxFiles) break;
                if (member instanceof IFile) {
                    files.add((IFile) member);
                } else if (member instanceof org.eclipse.core.resources.IContainer) {
                    String name = member.getName();
                    if (!name.startsWith(".") && !"bin".equals(name)
                            && !"target".equals(name) && !"node_modules".equals(name)) {
                        collectFiles((org.eclipse.core.resources.IContainer) member, files, maxFiles);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
