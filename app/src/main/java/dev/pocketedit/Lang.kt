package dev.pocketedit

enum class CommentStyle { NONE, C, HASH, SQL, MARKUP }

/** Rough language families — enough for lightweight highlighting and editor behaviour. */
enum class Lang(val comments: CommentStyle, val keywords: Set<String>) {
    PLAIN(CommentStyle.NONE, emptySet()),
    MARKDOWN(CommentStyle.NONE, emptySet()),
    C_LIKE(CommentStyle.C, Keywords.C_LIKE),
    PYTHON(CommentStyle.HASH, Keywords.PYTHON),
    SHELL(CommentStyle.HASH, Keywords.SHELL),
    CONFIG(CommentStyle.HASH, Keywords.LITERALS),
    SQL(CommentStyle.SQL, Keywords.SQL),
    MARKUP(CommentStyle.MARKUP, emptySet()),
    JSON(CommentStyle.NONE, Keywords.LITERALS);

    val isCode: Boolean get() = this != PLAIN && this != MARKDOWN

    companion object {
        fun forName(name: String): Lang = when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown", "mdown", "mkd" -> MARKDOWN
            "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "c", "h", "cpp", "cc", "hpp",
            "cs", "go", "rs", "swift", "dart", "scala", "groovy", "gradle", "php", "css", "scss" -> C_LIKE
            "py", "pyw" -> PYTHON
            "sh", "bash", "zsh", "fish" -> SHELL
            "yml", "yaml", "toml", "ini", "conf", "cfg", "properties", "env", "rb" -> CONFIG
            "sql" -> SQL
            "xml", "html", "htm", "svg", "xhtml", "vue" -> MARKUP
            "json", "jsonc", "geojson" -> JSON
            else -> PLAIN
        }
    }
}

private object Keywords {
    val LITERALS = setOf("true", "false", "null", "yes", "no", "on", "off", "none", "None", "True", "False", "nil")

    val C_LIKE = setOf(
        "abstract", "as", "async", "await", "break", "by", "case", "catch", "class", "companion", "const",
        "continue", "data", "def", "default", "defer", "delete", "do", "else", "enum", "export", "extends",
        "false", "final", "finally", "fn", "for", "fun", "func", "function", "get", "go", "if", "impl",
        "implements", "import", "in", "init", "inline", "instanceof", "interface", "internal", "is", "lateinit",
        "let", "match", "mod", "mut", "namespace", "new", "null", "object", "open", "operator", "override",
        "package", "private", "protected", "pub", "public", "return", "sealed", "self", "set", "static",
        "struct", "super", "suspend", "switch", "this", "throw", "throws", "trait", "true", "try", "type",
        "typealias", "typeof", "use", "val", "var", "void", "when", "where", "while", "yield",
        "int", "long", "float", "double", "char", "bool", "boolean", "string", "String", "Int", "Long",
        "Boolean", "Unit", "Any", "nil", "undefined", "unsafe", "virtual", "readonly", "from", "of",
    )

    val PYTHON = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
        "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None",
        "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield", "self",
        "match", "case",
    )

    val SHELL = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in",
        "function", "return", "local", "export", "readonly", "echo", "exit", "set", "unset", "source",
        "true", "false", "shift", "cd", "test",
    )

    val SQL = buildSet {
        listOf(
            "select", "from", "where", "and", "or", "not", "insert", "into", "values", "update", "set",
            "delete", "create", "table", "drop", "alter", "add", "index", "view", "join", "left", "right",
            "inner", "outer", "on", "as", "group", "by", "order", "having", "limit", "offset", "distinct",
            "union", "all", "null", "is", "in", "like", "between", "case", "when", "then", "else", "end",
            "primary", "key", "foreign", "references", "default", "exists", "with", "asc", "desc",
            "integer", "int", "text", "varchar", "boolean", "true", "false", "count", "sum", "avg", "min", "max",
        ).forEach { add(it); add(it.uppercase()) }
    }
}
