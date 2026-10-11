OVERLEAF IMPORT - SENTINEL KMS DISSERTATION

1. Open your Overleaf project dashboard and choose New project >
   Existing project (.zip), also called Upload Project in older interfaces.
2. Upload Sentinel_KMS_Overleaf.zip without extracting it first.
3. Open project Settings (gear icon), then Compiler. Select XeLaTeX.
4. Set the Main document to main.tex and keep the latest available TeX Live.
5. Click Recompile. Overleaf runs BibTeX and the required additional passes.

The compiler selection is a project setting: importing the ZIP does not
guarantee XeLaTeX is selected. This document uses fontspec and OpenType fonts,
so the default pdfLaTeX compiler is unsuitable.

WHAT IS INCLUDED

main.tex is at the ZIP root. Keep the chapters/ and figures/ folders intact.
The package contains all five chapters, front matter, metadata, acronyms,
appendices, both bibliography databases, two Iscte logos, two diagrams,
and the four screenshots used in the manuscript. The fonts and LaTeX
packages are standard TeX-distribution resources. No custom university
class, Python script, external application, or shell escape is required.

WHERE TO WRITE

metadata.tex                 Title, author, degree, supervisors and date
frontmatter.tex              Acknowledgements and bilingual abstracts
chapters/01-introduction.tex  Introduction
chapters/02-literature.tex    State of the art
chapters/03-system.tex        System design and development
chapters/04-evaluation.tex    Evaluation and results
chapters/05-conclusion.tex    Conclusion
appendices.tex               Appendices
acronyms.tex                 Acronyms
references.bib               Supplied bibliography records
supplementary.bib            Additional bibliography records

Use existing citation keys with \cite{key}; numbering and links are automatic.
The printed bibliography contains the cited records only.

CURRENT WRITING STATUS

This package preserves the existing manuscript. Before final submission:
- Replace the acknowledgement placeholder in frontmatter.tex.
- Integrate the actual records from the completed model-comparison and human
  evaluation studies, then reconcile Chapter 4, the abstracts, conclusion,
  and relevant appendices. Missing transferred records do not mean that
  these studies were not conducted.
- Confirm institutional formatting and citation requirements. The current
  layout has not been established as an official Iscte dissertation template.

REFERENCE

Upload instructions:
https://docs.overleaf.com/managing-projects-and-files/uploading-a-project
Compiler settings:
https://docs.overleaf.com/getting-started/recompiling-your-project/selecting-a-tex-live-version-and-latex-compiler
