"""The "no CPython" check of the AOT tests, shared by every test that makes it.

What it means: a generated C program or a runtime header of the AOT mode must not DEPEND on the CPython
API. It must not include a CPython header, and its code must not name a CPython identifier (`PyObject`,
`Py_INCREF`, `_PyLong_New`, ...). The word "Python" or a name like "PyObject" inside a comment or a string
or character literal is not a dependence (a message of the reference interpreter that the runtime
reproduces contains the language's name), so it is allowed.

How: (1) the `#include` lines of the original text are examined on their own (`cpython_includes`);
(2) the include lines are removed, then comments and string and character literals are blanked out by a
small scanner (`code_only`);
(3) every match of `\\b_?Py[A-Z_a-z]\\w*` in the remaining code is an offence.

What it cannot find: a CPython function reached through a macro defined in another header (the name is
not in this text), a name built by token pasting (`Py ## Object`), a dependence by linking only (a
library, a `dlopen`), and an include written through a macro or split by a line continuation. An include
inside a comment line that starts with `#include` is reported as well (a false alarm, never a miss).
"""
import re

_INCLUDE = re.compile(r'^[ \t]*#[ \t]*(?:include|include_next|import)[ \t]*[<"]([^>"\n]+)[>"]', re.MULTILINE)
_CPYTHON_NAME = re.compile(r"\b_?Py[A-Z_a-z]\w*")
# headers of the CPython include directory whose names do not start with "py"
_CPYTHON_HEADERS = {"structmember.h", "frameobject.h", "longintrepr.h"}


def cpython_includes(text):
    """The included paths that name CPython. A path is an offence when any of its components starts with
    `py` (case-insensitive: `Python.h`, `pyconfig.h`, `pymacro.h`, `internal/pycore_long.h`), is `cpython`
    (`cpython/object.h`), or is one of structmember.h, frameobject.h, longintrepr.h."""
    found = []
    for path in _INCLUDE.findall(text):
        parts = path.strip().replace("\\", "/").split("/")
        if any(p.lower().startswith("py") or p.lower() == "cpython" or p.lower() in _CPYTHON_HEADERS for p in parts):
            found.append("#include " + path.strip())
    return found


def code_only(text):
    """`text` with every comment, string literal and character literal replaced by spaces (newlines kept).
    States: CODE, LINE (`// ...` up to an unescaped newline), BLOCK (`/* ... */`), STRING and CHAR (up to
    the closing quote; a backslash escapes the next character, so `\\"` and `\\\\` are handled)."""
    out = []
    state, i, n = "CODE", 0, len(text)
    while i < n:
        c, nxt = text[i], text[i + 1] if i + 1 < n else ""
        if state == "CODE":
            if c == "/" and nxt == "/":
                state, i = "LINE", i + 2
                out.append("  ")
            elif c == "/" and nxt == "*":
                state, i = "BLOCK", i + 2
                out.append("  ")
            elif c in "\"'":
                state, i = c, i + 1
                out.append(" ")
            else:
                out.append(c)
                i += 1
        elif state == "LINE":
            if c == "\\" and nxt:
                out.append(" " + ("\n" if nxt == "\n" else " "))
                i += 2
            elif c == "\n":
                out.append(c)
                state, i = "CODE", i + 1
            else:
                out.append(" ")
                i += 1
        elif state == "BLOCK":
            if c == "*" and nxt == "/":
                out.append("  ")
                state, i = "CODE", i + 2
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
        else:  # STRING ('"') or CHAR ("'")
            if c == "\\" and nxt:
                out.append(" " + ("\n" if nxt == "\n" else " "))
                i += 2
            else:
                out.append("\n" if c == "\n" else " ")
                if c == state or c == "\n":  # the closing quote (an unterminated literal ends at the line end)
                    state = "CODE"
                i += 1
    return "".join(out)


def cpython_api_uses(c_text):
    """The offending items of `c_text`: CPython includes, then CPython identifiers in the code. Empty when clean."""
    return cpython_includes(c_text) + _CPYTHON_NAME.findall(code_only(_INCLUDE.sub("", c_text)))
