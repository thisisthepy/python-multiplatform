"""The payload `stagePythonBundle*` has to get into each platform's artifact.

`greeting()` is here so that "the file is in the APK/jar" can be checked by content
and not only by path -- see the toolchain plugin's staging tasks.
"""

VERSION = "0.1.0"


def greeting() -> str:
    return f"Hello from usage-example's Python payload {VERSION}"
