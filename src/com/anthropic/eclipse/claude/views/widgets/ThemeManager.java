package com.anthropic.eclipse.claude.views.widgets;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.preferences.PreferenceConstants;

/**
 * Central theme manager for the Claude plugin.
 * Provides all color definitions for light and dark themes.
 * Detects Eclipse's current theme by default, with a preference override.
 *
 * Usage: ThemeManager tm = ThemeManager.getInstance();
 *        Color bg = tm.getColor(tm.viewBg);
 *
 * Colors are cached per-instance and disposed when dispose() is called.
 * Each widget composite should call ThemeManager.getInstance() and keep
 * a reference, then dispose it in its own dispose listener.
 */
public class ThemeManager {

    // Theme mode constants (matches preference values)
    public static final String MODE_AUTO = "auto";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    private final boolean darkMode;
    private final Display display;

    // ==================== Color Definitions ====================
    // Access these as fields, then call getColor(rgb) to get the SWT Color.

    // View background
    public final RGB viewBg;
    // Input field background
    public final RGB inputBg;
    // Input field text
    public final RGB inputText;

    // Connection status colors
    public final RGB connectedColor;
    public final RGB disconnectedColor;
    public final RGB errorColor;

    // Title and toolbar
    public final RGB titleColor;
    public final RGB dimTextColor;

    // Message backgrounds
    public final RGB userMessageBg;
    public final RGB assistantMessageBg;
    public final RGB errorMessageBg;
    public final RGB systemMessageBg;

    // Message text
    public final RGB roleTextColor;
    public final RGB bodyTextColor;

    // Tool call widget
    public final RGB toolBg;
    public final RGB toolHeaderBg;
    public final RGB toolHeaderText;
    public final RGB toolToggleColor;
    public final RGB toolDetailsText;
    public final RGB toolRunningColor;
    public final RGB toolCompletedColor;
    public final RGB toolFailedColor;

    // Code block (theme-aware — dark in dark mode, light in light mode)
    public final RGB codeBg;
    public final RGB codeHeaderBg;
    public final RGB codeText;
    public final RGB codeLangText;

    // Syntax highlighting (theme-aware — Dark+ in dark mode, Light+ in light mode)
    public final RGB syntaxKeyword;
    public final RGB syntaxString;
    public final RGB syntaxComment;
    public final RGB syntaxType;
    public final RGB syntaxNumber;
    public final RGB syntaxAnnotation;

    // Cost status bar
    public final RGB statusBarBg;
    public final RGB statusBarText;
    public final RGB statusBarAccent;

    // Permission banner (warm yellow - same for both themes)
    public final RGB permissionBg;
    public final RGB permissionBorder;
    public final RGB permissionText;

    // Hint/context line text
    public final RGB hintText;

    // Attachment chip
    public final RGB chipBg;

    // Floating popup (mode selector, autocomplete) — same palette for both
    public final RGB popupBg;
    public final RGB popupText;
    public final RGB popupBorder;
    public final RGB popupHoverBg;
    public final RGB popupAccent;      // filled dot in effort slider, active highlight
    public final RGB popupAccentDim;   // unselected dots in effort slider

    // ==================== Construction ====================

    private ThemeManager(Display display, boolean darkMode) {
        this.display = display;
        this.darkMode = darkMode;

        if (darkMode) {
            // ---- Dark Theme ----
            viewBg = new RGB(28, 28, 30);
            inputBg = new RGB(44, 44, 48);
            inputText = new RGB(220, 220, 220);

            connectedColor = new RGB(75, 180, 100);
            disconnectedColor = new RGB(120, 120, 120);
            errorColor = new RGB(248, 81, 73);

            titleColor = new RGB(220, 220, 225);
            dimTextColor = new RGB(140, 140, 145);

            userMessageBg = new RGB(36, 36, 40);
            assistantMessageBg = new RGB(28, 28, 28);
            errorMessageBg = new RGB(50, 20, 20);
            systemMessageBg = new RGB(32, 32, 36);

            roleTextColor = new RGB(180, 180, 185);
            bodyTextColor = new RGB(212, 212, 212);

            toolBg = new RGB(32, 32, 36);
            toolHeaderBg = new RGB(40, 40, 46);
            toolHeaderText = new RGB(190, 190, 195);
            toolToggleColor = new RGB(140, 140, 145);
            toolDetailsText = new RGB(170, 170, 175);
            toolRunningColor = new RGB(230, 165, 30);
            toolCompletedColor = new RGB(75, 180, 100);
            toolFailedColor = new RGB(248, 81, 73);

            codeBg = new RGB(30, 30, 30);
            codeHeaderBg = new RGB(45, 45, 45);
            codeText = new RGB(212, 212, 212);
            codeLangText = new RGB(150, 150, 150);

            // VS Code Dark+ palette
            syntaxKeyword    = new RGB(86, 156, 214);   // #569CD6 blue
            syntaxString     = new RGB(206, 145, 120);  // #CE9178 orange-brown
            syntaxComment    = new RGB(106, 153, 85);   // #6A9955 green
            syntaxType       = new RGB(78, 201, 176);   // #4EC9B0 teal
            syntaxNumber     = new RGB(181, 206, 168);  // #B5CEA8 light green
            syntaxAnnotation = new RGB(220, 220, 170);  // #DCDCAA yellow

            statusBarBg = new RGB(24, 24, 24);
            statusBarText = new RGB(120, 120, 120);
            statusBarAccent = new RGB(75, 180, 100);

            permissionBg = new RGB(60, 50, 20);
            permissionBorder = new RGB(180, 140, 20);
            permissionText = new RGB(240, 200, 80);

            hintText = new RGB(120, 120, 125);
            chipBg = new RGB(50, 60, 80);

            popupBg       = new RGB(42, 42, 48);
            popupText     = new RGB(220, 220, 220);
            popupBorder   = new RGB(70, 70, 76);
            popupHoverBg  = new RGB(56, 56, 62);
            popupAccent   = new RGB(220, 220, 220);
            popupAccentDim = new RGB(100, 100, 108);
        } else {
            // ---- Light Theme ----
            viewBg = new RGB(246, 246, 246);
            inputBg = new RGB(255, 255, 255);
            inputText = new RGB(30, 30, 30);

            connectedColor = new RGB(46, 160, 67);
            disconnectedColor = new RGB(160, 160, 160);
            errorColor = new RGB(220, 53, 69);

            titleColor = new RGB(50, 50, 50);
            dimTextColor = new RGB(120, 120, 120);

            userMessageBg = new RGB(240, 243, 249);
            assistantMessageBg = new RGB(255, 255, 255);
            errorMessageBg = new RGB(255, 235, 235);
            systemMessageBg = new RGB(248, 248, 250);

            roleTextColor = new RGB(60, 60, 60);
            bodyTextColor = new RGB(30, 30, 30);

            toolBg = new RGB(242, 242, 246);
            toolHeaderBg = new RGB(232, 232, 238);
            toolHeaderText = new RGB(50, 50, 55);
            toolToggleColor = new RGB(90, 90, 95);
            toolDetailsText = new RGB(60, 60, 65);
            toolRunningColor = new RGB(200, 140, 20);
            toolCompletedColor = new RGB(40, 140, 70);
            toolFailedColor = new RGB(200, 50, 50);

            // Code blocks adapt to the surrounding theme — light background
            // with darker text, like VS Code Light+ and the GitHub light code style.
            codeBg = new RGB(246, 248, 250);     // off-white
            codeHeaderBg = new RGB(232, 234, 238);
            codeText = new RGB(36, 41, 46);      // near-black
            codeLangText = new RGB(106, 115, 125);

            // VS Code Light+ palette (works on the light background above)
            syntaxKeyword    = new RGB(0, 0, 255);     // #0000FF blue
            syntaxString     = new RGB(163, 21, 21);   // #A31515 dark red
            syntaxComment    = new RGB(0, 128, 0);     // #008000 green
            syntaxType       = new RGB(38, 127, 153);  // #267F99 teal
            syntaxNumber     = new RGB(9, 134, 88);    // #098658 green
            syntaxAnnotation = new RGB(121, 94, 38);   // #795E26 brown

            statusBarBg = new RGB(240, 240, 242);
            statusBarText = new RGB(100, 100, 100);
            statusBarAccent = new RGB(40, 140, 70);

            permissionBg = new RGB(255, 248, 230);
            permissionBorder = new RGB(255, 193, 7);
            permissionText = new RGB(133, 100, 4);

            hintText = new RGB(100, 100, 100);
            chipBg = new RGB(220, 235, 255);

            popupBg       = new RGB(252, 252, 253);
            popupText     = new RGB(30, 30, 30);
            popupBorder   = new RGB(210, 210, 215);
            popupHoverBg  = new RGB(238, 238, 242);
            popupAccent   = new RGB(40, 40, 40);
            popupAccentDim = new RGB(185, 185, 190);
        }
    }

    // ==================== Factory ====================

    /**
     * Create a ThemeManager instance using the current preference and Eclipse theme.
     */
    public static ThemeManager getInstance() {
        Display display = Display.getDefault();
        boolean dark = isDarkMode(display);
        return new ThemeManager(display, dark);
    }

    /**
     * Detect whether dark mode should be used.
     * Priority: 1) Preference override, 2) Eclipse theme detection.
     */
    public static boolean isDarkMode(Display display) {
        // 1. Check preference override
        try {
            Activator activator = Activator.getDefault();
            if (activator != null) {
                IPreferenceStore store = activator.getPreferenceStore();
                String mode = store.getString(PreferenceConstants.THEME_MODE);
                if (MODE_LIGHT.equals(mode)) return false;
                if (MODE_DARK.equals(mode)) return true;
                // "auto" or empty -> fall through to detection
            }
        } catch (Exception ignored) {}

        // 2. Detect Eclipse theme by examining the widget background color
        // If the system background is dark (luminance < 0.5), we're in dark mode.
        try {
            Color sysBg = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
            if (sysBg != null) {
                double luminance = (0.299 * sysBg.getRed()
                    + 0.587 * sysBg.getGreen()
                    + 0.114 * sysBg.getBlue()) / 255.0;
                return luminance < 0.5;
            }
        } catch (Exception ignored) {}

        // Default to light
        return false;
    }

    // ==================== Color Access ====================

    /**
     * Create an SWT Color from an RGB value.
     * IMPORTANT: The caller is responsible for disposing the returned Color.
     */
    public Color getColor(RGB rgb) {
        return new Color(display, rgb);
    }

    /**
     * Check if we're in dark mode.
     */
    public boolean isDark() {
        return darkMode;
    }

    // ==================== Font Names ====================

    /** Primary UI font name. */
    public String getUIFontName() {
        return "Helvetica Neue";
    }

    /** Monospace font name for code. */
    public String getMonoFontName() {
        return "Menlo";
    }
}
