#!/usr/bin/env python3
"""Checks the bilingual GitHub Pages guide in docs/guide/.

    python3 .github/scripts/check_guide.py [guide-dir]

Fails (exit 1) when:
  1. an HTML file does not parse into balanced elements;
  2. a relative href/src points to a missing file, or a #fragment to a missing id;
  3. a `data-lang="en"` element has no `data-lang="ko"` twin right after it (or vice versa);
  4. visible text sits outside any `data-lang` element (it would show in both languages untranslated),
     unless it is language-neutral (no letters), inside <pre>/<code>/<script>/<style>, inside an
     element marked translate="no", or in the small allowlist of proper names below;
  5. a page lacks the per-language titles (`data-title-en` / `data-title-ko`).

What this cannot find: a Korean string that is a poor or wrong translation of its English twin, and
links to external sites (they are not fetched).
"""
import os
import re
import sys
from html.parser import HTMLParser

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
        # SVG children written self-closing are reported through handle_startendtag
        }
SKIP_TEXT = {"pre", "code", "script", "style", "title"}
NEUTRAL_ALLOW = {"GitHub", "FAQ", "python-multiplatform", "thisisthepy", "Kotlin", "CPython", "한국어", "English"}
LETTER = re.compile(r"[A-Za-zㄱ-ㆎ가-힣]")


class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []          # (tag, attrs dict)
        self.errors = []
        self.ids = set()
        self.links = []          # (attr value, line)
        self.siblings = [[]]     # per open element: list of data-lang values of its element children
        self.titles = set()

    def _attrs(self, attrs):
        return {k: (v or "") for k, v in attrs}

    def handle_starttag(self, tag, attrs):
        a = self._attrs(attrs)
        self._record(tag, a)
        if tag in VOID:
            return
        self.stack.append((tag, a))
        self.siblings.append([])

    def handle_startendtag(self, tag, attrs):
        self._record(tag, self._attrs(attrs))

    def _record(self, tag, a):
        line = self.getpos()[0]
        if "id" in a:
            self.ids.add(a["id"])
        for key in ("href", "src"):
            if key in a:
                self.links.append((a[key], line))
        if "data-title-en" in a and a["data-title-en"].strip():
            self.titles.add("en")
        if "data-title-ko" in a and a["data-title-ko"].strip():
            self.titles.add("ko")
        self.siblings[-1].append((a.get("data-lang"), line))

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if not self.stack:
            self.errors.append(f"line {self.getpos()[0]}: stray </{tag}>")
            return
        open_tag, _ = self.stack.pop()
        children = self.siblings.pop()
        self._check_pairs(children)
        if open_tag != tag:
            self.errors.append(f"line {self.getpos()[0]}: </{tag}> closes <{open_tag}>")

    def _check_pairs(self, children):
        i = 0
        while i < len(children):
            lang, line = children[i]
            if lang == "en":
                if i + 1 >= len(children) or children[i + 1][0] != "ko":
                    self.errors.append(f"line {line}: data-lang=en without a following data-lang=ko twin")
                    i += 1
                else:
                    i += 2
            elif lang == "ko":
                self.errors.append(f"line {line}: data-lang=ko without a preceding data-lang=en twin")
                i += 1
            else:
                i += 1

    def handle_data(self, data):
        text = data.strip()
        if not text or not LETTER.search(text):
            return
        tags = [t for t, _ in self.stack]
        if any(t in SKIP_TEXT for t in tags):
            return
        if any(a.get("data-lang") for _, a in self.stack):
            return
        if any(a.get("translate") == "no" for _, a in self.stack):
            return
        if text in NEUTRAL_ALLOW:
            return
        if any(a.get("id") in ("lang-toggle", "theme-toggle") for _, a in self.stack):
            return  # set by site.js per language
        self.errors.append(f"line {self.getpos()[0]}: untranslated visible text: {text[:60]!r}")

    def finish(self):
        self._check_pairs(self.siblings[0])
        for tag, _ in self.stack:
            self.errors.append(f"unclosed <{tag}>")
        for lang in ("en", "ko"):
            if lang not in self.titles:
                self.errors.append(f"missing data-title-{lang}")


def check(guide):
    pages = {}
    failures = []
    for root, _, files in os.walk(guide):
        for f in files:
            if f.endswith(".html"):
                path = os.path.join(root, f)
                p = Page()
                with open(path, encoding="utf-8") as fh:
                    p.feed(fh.read())
                p.close()
                p.finish()
                pages[os.path.normpath(path)] = p
    if not pages:
        return [f"{guide}: no HTML pages found"]
    for path, p in sorted(pages.items()):
        rel = os.path.relpath(path, guide)
        failures += [f"{rel}: {e}" for e in p.errors]
        for link, line in p.links:
            if re.match(r"^[a-z]+:", link) or link.startswith("//"):
                continue
            target, _, frag = link.partition("#")
            dest = os.path.normpath(os.path.join(os.path.dirname(path), target)) if target else path
            if target and not os.path.exists(dest):
                failures.append(f"{rel}: line {line}: broken link {link!r}")
                continue
            if frag and dest.endswith(".html"):
                if dest not in pages or frag not in pages[dest].ids:
                    failures.append(f"{rel}: line {line}: missing anchor {link!r}")
    return failures


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    guide = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "..", "docs", "guide")
    failures = check(guide)
    for f in failures:
        print("FAIL", f)
    count = sum(1 for _, _, fs in os.walk(guide) for f in fs if f.endswith(".html"))
    print(f"{count} pages checked, {len(failures)} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
