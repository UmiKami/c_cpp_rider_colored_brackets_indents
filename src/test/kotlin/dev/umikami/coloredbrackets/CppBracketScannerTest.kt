// Copyright 2026 Ernesto Gonzalez. Licensed under the Apache License, Version 2.0.
// See the LICENSE and NOTICE files in the project root.

package dev.umikami.coloredbrackets

import org.junit.Assert.assertEquals
import org.junit.Test

class CppBracketScannerTest {
    private val basic = setOf(BracketType.ROUND, BracketType.SQUARE, BracketType.CURLY)
    private val all = basic + BracketType.ANGLE

    /** Renders each colored bracket as `char+level` (or `char!` if unmatched), e.g. "{0 (1 )1 }0". */
    private fun render(code: String, types: Set<BracketType> = basic): String =
        CppBracketScanner.scan(code, types).sortedBy { it.offset }.joinToString(" ") {
            code[it.offset] + if (it.unmatched) "!" else it.level.toString()
        }

    @Test fun nesting() = assertEquals("{0 (1 [2 ]2 )1 }0", render("{ f(a[1]); }"))

    @Test fun skipsStringsCharsAndComments() = assertEquals(
        "(0 )0",
        render("f(\"(\", '[', '\\'', /* { */ x) // )\n"),
    )

    @Test fun rawStrings() = assertEquals("(0 )0", render("f(R\"x( ) ] } )\" )x\");"))

    @Test fun digitSeparatorIsNotACharLiteral() = assertEquals("(0 )0 (0 )0", render("f(1'000'000); g(x);"))

    @Test fun includeHeaderIgnored() = assertEquals(
        "(0 )0",
        render("#include <vector>\nf();", all),
    )

    @Test fun macroBracketsDontLeak() = assertEquals(
        "{0 {0 }0",
        render("#define BEGIN {\nvoid f() {\n}\n").replace("(0 )0 ", ""),
    )

    @Test fun ifZeroSkipped() = assertEquals(
        "{0 }0",
        render("#if 0\n{ ( \n#endif\n{ }"),
    )

    @Test fun ifZeroElseBranchKept() = assertEquals("(0 )0", render("#if 0\n{\n#else\nf();\n#endif\n"))

    @Test fun unmatched() {
        assertEquals("{0 }0 )!", render("{ } )"))
        assertEquals("(! {1 }1", render("( { }"))
    }

    @Test fun mismatchedCloserMarksInnerOpen() = assertEquals("{0 (! }0", render("{ ( }"))

    @Test fun templates() = assertEquals(
        "<0 <1 >1 >0",
        render("std::map<int, std::vector<int>> m;", all),
    )

    @Test fun comparisonsAreNotTemplates() {
        assertEquals("(0 )0", render("if (a < b && c > d) {}", setOf(BracketType.ROUND, BracketType.ANGLE)))
        assertEquals("(0 )0", render("for (i = 0; i < n; ++i) x = y > 2;", setOf(BracketType.ROUND, BracketType.ANGLE)))
        assertEquals("", render("x = a << 2 >> 1; p->q;", setOf(BracketType.ANGLE)))
    }

    @Test fun templateRvalueRef() = assertEquals("<0 >0", render("std::forward<T&&>(x);", setOf(BracketType.ANGLE)))

    @Test fun operatorLessIsNotTemplate() = assertEquals(
        "",
        render("bool operator<(const A& o) const; x > 1;", setOf(BracketType.ANGLE)),
    )

    @Test fun indents() {
        val code = "a\n    b\n        c\n    \n\td\n"
        val hits = CppBracketScanner.indents(code, tabSize = 4, indentSize = 4)
        assertEquals(
            listOf("    " to 0, "    " to 0, "    " to 1, "\t" to 0),
            hits.map { code.substring(it.start, it.end) to it.level },
        )
    }

    @Test fun detectIndent() {
        val code = "a {\n  b {\n    c;\n  }\n  d {\n    e;\n  }\n}\n"
        assertEquals(2, CppBracketScanner.detectIndentSize(code, 4))
    }
}
