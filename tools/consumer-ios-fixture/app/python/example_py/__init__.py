"""The consumer fixture's Python payload; copied to <app>/python/ by install-python.sh."""

VERSION = "0.1.0"


def greeting() -> str:
    return f"Hello from the iOS consumer fixture's Python payload {VERSION}"
