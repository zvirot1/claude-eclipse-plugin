# Claude AI Plugin for Eclipse (Unofficial)

פלגין Eclipse לא רשמי לשימוש ב-Claude AI ישירות בתוך ה-IDE.

## תכונות

- **Chat Panel** — שיחה מרובת סבבים עם Claude ישירות בתוך Eclipse
- **Send to Claude** — שלח קוד נבחר לניתוח בלחיצה ימנית
- **Explain Code** — קבל הסבר מפורט על קוד נבחר
- **Review Code** — בקשת Code Review אוטומטי
- **Keyboard Shortcuts** — `Ctrl+Shift+C` לפתיחת צ'אט, `Ctrl+Shift+S` לשליחת selection
- **מדויק ל-COBOL** — System Prompt מוכוון לעזרה בקוד COBOL

---

## הכנסה ל-Eclipse (Import)

### שיטה 1: Import כ-Existing Project (הכי פשוט)

1. פתח Eclipse
2. `File > Import > General > Existing Projects into Workspace`
3. בחר את תיקיית `claude-eclipse-plugin`
4. לחץ `Finish`

### שיטה 2: בנייה עם Maven Tycho

דרישות: Maven 3.8+, Java 11+

```bash
cd claude-eclipse-plugin
mvn clean package
```

הקובץ המוכן יהיה ב: `target/com.anthropic.eclipse.claude-1.0.0-SNAPSHOT.jar`

---

## הכנה להרצה (Run Plugin)

### מתוך Eclipse PDE:

1. ייבא את הפרויקט ל-Eclipse
2. פתח את `plugin.xml`
3. לחץ `Launch an Eclipse Application` (בחלונית Overview)
4. Eclipse חדש יפתח עם הפלגין מותקן

### הגדרת API Key:

1. בחלון Eclipse עם הפלגין: `Window > Preferences > Claude AI`
2. הכנס את Anthropic API Key שלך
   - קבל מ: https://console.anthropic.com/
3. בחר מודל (ברירת מחדל: claude-sonnet-4-5)
4. לחץ `Apply and Close`

---

## שימוש

### פתיחת Chat Panel
- תפריט: `Claude AI > Open Chat Panel`
- קיצור: `Ctrl+Shift+C`
- או: `Window > Show View > Other > Claude AI > Claude AI Chat`

### שליחת קוד לניתוח
1. סמן קוד בעורך
2. לחץ ימני → `Claude AI > Send to Claude`
   (או `Explain this Code` / `Review this Code`)

---

## מבנה הפרויקט

```
claude-eclipse-plugin/
├── src/
│   └── com/anthropic/eclipse/claude/
│       ├── Activator.java              ← Plugin entry point
│       ├── api/
│       │   └── ClaudeApiClient.java    ← Anthropic API calls
│       ├── views/
│       │   └── ClaudeChatView.java     ← Main chat UI
│       ├── handlers/
│       │   ├── HandlerUtils.java       ← Shared utilities
│       │   ├── OpenChatHandler.java    ← Open chat command
│       │   ├── SendSelectionHandler.java
│       │   ├── ExplainCodeHandler.java
│       │   └── ReviewCodeHandler.java
│       └── preferences/
│           ├── PreferenceConstants.java
│           ├── PreferenceInitializer.java
│           └── ClaudePreferencePage.java
├── META-INF/MANIFEST.MF               ← OSGi bundle descriptor
├── plugin.xml                          ← Extension points
├── build.properties
├── pom.xml                             ← Maven Tycho build
└── README.md
```

---

## פיתוח עתידי (TODO)

- [ ] Streaming responses (תצוגה בזמן אמת)
- [ ] Diff viewer להצגת שינויי קוד
- [ ] שמירת היסטוריית שיחות
- [ ] אינטגרציה עם Claude Code CLI
- [ ] תמיכה ב-COBOL syntax highlighting בתשובות
- [ ] File context — שליחת קבצים שלמים לניתוח

---

## דרישות

- Eclipse IDE 2022-06 ומעלה
- Java 11+
- Anthropic API Key (https://console.anthropic.com/)
