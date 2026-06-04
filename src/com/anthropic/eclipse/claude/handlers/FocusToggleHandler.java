package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.views.ClaudeConversationView;
import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

/**
 * Toggles keyboard focus between the active editor and the Claude Code panel.
 * Bound to M1+ESCAPE (Ctrl+Esc / Cmd+Esc).
 *
 * - If a Claude panel (V2 webview, or legacy V1) already has focus →
 *   return focus to the active editor.
 * - Otherwise → bring up the V2 panel (falling back to V1) and give it focus.
 */
public class FocusToggleHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) return null;

        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;

        // If EITHER the V2 webview view or the legacy V1 view currently has
        // focus, return focus to the active editor.
        IViewPart v2View = page.findView(ClaudeConversationViewV2.ID);
        IViewPart v1View = page.findView(ClaudeConversationView.ID);
        Object active = page.getActivePart();
        if ((v2View != null && active == v2View) || (v1View != null && active == v1View)) {
            if (page.getActiveEditor() != null) {
                page.activate(page.getActiveEditor());
                page.getActiveEditor().setFocus();
            }
            return null;
        }

        // Otherwise, open (or show) the Claude view and focus it. Try V2
        // first — that's the default since the webview migration — and
        // fall back to V1 only if V2 fails to instantiate (e.g. Edge
        // WebView2 unavailable on this machine).
        try {
            IViewPart view = page.showView(ClaudeConversationViewV2.ID);
            if (view != null) {
                view.setFocus();
                return null;
            }
        } catch (PartInitException e) {
            Activator.logError("[FocusToggleHandler] Could not open V2 Claude view, trying V1", e);
        }
        try {
            IViewPart view = page.showView(ClaudeConversationView.ID);
            if (view != null) {
                view.setFocus();
            }
        } catch (PartInitException e) {
            Activator.logError("[FocusToggleHandler] Could not open Claude view", e);
        }
        return null;
    }
}
