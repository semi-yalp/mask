package io.sqlmask.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits multi-statement SQL input on top-level semicolons, following
 * PostgreSQL lexical rules: single-quoted strings (including {@code E'...'}
 * backslash escapes and doubled quotes), quoted identifiers, dollar-quoted
 * strings ({@code $$...$$}, {@code $tag$...$tag$}) and comments
 * ({@code --} line comments and nestable block comments) never contribute
 * split points. Blank statements are dropped; statement order is preserved;
 * returned statements carry no trailing semicolon.
 */
public final class SqlStatementSplitter {

  public List<String> split(String sql) {
    List<String> statements = new ArrayList<>();
    if (sql == null || sql.isBlank()) {
      return statements;
    }
    Cursor cursor = new Cursor(sql);
    int statementStart = 0;
    while (cursor.hasNext()) {
      char c = cursor.peek();
      switch (c) {
        case '\'' -> cursor.skipSingleQuoted();
        case '"' -> cursor.skipDoubleQuoted();
        case '$' -> cursor.skipDollarQuoted();
        case '-' -> cursor.skipLineComment();
        case '/' -> cursor.skipBlockComment();
        case ';' -> {
          addStatement(statements, sql, statementStart, cursor.pos);
          statementStart = cursor.next();
          cursor.advance();
        }
        default -> cursor.advance();
      }
    }
    addStatement(statements, sql, statementStart, sql.length());
    return statements;
  }

  private static void addStatement(List<String> statements, String sql, int start, int end) {
    String statement = sql.substring(start, end).trim();
    if (!statement.isEmpty()) {
      statements.add(statement);
    }
  }

  private static final class Cursor {
    private final String sql;
    private final int length;
    private int pos;

    Cursor(String sql) {
      this.sql = sql;
      this.length = sql.length();
    }

    boolean hasNext() {
      return pos < length;
    }

    char peek() {
      return sql.charAt(pos);
    }

    void advance() {
      pos++;
    }

    int next() {
      return pos + 1;
    }

    private char charAt(int index) {
      return index >= 0 && index < length ? sql.charAt(index) : '\0';
    }

    private boolean isWordChar(char c) {
      return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** True when the quote at {@code quotePos} is an {@code E'...'} literal quote. */
    private boolean escapedString(int quotePos) {
      int e = quotePos - 1;
      if (e < 0) {
        return false;
      }
      char c = sql.charAt(e);
      return (c == 'e' || c == 'E') && !isWordChar(charAt(e - 1));
    }

    void skipSingleQuoted() {
      boolean escaped = escapedString(pos);
      advance(); // opening quote
      while (hasNext()) {
        char c = peek();
        if (escaped && c == '\\') {
          advance();
          if (hasNext()) {
            advance();
          }
          continue;
        }
        if (c == '\'') {
          advance();
          if (hasNext() && peek() == '\'') {
            advance(); // doubled quote stays inside the string
            continue;
          }
          return;
        }
        advance();
      }
    }

    void skipDoubleQuoted() {
      advance();
      while (hasNext()) {
        char c = peek();
        if (c == '"') {
          advance();
          if (hasNext() && peek() == '"') {
            advance();
            continue;
          }
          return;
        }
        advance();
      }
    }

    /** Skips {@code $$...$$} / {@code $tag$...$tag$} dollar-quoted strings. */
    void skipDollarQuoted() {
      String tag = dollarTagAt(pos);
      if (tag == null) {
        advance();
        return;
      }
      int contentStart = pos + tag.length();
      int end = sql.indexOf(tag, contentStart);
      if (end < 0) {
        pos = length; // unterminated dollar quote consumes the rest
        return;
      }
      pos = end + tag.length();
    }

    private String dollarTagAt(int at) {
      if (charAt(at + 1) == '$') {
        return "$$";
      }
      int close = sql.indexOf('$', at + 1);
      if (close < 0) {
        return null;
      }
      String inner = sql.substring(at + 1, close);
      if (inner.isEmpty() || !Character.isLetter(inner.charAt(0))) {
        return null;
      }
      for (int i = 1; i < inner.length(); i++) {
        char c = inner.charAt(i);
        if (!Character.isLetterOrDigit(c) && c != '_') {
          return null;
        }
      }
      return sql.substring(at, close + 1);
    }

    void skipLineComment() {
      if (charAt(pos + 1) != '-') {
        advance();
        return;
      }
      int newline = sql.indexOf('\n', pos);
      pos = newline < 0 ? length : newline + 1;
    }

    void skipBlockComment() {
      if (charAt(pos + 1) != '*') {
        advance();
        return;
      }
      int depth = 1;
      pos += 2;
      while (hasNext() && depth > 0) {
        if (peek() == '/' && charAt(pos + 1) == '*') {
          depth++;
          pos += 2;
        } else if (peek() == '*' && charAt(pos + 1) == '/') {
          depth--;
          pos += 2;
        } else {
          advance();
        }
      }
    }
  }
}
