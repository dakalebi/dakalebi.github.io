#!/usr/bin/env python3
"""Fail the build when a composable emits a CSS class the page it renders on cannot style.

This exists because of a bug that shipped. `LoginScreen` is rendered by both shells. A
commit changed it to emit keel's class names - `input`, `btn--default`, `btn--link` -
and updated `web.css` to match. `tv/index.html` linked no keel stylesheet at the time,
so on `/tv/` those three classes were defined nowhere: the sign-in fields rendered as
unstyled browser boxes and the submit button had no fill. Nothing failed. Both halves
were individually correct and only the pair was wrong.

The check that catches that has to be per shell, not per repository. Asking "is this
class defined in any of our stylesheets" answers yes for that bug, because `input` was
in keel's `components.css` all along - the sheet TV was not loading. So this reads the
`<link>` tags in each shell's own `index.html` and holds each shell to exactly what
that page loads.

Two checks:

  1. Every class emitted anywhere is defined in at least one stylesheet. Catches a
     typo, and catches deleting a rule while the markup still asks for it.

  2. Every class emitted by a composable that *both* shells render is defined in both
     shells' linked stylesheets. This is the one above.

The unit here is the top-level declaration, not the file, and that distinction is
load-bearing. `Components.kt` holds nine composables; TV renders exactly one of them
(`Thumb`) and none of the rest. At file granularity this check would demand tv.css
define `.tile-img`, `.uprow` and twenty more classes for markup TV never emits, and
would be turned off within a week. At declaration granularity it asks only about
`Thumb`, and finds that TV really is missing two of the three classes `Thumb` emits.

Reachability follows explicit `ge.dakalebi` imports plus same-package siblings -
Kotlin needs no import for those, so an import graph alone would have `App` unable to
see `LoginScreen` beside it. A declaration counts as used by another when its name
appears as a whole word in that declaration's body and is visible from it.

Class names are read from `classes("a", "b")` and `classNames("a", null)`, which is how
every class in this codebase is written. keel's own components need no exemption: their
classes are never literals here, they come back from `buttonClasses()` and friends, and
keel's stylesheet ships with keel.

It reads the *built* stylesheets, because keel's four sheets arrive from the submodule
and only sit beside this app's own in the output. So build first:

    ./gradlew jsBrowserDistribution && python3 tools/check-css-classes.py
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "src/jsMain/kotlin"
DIST = ROOT / "build/dist/js/productionExecutable"

# Each shell is its own page with its own stylesheet set, which is the whole point.
SHELL_PAGES = {"/": DIST / "index.html", "/tv/": DIST / "tv/index.html"}

# Where each page starts drawing.
SHELL_ROOTS = {"/": "App", "/tv/": "TvApp"}

DECLARATION = re.compile(
    r"^(?:public |internal |private )*"
    r"(?:(?:data |value |sealed |abstract |open |enum )*class|object|interface|fun|val|var)\s+"
    r"(?:<[^>]+>\s*)?([A-Za-z_]\w*)",
    flags=re.M,
)


@dataclass
class Decl:
    """One top-level declaration, and the classes its body emits."""

    name: str
    package: str
    path: Path
    text: str
    imports: set[str]
    classes: set[str] = field(default_factory=set)
    words: set[str] = field(default_factory=set)

    def __hash__(self) -> int:
        return hash((self.path, self.name, len(self.text)))

    @property
    def where(self) -> str:
        return f"{self.path.relative_to(ROOT)}: {self.name}"


def linked_stylesheets(page: Path) -> list[Path]:
    """The local stylesheets this page links, in load order."""
    sheets: list[Path] = []

    for tag in re.findall(r"""<link[^>]*rel=["']stylesheet["'][^>]*>""", page.read_text()):
        href = re.search(r"""href=["']([^"']+)["']""", tag)
        # Remote sheets are web fonts; they define no component class.
        if not href or href.group(1).startswith("http"):
            continue

        path = (page.parent / href.group(1)).resolve()
        if not path.exists():
            raise SystemExit(f"{page.relative_to(ROOT)} links {href.group(1)}, which is not in the build")

        sheets.append(path)

    return sheets


def css_classes(path: Path) -> set[str]:
    """Every class name appearing in a selector in this stylesheet.

    Comments are stripped first. This codebase's stylesheets explain themselves at
    length and those explanations name the classes they are about - including ones just
    deleted, which is exactly the case a naive scan would report as still defined.
    """
    text = re.sub(r"/\*.*?\*/", "", path.read_text(), flags=re.S)
    names: set[str] = set()

    for block in re.finditer(r"(?:^|[{}])\s*([^{}@][^{}]*)\{", text, flags=re.M):
        names |= set(re.findall(r"\.([A-Za-z_][\w-]*)", block.group(1)))

    return names


def balanced(text: str, open_paren: int) -> str:
    """The text between `open_paren` and the parenthesis closing it."""
    depth = 0
    for index in range(open_paren, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1 : index]
    return ""


def emitted_classes(text: str) -> set[str]:
    """The class-name literals this body passes to `classes`/`classNames`."""
    names: set[str] = set()

    for call in re.finditer(r"\bclass(?:es|Names)\s*\(", text):
        # Literals only. An interpolated or computed argument is not something a static
        # check can resolve, and pretending otherwise would produce noise.
        names |= set(re.findall(r'"([^"$\\]+)"', balanced(text, call.end() - 1)))

    return {name for name in names if name and " " not in name}


def parse(path: Path) -> list[Decl]:
    """Split one file into its top-level declarations."""
    text = path.read_text()
    package = re.search(r"^package\s+([\w.]+)", text, flags=re.M)
    imports = set(re.findall(r"^import\s+(ge\.dakalebi\.[\w.]+)", text, flags=re.M))

    starts = [(m.start(), m.group(1)) for m in DECLARATION.finditer(text)]
    decls: list[Decl] = []

    for index, (start, name) in enumerate(starts):
        end = starts[index + 1][0] if index + 1 < len(starts) else len(text)
        body = text[start:end]
        decls.append(
            Decl(
                name=name,
                package=package.group(1) if package else "",
                path=path,
                text=body,
                imports=imports,
                classes=emitted_classes(body),
                words=set(re.findall(r"\b[A-Za-z_]\w*\b", body)),
            )
        )

    return decls


def reachable(root: Decl, by_name: dict[str, list[Decl]]) -> set[Decl]:
    """Every declaration this one can reach."""
    seen = {root}
    queue = [root]

    while queue:
        current = queue.pop()

        for name in current.words:
            for target in by_name.get(name, []):
                if target in seen:
                    continue

                visible = (
                    # Same package: Kotlin resolves it with no import.
                    target.package == current.package
                    # `import ge.dakalebi.ui.LoginScreen`
                    or f"{target.package}.{name}" in current.imports
                    # A star import, or the package imported for another symbol in it.
                    or target.package in current.imports
                )
                if visible:
                    seen.add(target)
                    queue.append(target)

    return seen


def main() -> int:
    if not DIST.exists():
        raise SystemExit(f"{DIST.relative_to(ROOT)} is missing. Run ./gradlew jsBrowserDistribution first.")

    files = sorted(KOTLIN.rglob("*.kt"))
    decls = [decl for path in files for decl in parse(path)]

    by_name: dict[str, list[Decl]] = {}
    for decl in decls:
        by_name.setdefault(decl.name, []).append(decl)

    sheets = {shell: linked_stylesheets(page) for shell, page in SHELL_PAGES.items()}
    defined = {shell: set().union(*(css_classes(s) for s in paths)) for shell, paths in sheets.items()}
    anywhere = set().union(*defined.values())

    reach: dict[str, set[Decl]] = {}
    for shell, root_name in SHELL_ROOTS.items():
        roots = by_name.get(root_name, [])
        if not roots:
            raise SystemExit(f"no top-level `{root_name}` to start {shell} from")
        reach[shell] = set().union(*(reachable(root, by_name) for root in roots))

    failures: list[str] = []

    for decl in decls:
        if not decl.classes:
            continue

        for name in sorted(decl.classes - anywhere):
            failures.append(f"{decl.where} emits `{name}`, which no stylesheet defines")

        drawn_on = [shell for shell, seen in reach.items() if decl in seen]
        if len(drawn_on) < 2:
            continue

        for shell in sorted(drawn_on):
            for name in sorted((decl.classes & anywhere) - defined[shell]):
                linked = ", ".join(s.name for s in sheets[shell])
                failures.append(f"{decl.where} also draws on {shell}, where `{name}` is undefined ({linked})")

    if failures:
        print("Classes the page cannot style:\n")
        for failure in failures:
            print(f"  {failure}")
        print(f"\n{len(failures)} problem(s). Why this check exists: see the top of {Path(__file__).name}.")
        return 1

    shared = len(reach["/"] & reach["/tv/"])
    print(f"OK: {len(decls)} declarations in {len(files)} files, {shared} on both shells, every class accounted for.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
