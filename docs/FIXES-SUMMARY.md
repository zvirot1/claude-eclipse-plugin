# סיכום תיקונים ושיפורים — Claude Eclipse Plugin

מסמך זה מסכם את כל התיקונים שבוצעו בגרסאות `1.0.0.202604261148` עד `1.0.0.202604281430`. **ניתן ליישם בכל IDE plugin** (IntelliJ, VS Code, JetBrains וכו') שצורך את ה-Claude CLI במצב stream-json.

---

## 1️⃣ Send Button: כפתור אדום נשאר ארוך מדי אחרי תשובה

### תופעה
לאחר שהטקסט סיים להופיע למשתמש, הכפתור נשאר ב"מצב Stop" עוד 10-15 שניות עד שחוזר ל"Send".

### שורש הבעיה
המעבר back-to-send בוצע רק ב-`onResultReceived` (הודעת `result` של ה-CLI). הודעת ה-result מגיעה מאוחר אחרי ש-`message_stop` מסיים — בעיקר ברשתות איטיות / corporate proxy.

### תיקון
לבצע את המעבר ב-`onAssistantMessageCompleted` כשאין tools פעילים (`hasRunningToolCalls() == false`). אם תוך כדי תור יש tool_use intermediate — להישאר אדום.

```java
public void onAssistantMessageCompleted(MessageBlock block) {
    if (!model.hasRunningToolCalls()) {
        // switch button to send mode immediately
        hideThinkingIndicator();
        stopButton.setEnabled(false);
        setSendButtonToSend();
    }
    // existing finalize logic continues...
}
```

### למה זה עובד
- בתורים פשוטים (ללא tools) — חוסך 12-15 שניות של חיכוי
- בתורים עם tools — `hasRunningToolCalls()` מחזיר `true`, מתנהג כמו לפני
- safety net נשאר ב-`onResultReceived`

---

## 2️⃣ כפילות טקסט — "HelloHello" race

### תופעה
לפעמים תשובה של CLI מופיעה **פעמיים בתוך אותה בועת הודעה**:
```
"Hello! How can I assist you today?Hello! How can I assist you today?"
```

### שורש הבעיה (race condition מורכב)
1. UI thread עמוס (rendering, layout)
2. `onAssistantMessageStarted` queues asyncExec ליצירת widget
3. בינתיים על background thread: stream events של text מגיעים, queue מוסיף asyncExec של appendStreamingText
4. בינתיים `message_stop` מגיע → `MessageBlock.isComplete()` נהיה `true`
5. סוף סוף ה-UI thread מתחיל לעבד queue:
   - יוצר widget → ה-constructor רואה `isComplete()==true` → קורא ל-`renderExistingContent()` → דוחף את כל הטקסט המלא
   - מריץ asyncExecs של append מה-deltas → דוחף שוב את אותו טקסט
6. תוצאה: טקסט מוכפל

### תיקון
ב-`appendStreamingText`, לדלג על דלתות מאוחרות אם הבלוק כבר completed:

```java
public void appendStreamingText(String delta) {
    if (isDisposed() || finalized) return;
    // Race fix: constructor already wrote the full text via renderExistingContent
    if (hasStreamedText && messageBlock.isComplete()) return;
    ensureTextWidget();
    currentTextWidget.appendText(delta);
    hasStreamedText = true;
}
```

---

## 3️⃣ זיהוי `system` messages לפי subtype

### תופעה
הפלאגין החשיב **כל** הודעת `"type":"system"` כ-`SystemInit`. בפועל ה-CLI שולח גם `hook_started` / `hook_progress` / `hook_response` / `compact_boundary` עם `type:"system"`. כל אלה הוצגו כאילו הם init מזויפים, ופרטי ה-hook events אבדו.

### תיקון
פיצול לפי `subtype` בעת ה-parse:

```java
case "system":
    String subtype = JsonParser.getString(json, "subtype");
    if (subtype == null || "init".equals(subtype)) {
        return parseSystemInit(json);
    }
    return parseSystemNotification(json, jsonLine);
```

`SystemNotification` הוא class חדש עם השדות:
- `subtype`, `hookName`, `hookEvent`, `hookId`
- `stdout`, `stderr`, `exitCode`, `outcome`
- `sessionId`, `rawJson`
- מתודה `hasErrorIndicator()` שמזהה patterns כמו `[error]`, `token has expired`, `unauthorized`, `permission denied`, וכן exitCode != 0

### תועלת
- הוקים מסוג SessionStart שמדווחים שגיאות AWS SSO / token / proxy מופיעים למשתמש כשגיאה ברורה
- אבחון אמיתי של בעיות auth במקום "תשובה ריקה"

---

## 4️⃣ Surfacing hook errors למשתמש (AWS SSO וכו')

### תופעה
ה-AIM hook (corporate) שולח hook_response עם `outcome:"success"` אך ב-`stderr` שגיאת Token expired:
```
aws: [ERROR]: Error when retrieving token from sso: Token has expired and refresh failed
```
ה-CLI מתעלם וממשיך, אבל הקריאה ל-Bedrock נכשלת בשתיקה → result ריק.

### תיקון
ב-`handleSystemNotification` של המודל, לזהות hook_response עם `hasErrorIndicator()` ולהציג למשתמש:

```java
private void handleSystemNotification(CliMessage.SystemNotification n) {
    if (!"hook_response".equals(n.getSubtype())) return;
    if (n.hasErrorIndicator()) {
        lastErrorNotification = n;
        String detail = n.getStderr() != null ? n.getStderr() : n.getStdout();
        String hint = "";
        if (detail.toLowerCase().contains("token has expired") || detail.toLowerCase().contains("sso")) {
            hint = "\nFix: refresh your AWS SSO token (run `aws sso login`) and reopen this Claude tab.";
        } else if (detail.toLowerCase().contains("unauthorized")) {
            hint = "\nFix: re-authenticate (check API key / SSO session).";
        }
        fireError("⚠ Hook '" + n.getHookName() + "' reported an error:\n" + detail + hint);
    }
}
```

---

## 5️⃣ זיהוי "Silent Empty Result"

### תופעה
לפעמים ה-CLI מחזיר result עם:
- `result: ""`
- `output_tokens: 0`
- `is_error: false, subtype: "success"`
- אין שום `AssistantMessage` או `StreamEvent` בתור

זו **חתימה ייחודית** של חסימה ע"י `UserPromptSubmit` hook (כמו "obfuscation attack detected"). ה-CLI לא חושף את הסיבה ב-stream-json.

### תיקון
הוספת state tracking במודל:
```java
private volatile boolean hadTextInCurrentTurn = false;
private volatile CliMessage.SystemNotification lastErrorNotification;
private volatile String lastUserPrompt;
private volatile boolean retriedSilentEmpty;
```
- `hadTextInCurrentTurn = true` כל פעם ש-text_delta מגיע
- מאופס ב-`addUserMessage`

ב-`handleResult`:
```java
boolean silentEmpty = !hadTextInCurrentTurn
        && (resultText == null || resultText.isEmpty())
        && result.getOutputTokens() == 0;
if (silentEmpty) { /* ... handle ... */ }
```

---

## 6️⃣ Auto-Retry פעם אחת על Silent Empty

### תופעה
ה-AIM hook **לא דטרמיניסטי**: אותו prompt נחסם פעם אחת ועובר פעם אחרת (Random ML classifier / rate limiting / proxy timeout).

### תיקון
על silent-empty ראשון של תור — לבצע retry אוטומטי **אחד**. רק אם גם ה-retry נכשל — להציג שגיאה.

#### במודל
```java
// In handleResult, when silentEmpty detected:
if (!retriedSilentEmpty && lastUserPrompt != null && !lastUserPrompt.isEmpty()
        && lastErrorNotification == null) {
    retriedSilentEmpty = true;       // prevent infinite loop
    hadTextInCurrentTurn = false;    // reset for retry
    lastErrorNotification = null;
    fireSilentEmptyShouldRetry(lastUserPrompt);
    return;
}
// otherwise → fire error
```

#### בממשק
חדש ב-`IConversationListener`:
```java
default void onSilentEmptyShouldRetry(String lastUserPrompt) {}
```

#### ב-View
```java
public void onSilentEmptyShouldRetry(String prompt) {
    asyncExec(() -> {
        if (cliManager.isRunning()) {
            cliManager.sendMessage(prompt);
            stopButton.setEnabled(true);
            setSendButtonToStop();
        }
    });
}
```

### תועלת
- רוב מקרי החסימות הלא-עקביות **בלתי-נראים** למשתמש
- חסימה אמיתית עדיין מציגה הודעה ברורה אחרי retry

---

## 7️⃣ הודעת שגיאה ידידותית על חסימת hook

### תופעה
לפני התיקון: "Empty response from Claude". משתמש לא יודע מה לעשות.

### תיקון
הודעה מפורטת:
```
⚠ Your prompt was blocked [after one auto-retry] by a hook.

The CLI returned an empty response with 0 tokens used (duration=Xms, turns=Y). 
This is the typical signature of a UserPromptSubmit hook (e.g. corporate 
"obfuscation attack" detector flagging Hebrew/RTL text). The hook can be 
non-deterministic — [we already retried automatically and it was blocked again, 
so this prompt is consistently rejected. | this attempt was rejected.]

To see the exact reason, run in cmd:
   claude --debug
   <your prompt>

Workarounds: rephrase in English, try again later, or ask your IT/AWS admin 
to relax the hook rule.
```

---

## 8️⃣ דגל דיאגנוסטיקה גלובלי

### תכונה
דגל יחיד `Activator.DIAG_ENABLED` שמאפשר לכבות/להפעיל לוגים מפורטים בלי build חדש.

### יישום
```java
public static volatile boolean DIAG_ENABLED = Boolean.getBoolean("claude.diag");

public static void logDiag(String message) {
    if (DIAG_ENABLED) {
        log(IStatus.INFO, message, null);
    }
}
```

### שתי דרכי הפעלה
1. **System property** ב-`eclipse.ini` / `idea.vmoptions`:
   ```
   -Dclaude.diag=true
   ```
2. **Preference UI** עם BooleanFieldEditor — קוראים ל-`getPreferenceStore().addPropertyChangeListener` כדי לעדכן בזמן ריצה.

### תגיות לוג
- `[DIAG-MSG]` — כל הודעת CLI שמתקבלת
- `[DIAG-LISTENER]` — add/remove listeners + identity hash
- `[DIAG-FLAG]` — שינויי flags פנימיים
- `[DIAG-MODEL]` — יצירה/החלפה של ConversationModel
- `[DIAG-TIMING]` — T0/T1/T2/T3 timestamps של תור
- `[DIAG-RAW-SYSTEM]` — תוכן מלא של system messages
- `[DIAG-RAW-RESULT]` — תוכן מלא של result כשresult ריק
- `[DIAG-STDERR]` — שורות stderr של ה-CLI

### חשוב
- **לא להפעיל `--debug` של ה-CLI ב-default** — הוא משבש streaming (interleaving עם stdout)
- אם רוצים גישה ל-`~/.claude/debug/<session>.txt` — להפעיל `--debug` רק כש-DIAG_ENABLED

---

## 9️⃣ MCP Global Servers — קריאה/כתיבה למקום הנכון

### תופעה
דיאלוג "Global Servers (~/.claude.json)" לא הציג שרתים שנוספו ע"י `claude mcp add --scope user`.

### שורש הבעיה
הפלאגין קרא מ-`~/.claude.json → projects[currentDir].mcpServers`, אבל ה-CLI שומר ב-**root-level** `~/.claude.json → mcpServers`.

### תיקון
```java
private void loadGlobalServers() {
    Map<String, Object> root = parseJson(globalClaudePath);
    
    // 1. Read root-level mcpServers (CLI's --scope user location)
    Map<String, Object> rootServers = JsonParser.getMap(root, "mcpServers");
    if (rootServers != null) {
        for (entry : rootServers.entrySet()) addServerToTable(...);
    }
    
    // 2. Also read legacy projects[currentDir].mcpServers (old location)
    //    De-duplicate by name
    Map<String, Object> projects = JsonParser.getMap(root, "projects");
    Map<String, Object> project = JsonParser.getMap(projects, projectDir);
    if (project != null) {
        Map<String, Object> projServers = JsonParser.getMap(project, "mcpServers");
        if (projServers != null) {
            for (entry : projServers.entrySet()) {
                if (rootServers != null && rootServers.containsKey(entry.getKey())) continue;
                addServerToTable(...);
            }
        }
    }
}
```

`saveGlobalServers` כותב ל-root-level וגם מנקה duplicates ב-legacy location.

---

## 🔟 שונות קטנים

| תיקון | שינוי |
|---|---|
| **Race ב-`start()`** של CLI manager | `start()` ייצא עם no-op אם state כבר RUNNING **או** STARTING (לא רק RUNNING). מונע race שיוצר תהליכי CLI מרובים בעת startup איטי. |
| **`autoStartCli` ב-View** | בודק גם `state == STARTING` ולא מחפש להפעיל פעם נוספת. |
| **stderr capture** | תיוג עם `[DIAG-STDERR]` בנוסף ל-`[Claude CLI stderr]` ב-info log. |

---

## סיכום קצר ליישום ב-IntelliJ

עבור IntelliJ, ה-architecture דומה אבל ה-API שונה:
- **`asyncExec` → `ApplicationManager.getApplication().invokeLater(...)`** עבור UI updates
- **`Display.timerExec` → `Alarm` או `EdtScheduledExecutorService`**
- **`IPreferenceStore` → `PropertiesComponent` או `PersistentStateComponent`**
- **`Composite/StyledText` → `JComponent / JTextPane / EditorEx`** של IntelliJ
- **Listener pattern → MessageBus topic / EventDispatcher**

הלוגיקה של 9 התיקונים העיקריים (1-9) **זהה** — רק מחליפים את ה-rendering וה-threading.

ה**קוד הקריטי** שאינו תלוי-IDE:
1. `CliMessage` ויריאנטים (כולל `SystemNotification`)
2. `NdjsonProtocolHandler.parseLine` (split system by subtype)
3. `ConversationModel.handleResult` + state tracking (silent-empty + retry)
4. `Activator.logDiag` + flag

אלה copy-paste כמעט ללא שינוי.
