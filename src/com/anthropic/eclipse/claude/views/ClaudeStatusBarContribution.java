package com.anthropic.eclipse.claude.views;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.cli.ClaudeCliManager;
import com.anthropic.eclipse.claude.cli.ICliStateListener;
import com.anthropic.eclipse.claude.model.ConversationModel;
import com.anthropic.eclipse.claude.model.IConversationListener;
import com.anthropic.eclipse.claude.model.MessageBlock;
import com.anthropic.eclipse.claude.model.SessionInfo;
import com.anthropic.eclipse.claude.model.UsageInfo;

/**
 * Eclipse workbench status-bar contribution that shows the current Claude
 * connection state, active model, and streaming indicator.
 *
 * Registered in plugin.xml under toolbar:org.eclipse.ui.trim.status.
 *
 * Listens to:
 *   - {@link ICliStateListener} for process state changes
 *   - {@link IConversationListener} for session/streaming state
 *   - Activator model-change events to re-register when a new view opens
 */
public class ClaudeStatusBarContribution extends WorkbenchWindowControlContribution
        implements ICliStateListener, IConversationListener {

    private Composite container;
    private Label statusLabel;

    // Colors owned by this control (disposed via container dispose listener)
    private Color greenColor;
    private Color redColor;
    private Color grayColor;
    private Color blueColor;
    private Color yellowColor;

    private volatile boolean streaming = false;
    private volatile String currentModel = null;

    // Tracks which CLI manager we're currently listening to (one per active tab).
    private ClaudeCliManager attachedCliManager;

    // Model-change consumer — kept so we can remove it on dispose
    private final Consumer<ConversationModel> modelChangeConsumer = this::onConversationModelChanged;
    // Active-CLI change consumer — fires when the focused tab's CLI changes
    private final Consumer<ClaudeCliManager> cliManagerChangeConsumer = this::onActiveCliManagerChanged;

    @Override
    protected Control createControl(Composite parent) {
        container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 6;
        layout.marginHeight = 1;
        container.setLayout(layout);

        statusLabel = new Label(container, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, true));
        statusLabel.setText("Claude");

        // Allocate colors
        Display display = parent.getDisplay();
        greenColor  = new Color(display,  50, 180,  50);
        redColor    = new Color(display, 220,  50,  50);
        grayColor   = new Color(display, 140, 140, 140);
        blueColor   = new Color(display,  50, 120, 220);
        yellowColor = new Color(display, 180, 140,   0);

        container.addDisposeListener(e -> {
            if (greenColor  != null) greenColor.dispose();
            if (redColor    != null) redColor.dispose();
            if (grayColor   != null) grayColor.dispose();
            if (blueColor   != null) blueColor.dispose();
            if (yellowColor != null) yellowColor.dispose();
        });

        // Register for active-CLI changes (each conversation view has its own CLI;
        // we always reflect whichever tab is currently focused).
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.addActiveCliManagerListener(cliManagerChangeConsumer);
            ClaudeCliManager existingCli = activator.getActiveCliManager();
            if (existingCli != null) {
                attachedCliManager = existingCli;
                existingCli.addStateListener(this);
            }

            // Register for model changes (so we can attach to whichever model the view creates)
            activator.addConversationModelListener(modelChangeConsumer);
            ConversationModel existing = activator.getConversationModel();
            if (existing != null) {
                existing.addListener(this);
            }
        }

        updateDisplay();
        return container;
    }

    @Override
    public void dispose() {
        if (attachedCliManager != null) {
            attachedCliManager.removeStateListener(this);
            attachedCliManager = null;
        }
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.removeActiveCliManagerListener(cliManagerChangeConsumer);
            activator.removeConversationModelListener(modelChangeConsumer);
            ConversationModel model = activator.getConversationModel();
            if (model != null) {
                model.removeListener(this);
            }
        }
        super.dispose();
    }

    // ==================== Model & CLI lifecycle ====================

    private void onConversationModelChanged(ConversationModel newModel) {
        // Remove listener from old model (we don't have a reference to it here,
        // so we rely on Activator setting null first, then the new model)
        if (newModel != null) {
            newModel.addListener(this);
        }
        // Reset streaming state when model changes
        streaming = false;
        currentModel = null;
        updateDisplay();
    }

    /**
     * Called when the focused conversation view changes — swap the CLI we listen to.
     */
    private void onActiveCliManagerChanged(ClaudeCliManager newCli) {
        if (attachedCliManager != null) {
            attachedCliManager.removeStateListener(this);
        }
        attachedCliManager = newCli;
        if (newCli != null) {
            newCli.addStateListener(this);
        }
        updateDisplay();
    }

    // ==================== ICliStateListener ====================

    @Override
    public void onStateChanged(ClaudeCliManager.ProcessState oldState,
                               ClaudeCliManager.ProcessState newState) {
        if (newState == ClaudeCliManager.ProcessState.STOPPED ||
                newState == ClaudeCliManager.ProcessState.ERROR) {
            streaming = false;
        }
        updateDisplay();
    }

    // ==================== IConversationListener ====================

    @Override
    public void onSessionInitialized(SessionInfo info) {
        currentModel = (info != null) ? info.getModel() : null;
        updateDisplay();
    }

    @Override
    public void onAssistantMessageStarted(MessageBlock block) {
        streaming = true;
        updateDisplay();
    }

    @Override
    public void onAssistantMessageCompleted(MessageBlock block) {
        streaming = false;
        updateDisplay();
    }

    @Override
    public void onResultReceived(UsageInfo usage) {
        streaming = false;
        updateDisplay();
    }

    @Override
    public void onError(String error) {
        streaming = false;
        updateDisplay();
    }

    @Override
    public void onConversationCleared() {
        streaming = false;
        currentModel = null;
        updateDisplay();
    }

    // ==================== Display update ====================

    private void updateDisplay() {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return;

        display.asyncExec(() -> {
            if (statusLabel == null || statusLabel.isDisposed()) return;

            ClaudeCliManager cliManager = attachedCliManager;
            ClaudeCliManager.ProcessState state = (cliManager != null)
                    ? cliManager.getState()
                    : ClaudeCliManager.ProcessState.NOT_STARTED;

            String text;
            Color color;

            switch (state) {
                case NOT_STARTED:
                case STOPPED:
                    if (cliManager != null && !cliManager.isCliAvailable()) {
                        text = "Claude: Not installed";
                    } else {
                        text = "Claude: Disconnected";
                    }
                    color = grayColor;
                    break;
                case STARTING:
                    text = "Claude: Connecting\u2026";
                    color = yellowColor;
                    break;
                case RUNNING:
                    if (streaming) {
                        text = "Claude: Thinking\u2026";
                    } else {
                        text = "Claude: Ready";
                    }
                    if (currentModel != null && !currentModel.isBlank()) {
                        text += " [" + currentModel + "]";
                    }
                    color = streaming ? blueColor : greenColor;
                    break;
                case STOPPING:
                    text = "Claude: Stopping\u2026";
                    color = yellowColor;
                    break;
                case ERROR:
                    text = "Claude: Error";
                    color = redColor;
                    break;
                default:
                    text = "Claude";
                    color = grayColor;
            }

            statusLabel.setText(text);
            if (color != null && !color.isDisposed()) {
                statusLabel.setForeground(color);
            }
            if (!container.isDisposed()) {
                container.layout(true);
            }
        });
    }

}
