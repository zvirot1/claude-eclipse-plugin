package com.anthropic.eclipse.claude.views.widgets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;

/**
 * Syntax highlighting for code blocks.
 * Java: uses Eclipse JDT IScanner (if available) for 100% accuracy.
 * Other languages: regex-based highlighting for keywords, strings, comments, numbers.
 */
public class SyntaxHighlighter {

    private static final boolean HAS_JDT = checkJdt();

    private static boolean checkJdt() {
        try {
            Class.forName("org.eclipse.jdt.core.ToolFactory");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Highlight code and return StyleRange array for a StyledText widget.
     * Colors are NOT disposed by this class — caller must manage Color lifecycle.
     */
    public static StyleRange[] highlight(String code, String language, ThemeManager tm,
                                          org.eclipse.swt.widgets.Widget colorOwner) {
        if (code == null || code.isEmpty() || language == null || language.isEmpty()) {
            return new StyleRange[0];
        }

        String lang = language.toLowerCase().trim();

        if (("java".equals(lang)) && HAS_JDT) {
            return highlightJava(code, tm, colorOwner);
        }
        return highlightWithRegex(code, lang, tm, colorOwner);
    }

    // ======================== Java via JDT IScanner ========================

    private static StyleRange[] highlightJava(String code, ThemeManager tm,
                                               org.eclipse.swt.widgets.Widget colorOwner) {
        try {
            return doHighlightJava(code, tm, colorOwner);
        } catch (Exception e) {
            // JDT not available or scan error — fall back to regex
            return highlightWithRegex(code, "java", tm, colorOwner);
        }
    }

    private static StyleRange[] doHighlightJava(String code, ThemeManager tm,
                                                  org.eclipse.swt.widgets.Widget colorOwner) throws Exception {
        org.eclipse.jdt.core.compiler.IScanner scanner =
            org.eclipse.jdt.core.ToolFactory.createScanner(
                true,  // tokenizeComments
                true,  // tokenizeWhiteSpace
                true,  // recordLineSeparator
                "21"   // sourceLevel
            );
        scanner.setSource(code.toCharArray());

        List<StyleRange> styles = new ArrayList<>();
        int token;
        while (true) {
            try {
                token = scanner.getNextToken();
            } catch (org.eclipse.jdt.core.compiler.InvalidInputException e) {
                break;
            }
            if (token == org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameEOF) break;

            int start = scanner.getCurrentTokenStartPosition();
            int end = scanner.getCurrentTokenEndPosition();
            int length = end - start + 1;

            RGB colorRgb = mapJdtTokenToColor(token, tm);
            if (colorRgb != null) {
                Color color = tm.getColor(colorRgb);
                colorOwner.addDisposeListener(e -> color.dispose());
                StyleRange sr = new StyleRange(start, length, color, null);
                if (isJdtCommentToken(token)) {
                    sr.fontStyle = SWT.ITALIC;
                }
                styles.add(sr);
            }
        }
        return styles.toArray(new StyleRange[0]);
    }

    @SuppressWarnings("deprecation")
    private static RGB mapJdtTokenToColor(int token, ThemeManager tm) {
        // Keywords
        switch (token) {
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameabstract:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameassert:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamebreak:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamecase:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamecatch:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameclass:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamecontinue:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamedefault:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamedo:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameelse:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameenum:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameextends:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamefinal:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamefinally:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamefor:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameif:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameimplements:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameimport:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameinstanceof:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameinterface:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamenew:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamepackage:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameprivate:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameprotected:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamepublic:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamereturn:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamestatic:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamesuper:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameswitch:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamesynchronized:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamethis:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamethrow:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamethrows:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNametransient:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNametry:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamevolatile:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamewhile:
                return tm.syntaxKeyword;

            // Primitive types
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameboolean:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamebyte:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamechar:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamedouble:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamefloat:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameint:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamelong:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameshort:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamevoid:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamenull:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNametrue:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNamefalse:
                return tm.syntaxType;

            // Strings
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameStringLiteral:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameCharacterLiteral:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameTextBlock:
                return tm.syntaxString;

            // Numbers
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameIntegerLiteral:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLongLiteral:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameFloatingPointLiteral:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameDoubleLiteral:
                return tm.syntaxNumber;

            // Comments
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameCOMMENT_LINE:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameCOMMENT_BLOCK:
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameCOMMENT_JAVADOC:
                return tm.syntaxComment;

            // Annotations (@Override, etc.)
            case org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameAT:
                return tm.syntaxAnnotation;

            default:
                return null;
        }
    }

    private static boolean isJdtCommentToken(int token) {
        return token == org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameCOMMENT_LINE
            || token == org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameCOMMENT_BLOCK
            || token == org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameCOMMENT_JAVADOC;
    }

    // ======================== Regex-based highlighting ========================

    private static final Map<String, Set<String>> KEYWORDS_BY_LANG = new HashMap<>();
    static {
        KEYWORDS_BY_LANG.put("java", new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "var", "record", "sealed", "permits",
            "yield", "null", "true", "false")));

        KEYWORDS_BY_LANG.put("python", new HashSet<>(Arrays.asList(
            "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
            "or", "pass", "raise", "return", "try", "while", "with", "yield",
            "None", "True", "False", "self")));

        Set<String> jsKeywords = new HashSet<>(Arrays.asList(
            "async", "await", "break", "case", "catch", "class", "const", "continue",
            "debugger", "default", "delete", "do", "else", "export", "extends",
            "finally", "for", "function", "if", "import", "in", "instanceof", "let",
            "new", "of", "return", "static", "super", "switch", "this", "throw",
            "try", "typeof", "var", "void", "while", "with", "yield",
            "null", "undefined", "true", "false", "NaN"));
        KEYWORDS_BY_LANG.put("javascript", jsKeywords);
        KEYWORDS_BY_LANG.put("js", jsKeywords);
        KEYWORDS_BY_LANG.put("typescript", jsKeywords);
        KEYWORDS_BY_LANG.put("ts", jsKeywords);
        KEYWORDS_BY_LANG.put("tsx", jsKeywords);
        KEYWORDS_BY_LANG.put("jsx", jsKeywords);

        KEYWORDS_BY_LANG.put("go", new HashSet<>(Arrays.asList(
            "break", "case", "chan", "const", "continue", "default", "defer", "else",
            "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
            "map", "package", "range", "return", "select", "struct", "switch", "type",
            "var", "nil", "true", "false")));

        KEYWORDS_BY_LANG.put("rust", new HashSet<>(Arrays.asList(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn",
            "else", "enum", "extern", "fn", "for", "if", "impl", "in", "let",
            "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self",
            "Self", "static", "struct", "super", "trait", "type", "unsafe", "use",
            "where", "while", "true", "false", "None", "Some")));

        Set<String> cKeywords = new HashSet<>(Arrays.asList(
            "auto", "break", "case", "char", "const", "continue", "default", "do",
            "double", "else", "enum", "extern", "float", "for", "goto", "if",
            "inline", "int", "long", "register", "return", "short", "signed",
            "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned",
            "void", "volatile", "while",
            "class", "namespace", "template", "typename", "virtual", "override",
            "public", "private", "protected", "new", "delete", "this", "throw",
            "try", "catch", "using", "include", "define", "ifdef", "endif",
            "nullptr", "true", "false", "NULL"));
        KEYWORDS_BY_LANG.put("c", cKeywords);
        KEYWORDS_BY_LANG.put("cpp", cKeywords);
        KEYWORDS_BY_LANG.put("c++", cKeywords);
        KEYWORDS_BY_LANG.put("h", cKeywords);

        KEYWORDS_BY_LANG.put("shell", new HashSet<>(Arrays.asList(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
            "case", "esac", "function", "return", "exit", "export", "local",
            "readonly", "declare", "typeset", "source", "alias", "unalias")));
        KEYWORDS_BY_LANG.put("bash", KEYWORDS_BY_LANG.get("shell"));
        KEYWORDS_BY_LANG.put("sh", KEYWORDS_BY_LANG.get("shell"));
        KEYWORDS_BY_LANG.put("zsh", KEYWORDS_BY_LANG.get("shell"));
    }

    // Comment style per language
    private static boolean usesHashComments(String lang) {
        return "python".equals(lang) || "shell".equals(lang) || "bash".equals(lang)
            || "sh".equals(lang) || "zsh".equals(lang) || "ruby".equals(lang)
            || "yaml".equals(lang) || "yml".equals(lang);
    }

    private static boolean usesSlashComments(String lang) {
        return "java".equals(lang) || "javascript".equals(lang) || "js".equals(lang)
            || "typescript".equals(lang) || "ts".equals(lang) || "tsx".equals(lang)
            || "jsx".equals(lang) || "go".equals(lang) || "rust".equals(lang)
            || "c".equals(lang) || "cpp".equals(lang) || "c++".equals(lang)
            || "h".equals(lang);
    }

    private static StyleRange[] highlightWithRegex(String code, String lang, ThemeManager tm,
                                                     org.eclipse.swt.widgets.Widget colorOwner) {
        Set<String> keywords = KEYWORDS_BY_LANG.get(lang);
        if (keywords == null && !usesHashComments(lang) && !usesSlashComments(lang)) {
            return new StyleRange[0]; // unknown language
        }
        if (keywords == null) keywords = new HashSet<>();

        // Track which character positions are already styled (to avoid overlaps)
        boolean[] styled = new boolean[code.length()];
        List<StyleRange> styles = new ArrayList<>();

        // 1. Strings (highest priority — keywords inside strings should not be colored)
        addStringStyles(code, styled, styles, tm, colorOwner);

        // 2. Comments
        if (usesSlashComments(lang)) {
            addPatternStyles(code, Pattern.compile("//[^\n]*"), styled, styles,
                tm.syntaxComment, SWT.ITALIC, tm, colorOwner);
            addPatternStyles(code, Pattern.compile("/\\*[\\s\\S]*?\\*/", Pattern.DOTALL), styled, styles,
                tm.syntaxComment, SWT.ITALIC, tm, colorOwner);
        }
        if (usesHashComments(lang)) {
            addPatternStyles(code, Pattern.compile("#[^\n]*"), styled, styles,
                tm.syntaxComment, SWT.ITALIC, tm, colorOwner);
        }
        if ("python".equals(lang)) {
            // Triple-quoted strings (used as docstrings)
            addPatternStyles(code, Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"", Pattern.DOTALL), styled, styles,
                tm.syntaxString, SWT.NORMAL, tm, colorOwner);
            addPatternStyles(code, Pattern.compile("'''[\\s\\S]*?'''", Pattern.DOTALL), styled, styles,
                tm.syntaxString, SWT.NORMAL, tm, colorOwner);
        }

        // 3. Annotations (@Override, @decorator)
        addPatternStyles(code, Pattern.compile("@\\w+"), styled, styles,
            tm.syntaxAnnotation, SWT.NORMAL, tm, colorOwner);

        // 4. Numbers
        addPatternStyles(code, Pattern.compile("\\b\\d+(\\.\\d+)?[fFdDlL]?\\b"), styled, styles,
            tm.syntaxNumber, SWT.NORMAL, tm, colorOwner);

        // 5. Keywords (word-boundary match)
        if (!keywords.isEmpty()) {
            Pattern kwPattern = Pattern.compile("\\b(" + String.join("|", keywords) + ")\\b");
            addPatternStyles(code, kwPattern, styled, styles,
                tm.syntaxKeyword, SWT.BOLD, tm, colorOwner);
        }

        return styles.toArray(new StyleRange[0]);
    }

    private static void addStringStyles(String code, boolean[] styled, List<StyleRange> styles,
                                          ThemeManager tm, org.eclipse.swt.widgets.Widget colorOwner) {
        // Match double-quoted and single-quoted strings (handling escaped quotes)
        Pattern stringPattern = Pattern.compile(
            "\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'");
        addPatternStyles(code, stringPattern, styled, styles,
            tm.syntaxString, SWT.NORMAL, tm, colorOwner);
    }

    private static void addPatternStyles(String code, Pattern pattern, boolean[] styled,
                                           List<StyleRange> styles, RGB colorRgb, int fontStyle,
                                           ThemeManager tm, org.eclipse.swt.widgets.Widget colorOwner) {
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            // Skip if any position in this range is already styled
            boolean overlap = false;
            for (int i = start; i < end && i < styled.length; i++) {
                if (styled[i]) { overlap = true; break; }
            }
            if (overlap) continue;

            // Mark positions as styled
            for (int i = start; i < end && i < styled.length; i++) {
                styled[i] = true;
            }

            Color color = tm.getColor(colorRgb);
            colorOwner.addDisposeListener(e -> color.dispose());
            StyleRange sr = new StyleRange(start, end - start, color, null);
            sr.fontStyle = fontStyle;
            styles.add(sr);
        }
    }
}
