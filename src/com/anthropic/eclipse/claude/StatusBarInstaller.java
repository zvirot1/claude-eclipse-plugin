package com.anthropic.eclipse.claude;

import java.lang.reflect.Method;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.anthropic.eclipse.claude.views.ClaudeStatusBarContribution;

/**
 * Installs {@link ClaudeStatusBarContribution} programmatically on each
 * workbench window's status line via {@code org.eclipse.ui.startup}.
 *
 * <p>The declarative {@code menuContribution} route targeting
 * {@code toolbar:org.eclipse.ui.trim.status} does not work reliably in
 * Eclipse 4.x — the e4 compatibility layer silently drops the contribution.
 * This class works around the issue by adding the control directly to the
 * {@link IStatusLineManager} obtained from WorkbenchWindow via reflection.
 */
public class StatusBarInstaller implements IStartup {

    private static final String ITEM_ID = "com.anthropic.eclipse.claude.statusbar";

    @Override
    public void earlyStartup() {
        Display.getDefault().asyncExec(this::install);
    }

    private void install() {
        try {
            IWorkbench wb = PlatformUI.getWorkbench();
            for (IWorkbenchWindow window : wb.getWorkbenchWindows()) {
                installOn(window);
            }
        } catch (Throwable t) {
            Activator.logError("StatusBarInstaller failed", t);
        }
    }

    private void installOn(IWorkbenchWindow window) {
        try {
            // WorkbenchWindow.getStatusLineManager() is public but on an
            // internal class — use reflection to avoid compile-time dependency.
            Method m = window.getClass().getMethod("getStatusLineManager");
            Object slm = m.invoke(window);
            if (!(slm instanceof IStatusLineManager)) {
                Activator.logWarning("Unexpected status line manager type: " + slm);
                return;
            }
            IStatusLineManager statusLine = (IStatusLineManager) slm;
            if (statusLine.find(ITEM_ID) != null) {
                return; // already installed
            }

            ClaudeStatusBarContribution item = new ClaudeStatusBarContribution();
            item.setId(ITEM_ID);
            statusLine.add(item);
            statusLine.update(true);
            Activator.logInfo("Claude status bar installed on window");
        } catch (NoSuchMethodException e) {
            Activator.logError("WorkbenchWindow.getStatusLineManager() not available", e);
        } catch (Throwable t) {
            Activator.logError("Failed to install Claude status bar on window", t);
        }
    }
}
