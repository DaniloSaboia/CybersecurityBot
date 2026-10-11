"""Package the manuscript and its local dependencies for Overleaf (stdlib only)."""
from pathlib import Path
import re
from zipfile import ZIP_DEFLATED, ZipFile


THESIS = Path(__file__).resolve().parent
OUTPUT = THESIS / "Sentinel_KMS_Overleaf.zip"
DEPENDENCY = re.compile(
    r"\\(input|include|includegraphics|bibliography)"
    r"(?:\[[^]]*\])?\{([^}]+)\}"
)


def collect_sources():
    """Follow this project's literal TeX, BibTeX and graphics references."""
    pending = [THESIS / "main.tex"]
    sources = set()
    while pending:
        path = pending.pop().resolve()
        path.relative_to(THESIS)  # Reject dependencies outside the thesis folder.
        if not path.is_file():
            raise FileNotFoundError(path)
        if path in sources:
            continue
        sources.add(path)
        if path.suffix != ".tex":
            continue
        source = re.sub(r"(?<!\\)%[^\n]*", "", path.read_text(encoding="utf-8"))
        for command, argument in DEPENDENCY.findall(source):
            names = argument.split(",") if command == "bibliography" else [argument]
            for name in names:
                dependency = THESIS / name.strip()
                if command in {"input", "include"} and not dependency.suffix:
                    dependency = dependency.with_suffix(".tex")
                elif command == "bibliography" and not dependency.suffix:
                    dependency = dependency.with_suffix(".bib")
                pending.append(dependency)
    return sorted(sources)


def main():
    sources = collect_sources()
    with ZipFile(OUTPUT, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sources:
            archive.write(path, path.relative_to(THESIS).as_posix())
        archive.write(THESIS / "OVERLEAF_README.txt", "README.txt")
    with ZipFile(OUTPUT) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("ZIP integrity check failed")
        assert "main.tex" in archive.namelist()
        for path in sources:
            assert archive.read(path.relative_to(THESIS).as_posix()) == path.read_bytes()
    print(f"Created {OUTPUT}")
    print(f"{len(sources) + 1} files; {OUTPUT.stat().st_size:,} bytes")
    print("Overleaf settings: main document main.tex; compiler XeLaTeX")


if __name__ == "__main__":
    main()
