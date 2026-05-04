# מדריך הגדרת MCP JDBC Server עבור Claude

מדריך זה מסביר כיצד להתקין ולהגדיר את [OpenLink MCP JDBC Server](https://github.com/OpenLinkSoftware/mcp-jdbc-server) — שרת MCP מבוסס Java/Quarkus שמאפשר ל-Claude להתחבר לכל מסד נתונים שיש לו דרייבר JDBC (Virtuoso, Oracle, PostgreSQL, MySQL, SQL Server, Informix וכו').

---

## 1. דרישות מקדימות

| רכיב | גרסה | הערה |
|------|------|------|
| Java JDK | **21 ומעלה** | חובה |
| Git | אחרון | להורדת המקור |
| Claude Desktop / Claude Code | אחרון | הצרכן של ה-MCP |
| דרייבר JDBC | תלוי-DB | רק עבור מסדים שאינם Virtuoso |

בדיקה מהירה שה-Java תקין:
```bash
java -version
```
הפלט צריך להראות `21` ומעלה.

---

## 2. הורדה והתקנה

```bash
git clone https://github.com/OpenLinkSoftware/mcp-jdbc-server.git
cd mcp-jdbc-server
```

הריפו כולל את ה-JAR המוכן: `MCPServer-1.0.0-runner.jar` — אין צורך לבנות אותו אלא אם רוצים גרסה מותאמת.

### בנייה אופציונלית (Gradle)
```bash
./gradlew build         # Linux / macOS
gradlew.bat build       # Windows
```
ה-JAR המתקבל יושב תחת `build/quarkus-app/` או בשורש הפרויקט (תלוי-Quarkus).

---

## 3. משתני סביבה

קובץ `.env` בתיקיית הפרויקט קובע את ברירות המחדל:

```properties
jdbc.url=jdbc:virtuoso://localhost:1111
jdbc.user=dba
jdbc.password=dba
jdbc.api_key=xxx
```

ניתן להגדיר את אותם הערכים גם דרך `env` בקובץ ה-MCP של Claude (ראו סעיף הבא) — וזה מנצח את ה-`.env`.

---

## 4. הגדרת Claude

### 4.1 איפה הקובץ?

| לקוח | מיקום הקובץ |
|------|--------------|
| Claude Desktop (macOS) | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Claude Desktop (Windows) | `%APPDATA%\Claude\claude_desktop_config.json` |
| Claude Code | `~/.claude/settings.json` (או דרך `/mcp` בתוך Claude Code) |

### 4.2 התצורה הבסיסית — Virtuoso

```json
{
  "mcpServers": {
    "my_database": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/mcp-jdbc-server/MCPServer-1.0.0-runner.jar"
      ],
      "env": {
        "jdbc.url": "jdbc:virtuoso://localhost:1111",
        "jdbc.user": "username",
        "jdbc.password": "password",
        "jdbc.api_key": "sk-xxx"
      }
    }
  }
}
```

### 4.3 תצורה עם דרייבר JDBC חיצוני (Oracle / PostgreSQL / MySQL / SQL Server)

יש להוסיף את ה-JAR של הדרייבר ל-classpath ולעבור ל-`-cp` במקום `-jar`:

```json
{
  "mcpServers": {
    "postgres_db": {
      "command": "java",
      "args": [
        "-cp",
        "/path/to/mcp-jdbc-server/MCPServer-1.0.0-runner.jar:/path/to/postgresql-42.7.3.jar",
        "io.quarkus.runner.GeneratedMain"
      ],
      "env": {
        "jdbc.url": "jdbc:postgresql://localhost:5432/mydb",
        "jdbc.user": "postgres",
        "jdbc.password": "secret"
      }
    }
  }
}
```

> **Windows**: המפריד ב-classpath הוא `;` במקום `:` — לדוגמה: `"MCPServer-1.0.0-runner.jar;C:\\drivers\\postgresql.jar"`.

### 4.4 דוגמאות URL לפי DB

| DB | jdbc.url |
|----|----------|
| Virtuoso | `jdbc:virtuoso://localhost:1111` |
| PostgreSQL | `jdbc:postgresql://host:5432/dbname` |
| MySQL | `jdbc:mysql://host:3306/dbname` |
| Oracle | `jdbc:oracle:thin:@host:1521:SID` |
| SQL Server | `jdbc:sqlserver://host:1433;databaseName=dbname` |
| Informix | `jdbc:informix-sqli://host:1526/dbname:INFORMIXSERVER=server` |

### 4.5 חיבור למספר מסדים במקביל

כל מסד מקבל מפתח משלו תחת `mcpServers`:

```json
{
  "mcpServers": {
    "prod_oracle": { "command": "java", "args": [...], "env": { "jdbc.url": "jdbc:oracle:thin:@..." } },
    "dev_postgres": { "command": "java", "args": [...], "env": { "jdbc.url": "jdbc:postgresql://..." } }
  }
}
```

---

## 5. הכלים הזמינים

לאחר הפעלת השרת, Claude יקבל גישה לכלים הבאים (קידומת `jdbc_`):

| כלי | תפקיד |
|-----|-------|
| `jdbc_get_schemas` | החזרת כל ה-schemas במסד |
| `jdbc_get_tables` | רשימת טבלאות ב-schema מסוים |
| `jdbc_describe_table` | מטא-דאטה של עמודות בטבלה (טיפוסים, NULL, מפתחות) |
| `jdbc_filter_table_names` | חיפוש טבלאות לפי שם חלקי |
| `jdbc_query_database` | הרצת SQL והחזרת JSON |
| `jdbc_execute_query_md` | הרצת SQL והחזרת טבלת Markdown |
| `jdbc_spasql_query` | SPASQL — Virtuoso בלבד |
| `jdbc_sparql_query` | SPARQL — Virtuoso בלבד |
| `jdbc_virtuoso_support_ai` | אינטגרציה עם Virtuoso AI Assistant |

---

## 6. בדיקה ופתרון תקלות

### 6.1 MCP Inspector — הכלי הרשמי לדיבוג
```bash
npm install -g @modelcontextprotocol/inspector
npx @modelcontextprotocol/inspector java -jar /path/to/MCPServer-1.0.0-runner.jar
```

עם דרייברים נוספים:
```bash
export CLASSPATH=$CLASSPATH:/path/to/driver1.jar:/path/to/driver2.jar
npx @modelcontextprotocol/inspector \
  java -cp MCPServer-1.0.0-runner.jar:/path/to/drivers/* io.quarkus.runner.GeneratedMain
```

### 6.2 בעיות נפוצות

| תופעה | סיבה סבירה | פתרון |
|-------|-------------|-------|
| `ClassNotFoundException` על דרייבר | הדרייבר לא ב-classpath | להוסיף את ה-JAR ל-`-cp` |
| `UnsupportedClassVersionError` | Java < 21 | להתקין JDK 21+ |
| Claude לא רואה את השרת | הקובץ לא נטען | לוודא נתיב מלא ל-JAR + restart לקליינט |
| `Connection refused` | DB סגור / port שגוי | לוודא ש-DB רץ ושהפורט נכון ב-`jdbc.url` |
| הרשאות חסרות | משתמש בלי GRANT | לעדכן `jdbc.user` למשתמש עם הרשאות מתאימות |

### 6.3 לוגים
Claude Desktop כותב לוגים תחת:
- macOS: `~/Library/Logs/Claude/`
- Windows: `%APPDATA%\Claude\logs\`

חפשו שורות עם השם שנתתם ל-server (`my_database` בדוגמה) כדי לראות את stdout/stderr של תהליך ה-Java.

---

## 7. אבטחה — לא לדלג

- **לעולם לא** להגדיר משתמש עם `DBA`/`SYSDBA`/`root` לשימוש יומי — ליצור משתמש read-only ייעודי.
- אין לחתום קובץ הגדרות עם סיסמאות לתוך Git.
- ה-`jdbc_query_database` מריץ SQL גולמי שמגיע מ-Claude — מומלץ לעבוד מול replica או DB פיתוח.
- להחזיק את `MCPServer-1.0.0-runner.jar` מעודכן (`git pull` תקופתי).

---

## 8. מקורות

- ריפו רשמי: <https://github.com/OpenLinkSoftware/mcp-jdbc-server>
- מפרט MCP: <https://modelcontextprotocol.io>
- MCP Inspector: <https://github.com/modelcontextprotocol/inspector>
