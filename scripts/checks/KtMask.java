// The oracle for check_conventions.py's _code_mask(). Lexes each .kt file with the Kotlin
// compiler's own lexer and prints one span per token, classified as CODE or TEXT.
//
// Run through `java -cp <jars> KtMask.java <files...>` — single-file source mode, so there is no
// compiled artefact to cache or invalidate. It costs about a second per run, against a whole-tree
// lex of ~0.2s; a compile cache would buy that second back and cost a staleness bug.
//
// Two jars are required: kotlin-compiler-embeddable AND kotlin-stdlib. javac links fine against
// the first alone, but IElementType's static init needs the second, so the omission surfaces only
// at runtime. Note also that the IntelliJ classes here are shaded — org.jetbrains.kotlin.com.
// intellij.*, not com.intellij.*, which is what every example on the web will give you.
//
// Output, one line per token:  <startCodePoint> <endCodePoint> CODE|TEXT
// Offsets are code-point indices, not Java's UTF-16 units, because Python indexes strings by code
// point; the two diverge on any astral character and the mismatch looks like a scanner bug.

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.jetbrains.kotlin.com.intellij.psi.TokenType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.lexer.KotlinLexer;
import org.jetbrains.kotlin.lexer.KtTokens;

public final class KtMask {

    // Everything a string literal, a char literal or a comment owns — the characters the gate's
    // rules must not see. Delimiters (quotes, template markers) count as TEXT: they are not code a
    // rule could fire on, and _code_mask() blanks them too. The two only have to agree.
    //
    // Compared by identity, never by name: DOC_COMMENT.toString() is "KDoc", so a name-based table
    // would silently drop KDoc into CODE.
    private static final Set<IElementType> TEXT = new HashSet<>(java.util.Arrays.asList(
            KtTokens.BLOCK_COMMENT,
            KtTokens.EOL_COMMENT,
            KtTokens.SHEBANG_COMMENT,
            KtTokens.DOC_COMMENT,
            KtTokens.OPEN_QUOTE,
            KtTokens.CLOSING_QUOTE,
            KtTokens.REGULAR_STRING_PART,
            KtTokens.ESCAPE_SEQUENCE,
            KtTokens.CHARACTER_LITERAL,
            KtTokens.SHORT_TEMPLATE_ENTRY_START,
            KtTokens.LONG_TEMPLATE_ENTRY_START,
            KtTokens.LONG_TEMPLATE_ENTRY_END,
            KtTokens.INTERPOLATION_PREFIX));

    // The tokens that are code but belong to no KtTokens TokenSet. Keywords and operators are
    // handled below; this is the remainder.
    private static final Set<IElementType> CODE = new HashSet<>(java.util.Arrays.asList(
            TokenType.WHITE_SPACE,
            TokenType.BAD_CHARACTER,
            KtTokens.DANGLING_NEWLINE,
            KtTokens.IDENTIFIER,
            KtTokens.FIELD_IDENTIFIER));

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        KotlinLexer lexer = new KotlinLexer();
        for (String arg : args) {
            Path path = Path.of(arg);
            String src = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            out.println("# " + path);
            lexer.start(src);
            int codePoint = 0;
            IElementType previous = null;
            for (IElementType type = lexer.getTokenType();
                    type != null;
                    lexer.advance(), type = lexer.getTokenType()) {
                int start = lexer.getTokenStart();
                int end = lexer.getTokenEnd();
                int startCp = codePoint;
                codePoint += src.codePointCount(start, end);
                out.println(startCp + " " + codePoint + " " + classify(type, previous, src, start, path));
                previous = type;
            }
        }
        out.flush();
    }

    private static String classify(IElementType type, IElementType previous, String src, int start, Path path) {
        // A backtick-escaped identifier lexes as one IDENTIFIER spanning the backticks, and the
        // lexer never looks inside it. Calling that CODE would let a rule fire on `` `not!!really` ``,
        // so it is detected by its first character rather than by token type.
        if (type == KtTokens.IDENTIFIER && start < src.length() && src.charAt(start) == '`') {
            return "TEXT";
        }
        // The name in a short template (`$name`) is an ordinary IDENTIFIER token. It is string
        // syntax, not code a rule should read, and _code_mask() blanks it.
        if (previous == KtTokens.SHORT_TEMPLATE_ENTRY_START) {
            return "TEXT";
        }
        if (TEXT.contains(type)) {
            return "TEXT";
        }
        if (CODE.contains(type) || KtTokens.KEYWORDS.contains(type) || KtTokens.SOFT_KEYWORDS.contains(type)) {
            return "CODE";
        }
        // Operators, separators and number literals have no umbrella TokenSet, but they are all
        // KtTokens types and none of them is string or comment content, so the remainder is CODE.
        //
        // A future Kotlin adding a *content* token would land here and be called CODE — but that
        // is not silent: _code_mask() blanks string and comment content, so the comparison in
        // test_code_mask.py fails on the disagreement. This throw is for the other case, a token
        // from somewhere the table never anticipated.
        if (type.getClass().getName().startsWith("org.jetbrains.kotlin.lexer.")) {
            return "CODE";
        }
        throw new IllegalStateException(
                "unclassified token type '" + type + "' (" + type.getClass().getName() + ") in " + path
                        + " — add it to TEXT or CODE in KtMask.java, and check _code_mask() handles it");
    }
}
