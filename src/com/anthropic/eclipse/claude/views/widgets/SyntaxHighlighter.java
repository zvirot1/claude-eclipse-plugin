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

    // ======================== Java via JDT IScanner (reflection) ========================
    // Uses reflection to avoid compile-time dependency on JDT classes that may
    // change between Eclipse versions (e.g. InvalidInputException removed in 4.35).

    private static StyleRange[] highlightJava(String code, ThemeManager tm,
                                               org.eclipse.swt.widgets.Widget colorOwner) {
        try {
            return doHighlightJavaReflection(code, tm, colorOwner);
        } catch (Exception e) {
            // JDT not available or scan error — fall back to regex
            return highlightWithRegex(code, "java", tm, colorOwner);
        }
    }

    private static StyleRange[] doHighlightJavaReflection(String code, ThemeManager tm,
                                                  org.eclipse.swt.widgets.Widget colorOwner) throws Exception {
        // Get scanner via reflection
        Class<?> toolFactoryClass = Class.forName("org.eclipse.jdt.core.ToolFactory");
        java.lang.reflect.Method createScanner = toolFactoryClass.getMethod("createScanner",
                boolean.class, boolean.class, boolean.class, String.class);
        Object scanner = createScanner.invoke(null, true, true, true, "21");

        // Get IScanner methods
        Class<?> scannerClass = scanner.getClass();
        java.lang.reflect.Method setSource = scannerClass.getMethod("setSource", char[].class);
        java.lang.reflect.Method getNextToken = scannerClass.getMethod("getNextToken");
        java.lang.reflect.Method getCurrentTokenStart = scannerClass.getMethod("getCurrentTokenStartPosition");
        java.lang.reflect.Method getCurrentTokenEnd = scannerClass.getMethod("getCurrentTokenEndPosition");

        // Get token constants via reflection
        Class<?> termSymbols = Class.forName("org.eclipse.jdt.core.compiler.ITerminalSymbols");
        int tokenEOF = getStaticInt(termSymbols, "TokenNameEOF");

        // Build token-to-color map
        Map<Integer, RGB> tokenColorMap = buildTokenColorMap(termSymbols, tm);
        Set<Integer> commentTokens = buildCommentTokenSet(termSymbols);

        setSource.invoke(scanner, (Object) code.toCharArray());

        List<StyleRange> styles = new ArrayList<>();
        while (true) {
            int token;
            try {
                token = (Integer) getNextToken.invoke(scanner);
            } catch (Exception e) {
                break;
            }
            if (token == tokenEOF) break;

            int start = (Integer) getCurrentTokenStart.invoke(scanner);
            int end = (Integer) getCurrentTokenEnd.invoke(scanner);
            int length = end - start + 1;

            RGB colorRgb = tokenColorMap.get(token);
            if (colorRgb != null) {
                Color color = tm.getColor(colorRgb);
                colorOwner.addDisposeListener(e -> color.dispose());
                StyleRange sr = new StyleRange(start, length, color, null);
                if (commentTokens.contains(token)) {
                    sr.fontStyle = SWT.ITALIC;
                }
                styles.add(sr);
            }
        }
        return styles.toArray(new StyleRange[0]);
    }

    private static int getStaticInt(Class<?> cls, String fieldName) {
        try {
            return cls.getField(fieldName).getInt(null);
        } catch (Exception e) {
            return -999;
        }
    }

    private static Map<Integer, RGB> buildTokenColorMap(Class<?> termSymbols, ThemeManager tm) {
        Map<Integer, RGB> map = new HashMap<>();
        // Keywords
        String[] keywords = {
            "TokenNameabstract", "TokenNameassert", "TokenNamebreak", "TokenNamecase",
            "TokenNamecatch", "TokenNameclass", "TokenNamecontinue", "TokenNamedefault",
            "TokenNamedo", "TokenNameelse", "TokenNameenum", "TokenNameextends",
            "TokenNamefinal", "TokenNamefinally", "TokenNamefor", "TokenNameif",
            "TokenNameimplements", "TokenNameimport", "TokenNameinstanceof",
            "TokenNameinterface", "TokenNamenew", "TokenNamepackage", "TokenNameprivate",
            "TokenNameprotected", "TokenNamepublic", "TokenNamereturn", "TokenNamestatic",
            "TokenNamesuper", "TokenNameswitch", "TokenNamesynchronized", "TokenNamethis",
            "TokenNamethrow", "TokenNamethrows", "TokenNametransient", "TokenNametry",
            "TokenNamevolatile", "TokenNamewhile"
        };
        for (String k : keywords) map.put(getStaticInt(termSymbols, k), tm.syntaxKeyword);
        // Types
        String[] types = {
            "TokenNameboolean", "TokenNamebyte", "TokenNamechar", "TokenNamedouble",
            "TokenNamefloat", "TokenNameint", "TokenNamelong", "TokenNameshort",
            "TokenNamevoid", "TokenNamenull", "TokenNametrue", "TokenNamefalse"
        };
        for (String t : types) map.put(getStaticInt(termSymbols, t), tm.syntaxType);
        // Strings
        String[] strings = {"TokenNameStringLiteral", "TokenNameCharacterLiteral", "TokenNameTextBlock"};
        for (String s : strings) map.put(getStaticInt(termSymbols, s), tm.syntaxString);
        // Numbers
        String[] numbers = {"TokenNameIntegerLiteral", "TokenNameLongLiteral",
            "TokenNameFloatingPointLiteral", "TokenNameDoubleLiteral"};
        for (String n : numbers) map.put(getStaticInt(termSymbols, n), tm.syntaxNumber);
        // Comments
        String[] comments = {"TokenNameCOMMENT_LINE", "TokenNameCOMMENT_BLOCK", "TokenNameCOMMENT_JAVADOC"};
        for (String c : comments) map.put(getStaticInt(termSymbols, c), tm.syntaxComment);
        // Annotations
        map.put(getStaticInt(termSymbols, "TokenNameAT"), tm.syntaxAnnotation);
        // Remove invalid entries
        map.remove(-999);
        return map;
    }

    private static Set<Integer> buildCommentTokenSet(Class<?> termSymbols) {
        Set<Integer> set = new HashSet<>();
        set.add(getStaticInt(termSymbols, "TokenNameCOMMENT_LINE"));
        set.add(getStaticInt(termSymbols, "TokenNameCOMMENT_BLOCK"));
        set.add(getStaticInt(termSymbols, "TokenNameCOMMENT_JAVADOC"));
        set.remove(-999);
        return set;
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
