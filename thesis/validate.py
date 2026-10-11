"""Validate the integrated thesis and record source provenance (stdlib only)."""
import collections
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

THESIS = Path(__file__).resolve().parent
ROOT = THESIS.parent
BUILD = THESIS / 'build'
EVIDENCE = THESIS / 'evidence'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def visible_source_path_lines(tex_files, thesis_root):
    """Locate source-tree names that should not be printed in dissertation prose."""
    pattern = re.compile(
        r'\\path\{|(?:backend/src/|frontend/src/|supabase/migrations/|'
        r'thesis/evidence/|scripts/ci/|/Users/)|'
        r'\b[\w.-]+\.(?:java|jsx|js|tsx|ts|py|sql|json|yaml|yml|toml|'
        r'sh|xml|csv|tex|bib|md|txt|pdf|png)\b')
    asset_command = re.compile(
        r'\\(?:includegraphics|input|include|bibliography)'
        r'(?:\[[^]]*\])?\{[^}]*\}')
    hits = []
    for file in tex_files:
        for number, line in enumerate(file.read_text().splitlines(), 1):
            visible = re.split(r'(?<!\\)%', line, maxsplit=1)[0]
            visible = asset_command.sub('', visible)
            if pattern.search(visible):
                hits.append(f'{file.relative_to(thesis_root)}:{number}')
    return hits


def narrative_constraint_lines(tex_files, thesis_root):
    """Check the agreed chapter boundaries and corrected study status."""
    obsolete_status = re.compile(
        r'(?:controlled model comparison and human outcomes have not been measured|'
        r'no controlled model comparison or participant study was conducted)',
        re.IGNORECASE)
    project_terms = re.compile(r'\b(?:Sentinel|pgvector|Supabase|JDBC)\b', re.IGNORECASE)
    git_terms = re.compile(
        r'\b(?:Git|commits?|committed|uncommitted|branches|branch|worktree)\b|'
        r'working[ -]tree', re.IGNORECASE)
    hits = []
    for file in tex_files:
        for number, line in enumerate(file.read_text().splitlines(), 1):
            visible = re.split(r'(?<!\\)%', line, maxsplit=1)[0]
            if obsolete_status.search(visible):
                hits.append(f'{file.relative_to(thesis_root)}:{number}: obsolete study-status claim')
            if file.name in {'01-introduction.tex', '02-literature.tex'} and project_terms.search(visible):
                hits.append(f'{file.relative_to(thesis_root)}:{number}: implementation before Chapter 3')
            if file.name == '03-system.tex' and git_terms.search(visible):
                hits.append(f'{file.relative_to(thesis_root)}:{number}: Git history in system chapter')
    return hits


def frontmatter_placeholders(tex_files, thesis_root):
    """Report only unresolved fields still present in uncommented LaTeX."""
    hits = []
    for file in tex_files:
        for number, line in enumerate(file.read_text().splitlines(), 1):
            visible = re.split(r'(?<!\\)%', line, maxsplit=1)[0]
            for field in re.findall(r'\\draftfield\{([^}]+)\}', visible):
                hits.append({'file': str(file.relative_to(thesis_root)),
                             'line': number, 'field': field})
    return hits


def main():
    tex_files = sorted(THESIS.glob('*.tex')) + sorted((THESIS / 'chapters').glob('*.tex'))
    text = '\n'.join(p.read_text() for p in tex_files)
    cites = set()
    for group in re.findall(r'\\cite\w*\*?(?:\[[^]]*\])*\{([^}]+)\}', text):
        cites.update(k.strip() for k in group.split(','))
    bib_keys = []
    for p in THESIS.glob('*.bib'):
        bib_keys.extend(re.findall(r'@\w+\s*\{\s*([^,]+),', p.read_text()))
    catalogue = json.loads((EVIDENCE / 'reference-catalogue.json').read_text())
    unresolved = {r['key'] for r in catalogue if r['status'] == 'unresolved'}
    labels = re.findall(r'\\label\{([^}]+)\}', text)
    refs = set(re.findall(r'\\(?:ref|pageref|autoref)\{([^}]+)\}', text))
    bbl = (BUILD / 'main.bbl').read_text()
    printed = set(re.findall(r'\\bibitem(?:\[[\s\S]*?\])?\{([^}]+)\}', bbl))
    log = (BUILD / 'main.log').read_text()
    bad_log = [line for line in log.splitlines() if any(s in line for s in (
        'Overfull \\hbox', 'Overfull \\vbox', 'Missing character:',
        'There were undefined references', 'undefined on input line',
        'multiply defined', 'Citation `' ))]
    errors = []
    example_pattern = r'godinho|diogo|\bASTRA\b|app:template'
    if re.search(example_pattern, text + '\n' + bbl, re.IGNORECASE):
        errors.append('example dissertation referenced in thesis or printed bibliography')
    for p in THESIS.glob('*.bib'):
        if re.search(example_pattern, p.read_text(), re.IGNORECASE):
            errors.append({'example dissertation in bibliography': p.name})
    # The final dissertation explains components, not source-tree filenames.
    visible_paths = visible_source_path_lines(tex_files, THESIS)
    if visible_paths:
        errors.append({'visible source paths in thesis prose': visible_paths})
    narrative_violations = narrative_constraint_lines(tex_files, THESIS)
    if narrative_violations:
        errors.append({'reader-facing narrative constraints': narrative_violations})
    for graphic in re.findall(r'\\includegraphics(?:\[[^]]*\])?\{([^}]+)\}', text):
        if not (THESIS / graphic).is_file():
            errors.append({'missing figure': graphic})
        if re.search(r'figures/0[1-4]-.*-mocked\.png$', graphic):
            errors.append({'superseded screenshot': graphic})
    for label, values in [('missing bibliography keys', cites-set(bib_keys)),
                          ('unresolved records cited', cites & unresolved),
                          ('missing labels', refs-set(labels)),
                          ('printed/cited difference', cites ^ printed)]:
        if values: errors.append({label: sorted(values)})
    if len(bib_keys) != len(set(bib_keys)): errors.append('duplicate bibliography keys')
    if len(labels) != len(set(labels)): errors.append('duplicate labels')
    if bad_log: errors.append({'latex_errors': bad_log})
    if len(catalogue) != 100: errors.append('supplied reference count differs from 100')
    if len(list((THESIS/'chapters').glob('*.tex'))) != 5: errors.append('expected five chapters')
    if not (BUILD/'main.pdf').is_file(): errors.append('compiled PDF absent')

    source_paths = set()
    for p in tex_files:
        for value in re.findall(r'\\path\{([^}]+)\}', p.read_text()):
            candidate = ROOT / value
            if candidate.is_file() and not value.startswith('thesis/'):
                source_paths.add(candidate)
    for audit_name in ['implementation-audit.md', 'evaluation-audit.md',
                       'implementation-revision.md', 'current-main-verification.md']:
        audit_path = EVIDENCE / audit_name
        if not audit_path.is_file():
            continue
        audit = audit_path.read_text()
        # Inline code references only; fenced execution logs are not paths.
        for value in re.findall(r'(?<!`)`([^`\r\n]+)`(?!`)', audit):
            value = value.split(':',1)[0]
            candidate = ROOT / value
            if candidate.is_file() and not value.startswith('thesis/') and candidate.suffix in {'.java','.jsx','.js','.sql','.yml','.md','.json','.py'}:
                source_paths.add(candidate)
    # Never scan the user's credential files or load the application environment.
    assert all(p.name != '.env' and '.env.' not in p.name for p in source_paths)
    manifest = {
        'recorded_at_utc': datetime.now(timezone.utc).isoformat(),
        'branch': subprocess.check_output(['git','branch','--show-current'],cwd=ROOT,text=True).strip(),
        'head': subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'scope': 'Current main working-tree source identities; not a deployment or research outcome',
        'files': [{'path':str(p.relative_to(ROOT)), 'sha256':sha(p)} for p in sorted(source_paths)],
    }

    (EVIDENCE/'source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    placeholders = frontmatter_placeholders(tex_files, THESIS)
    limitations = [
        'Structural/citation-link validation is not semantic entailment review',
        'The researcher confirms completed model and human studies; their records await integration',
    ]
    if placeholders:
        limitations.append('Unconfirmed front-matter fields: ' + ', '.join(
            sorted({item['field'] for item in placeholders})))
    report = {
        'recorded_at_utc': datetime.now(timezone.utc).isoformat(),
        'validation_passed': not errors,
        'errors': errors,
        'chapters': 5,
        'supplied_reference_records':len(catalogue),
        'total_bibtex_records_including_supplementary':len(bib_keys),
        'unique_cited_references':len(cites),
        'cited_keys':sorted(cites),
        'uncited_unresolved_records':sorted(unresolved),
        'bibliography_matches_citations':cites == printed,
        'example_dissertation_absent': not re.search(example_pattern, text + '\n' + bbl, re.IGNORECASE),
        'underfull_box_warnings':sum('Underfull' in line for line in log.splitlines()),
        'pdf_sha256':sha(BUILD/'main.pdf'),
        'figure_sha256':{p.name: sha(p) for p in sorted((THESIS/'figures').glob('main-*.png'))},
        'tex_sha256':{str(p.relative_to(THESIS)):sha(p) for p in tex_files},
        'unconfirmed_frontmatter_fields': placeholders,
        'limitations': limitations,
    }
    (EVIDENCE/'document-validation.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    raise SystemExit(0 if not errors else 1)


if __name__ == '__main__':
    main()
