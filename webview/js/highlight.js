/**
 * Lightweight syntax highlighter for Claude Code IntelliJ plugin.
 * Supports common languages without external dependencies.
 * Inspired by highlight.js but much smaller (~5KB).
 */
(function () {
    'use strict';

    var KEYWORDS = {
        java: 'abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while var yield record sealed permits',
        javascript: 'async await break case catch class const continue debugger default delete do else export extends finally for from function get if import in instanceof let new of return set static super switch this throw try typeof var void while with yield',
        typescript: 'abstract as async await break case catch class const continue declare default delete do else enum export extends finally for from function get if implements import in instanceof interface let module namespace new of package private protected public readonly return set static super switch this throw try type typeof var void while with yield',
        python: 'False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield',
        go: 'break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var',
        rust: 'as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self static struct super trait true type unsafe use where while',
        kotlin: 'abstract actual annotation as break by catch class companion const constructor continue crossinline data do else enum expect external final finally for fun get if import in infix init inline inner interface internal is it lateinit noinline object open operator out override package private protected public reified return sealed set super suspend tailrec this throw to try typealias val var vararg when where while',
        sql: 'ADD ALL ALTER AND AS ASC BETWEEN BY CASE CHECK COLUMN CONSTRAINT CREATE DATABASE DEFAULT DELETE DESC DISTINCT DROP ELSE END EXEC EXISTS FOREIGN FROM FULL GROUP HAVING IF IN INDEX INNER INSERT INTO IS JOIN KEY LEFT LIKE LIMIT NOT NULL ON OR ORDER OUTER PRIMARY PROCEDURE RIGHT ROWNUM SELECT SET TABLE TOP TRUNCATE UNION UNIQUE UPDATE VALUES VIEW WHERE',
        bash: 'alias bg bind break builtin caller case cd command compgen complete compopt continue coproc declare dirs disown do done echo elif else enable esac eval exec exit export false fc fg fi for function getopts hash help history if in jobs kill let local logout mapfile popd printf pushd pwd read readarray readonly return select set shift shopt source suspend test then time times trap true type typeset ulimit umask unalias unset until wait while',
        css: 'align-content align-items align-self all animation appearance background border bottom box-shadow box-sizing clear clip color columns content cursor direction display filter flex float font gap grid height justify-content justify-items left letter-spacing line-height list-style margin max-height max-width min-height min-width object-fit opacity order outline overflow padding pointer-events position right text-align text-decoration text-overflow text-transform top transform transition visibility white-space width word-break word-wrap z-index',
        c: 'auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while _Bool _Complex _Imaginary',
        cpp: 'alignas alignof and and_eq asm auto bitand bitor bool break case catch char char8_t char16_t char32_t class compl concept const consteval constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new noexcept not not_eq nullptr operator or or_eq private protected public register reinterpret_cast requires return short signed sizeof static static_assert static_cast struct switch template this thread_local throw true try typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq'
    };

    // Language aliases
    var LANG_MAP = {
        js: 'javascript', ts: 'typescript', py: 'python',
        sh: 'bash', shell: 'bash', zsh: 'bash',
        'c++': 'cpp', 'c#': 'cpp', csharp: 'cpp',
        rb: 'python', ruby: 'python', // similar keyword highlighting
        yml: 'yaml', dockerfile: 'bash', makefile: 'bash',
        kt: 'kotlin', kts: 'kotlin'
    };

    function escapeRegExp(str) {
        return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    function buildKeywordRegex(lang) {
        var resolved = LANG_MAP[lang] || lang;
        var kw = KEYWORDS[resolved];
        if (!kw) return null;
        var words = kw.split(/\s+/).sort(function (a, b) { return b.length - a.length; });
        return new RegExp('\\b(' + words.join('|') + ')\\b', 'g');
    }

    /**
     * Highlight a code string for a given language.
     * Returns HTML with <span class="hljs-*"> wrappers.
     */
    function highlight(code, lang) {
        if (!lang) return code;
        lang = lang.toLowerCase().trim();

        // JSON: special handling
        if (lang === 'json' || lang === 'jsonc') {
            return highlightJson(code);
        }

        // XML/HTML: special handling
        if (lang === 'xml' || lang === 'html' || lang === 'svg' || lang === 'xhtml') {
            return highlightXml(code);
        }

        var kwRegex = buildKeywordRegex(lang);

        // Tokenize into segments to avoid highlighting inside strings/comments
        var tokens = tokenize(code, lang);
        var result = '';
        for (var i = 0; i < tokens.length; i++) {
            var tok = tokens[i];
            switch (tok.type) {
                case 'string':
                    result += '<span class="hljs-string">' + tok.text + '</span>';
                    break;
                case 'comment':
                    result += '<span class="hljs-comment">' + tok.text + '</span>';
                    break;
                case 'number':
                    result += '<span class="hljs-number">' + tok.text + '</span>';
                    break;
                case 'code':
                    var segment = tok.text;
                    if (kwRegex) {
                        segment = segment.replace(kwRegex, '<span class="hljs-keyword">$1</span>');
                    }
                    // Highlight function calls: word followed by (
                    segment = segment.replace(/\b([a-zA-Z_]\w*)\s*(?=\()/g, '<span class="hljs-title">$1</span>');
                    // Highlight decorators/annotations
                    segment = segment.replace(/@([a-zA-Z_]\w*)/g, '<span class="hljs-meta">@$1</span>');
                    result += segment;
                    break;
                default:
                    result += tok.text;
            }
        }
        return result;
    }

    function tokenize(code, lang) {
        var tokens = [];
        var i = 0;
        var len = code.length;
        var buf = '';
        var isPython = (LANG_MAP[lang] || lang) === 'python';
        var isBash = (LANG_MAP[lang] || lang) === 'bash';

        while (i < len) {
            var ch = code[i];
            var ch2 = i + 1 < len ? code[i + 1] : '';

            // Line comments: // or #
            if ((ch === '/' && ch2 === '/' && !isBash) || (ch === '#' && (isBash || isPython))) {
                if (buf) { tokens.push({ type: 'code', text: buf }); buf = ''; }
                var end = code.indexOf('\n', i);
                if (end === -1) end = len;
                tokens.push({ type: 'comment', text: code.substring(i, end) });
                i = end;
                continue;
            }

            // Block comments: /* ... */
            if (ch === '/' && ch2 === '*') {
                if (buf) { tokens.push({ type: 'code', text: buf }); buf = ''; }
                var end = code.indexOf('*/', i + 2);
                if (end === -1) end = len - 2;
                tokens.push({ type: 'comment', text: code.substring(i, end + 2) });
                i = end + 2;
                continue;
            }

            // Strings: single, double, backtick
            if (ch === '"' || ch === "'" || ch === '`') {
                if (buf) { tokens.push({ type: 'code', text: buf }); buf = ''; }
                var strEnd = findStringEnd(code, i);
                tokens.push({ type: 'string', text: code.substring(i, strEnd) });
                i = strEnd;
                continue;
            }

            // Numbers: digits (simple)
            if (/\d/.test(ch) && (i === 0 || !/[a-zA-Z_]/.test(code[i - 1]))) {
                if (buf) { tokens.push({ type: 'code', text: buf }); buf = ''; }
                var numStr = '';
                while (i < len && /[\d.xXa-fA-FeEoObBn_]/.test(code[i])) {
                    numStr += code[i];
                    i++;
                }
                tokens.push({ type: 'number', text: numStr });
                continue;
            }

            buf += ch;
            i++;
        }
        if (buf) tokens.push({ type: 'code', text: buf });
        return tokens;
    }

    function findStringEnd(code, start) {
        var quote = code[start];
        var i = start + 1;
        while (i < code.length) {
            if (code[i] === '\\') { i += 2; continue; }
            if (code[i] === quote) { return i + 1; }
            if (quote !== '`' && code[i] === '\n') { return i; }
            i++;
        }
        return code.length;
    }

    function highlightJson(code) {
        // Keys, strings, numbers, booleans, null
        return code
            .replace(/"([^"\\]|\\.)*"\s*:/g, '<span class="hljs-attr">$&</span>')
            .replace(/"([^"\\]|\\.)*"/g, '<span class="hljs-string">$&</span>')
            .replace(/\b(true|false|null)\b/g, '<span class="hljs-keyword">$1</span>')
            .replace(/\b(\d+\.?\d*([eE][+-]?\d+)?)\b/g, '<span class="hljs-number">$1</span>');
    }

    function highlightXml(code) {
        return code
            .replace(/(&lt;\/?)([\w:-]+)/g, '$1<span class="hljs-tag">$2</span>')
            .replace(/([\w:-]+)(=)/g, '<span class="hljs-attr">$1</span>$2')
            .replace(/"([^"]*)"/g, '<span class="hljs-string">"$1"</span>')
            .replace(/'([^']*)'/g, '<span class="hljs-string">\'$1\'</span>')
            .replace(/(&lt;!--[\s\S]*?--&gt;)/g, '<span class="hljs-comment">$1</span>');
    }

    /**
     * Auto-apply highlighting to a DOM element containing a <code> tag.
     */
    function highlightElement(el, lang) {
        if (!el) return;
        var codeEl = el.tagName === 'CODE' ? el : el.querySelector('code');
        if (!codeEl) return;
        var raw = codeEl.textContent || '';
        codeEl.innerHTML = highlight(raw, lang);
        codeEl.classList.add('hljs');
    }

    // Export
    window.syntaxHighlight = {
        highlight: highlight,
        highlightElement: highlightElement
    };
})();
