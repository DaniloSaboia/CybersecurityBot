"""Regression checks for the thesis validator; run after compiling the thesis."""
import contextlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import validate


class ThesisValidationTest(unittest.TestCase):
    def test_confirmed_metadata_is_not_reported_as_a_placeholder(self):
        self.assertTrue(hasattr(validate, 'frontmatter_placeholders'))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = root / 'metadata.tex'
            metadata.write_text('\\newcommand{\\thesisauthor}{Confirmed Name}\n')
            frontmatter = root / 'frontmatter.tex'
            frontmatter.write_text(
                '\\draftfield{Acknowledgements}\n'
                '% \\draftfield{University}\n'
                '\\newcommand{\\draftfield}[1]{#1}\n'
            )
            self.assertEqual(validate.frontmatter_placeholders(
                [metadata, frontmatter], root),
                [{'file': 'frontmatter.tex', 'line': 1, 'field': 'Acknowledgements'}])

    def test_project_details_begin_in_chapter_three_without_git_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            intro = root / '01-introduction.tex'
            intro.write_text('Sentinel uses pgvector.\nKnowledge reuse guides the research.\n')
            literature = root / '02-literature.tex'
            literature.write_text('Sentinel stores passages.\n')
            system = root / '03-system.tex'
            system.write_text('Sentinel uses pgvector.\nThe main branch contains a commit.\n')
            self.assertEqual(validate.narrative_constraint_lines(
                [intro, literature, system], root),
                ['01-introduction.tex:1: implementation before Chapter 3',
                 '02-literature.tex:1: implementation before Chapter 3',
                 '03-system.tex:2: Git history in system chapter'])

    def test_obsolete_unperformed_study_claims_are_rejected(self):
        self.assertTrue(hasattr(validate, 'narrative_constraint_lines'))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = root / 'sample.tex'
            sample.write_text(
                'Controlled model comparison and human outcomes have not been measured.\n'
                'No controlled model comparison or participant study was conducted.\n'
                'The completed study records await integration.\n'
                '% No controlled model comparison or participant study was conducted.\n'
            )
            self.assertEqual(validate.narrative_constraint_lines([sample], root),
                             ['sample.tex:1: obsolete study-status claim',
                              'sample.tex:2: obsolete study-status claim'])

    def test_standalone_code_filenames_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = root / 'sample.tex'
            sample.write_text(
                'The component is defined by AssistantView.jsx.\n'
                'The evaluation uses quality_gate.py and results.json.\n'
                'The settings are in application.yml.\n'
                '\\includegraphics{figures/main-cited-answer-mocked.png}\n'
                '\\input{chapters/03-system.tex}\n'
                '\\bibliography{references,supplementary}\n'
                'The report validator checks consistency. % quality_gate.py\n'
            )
            self.assertEqual(validate.visible_source_path_lines([sample], root),
                             ['sample.tex:1', 'sample.tex:2', 'sample.tex:3'])

    def test_repository_paths_are_rejected_but_figure_assets_are_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = root / 'sample.tex'
            sample.write_text(
                '\\path{backend/src/main/java/KnowledgeArticleReindexWorker.java}\n'
                'The schema is at supabase/migrations/20260905020000_knowledge_governance.sql.\n'
                'The knowledge reindex worker handles publication.\n'
                '\\includegraphics{figures/main-cited-answer-mocked.png}\n'
                '% backend/src/this-is-a-comment.java\n'
            )
            self.assertEqual(validate.visible_source_path_lines([sample], root),
                             ['sample.tex:1', 'sample.tex:2'])

    def test_fenced_execution_output_is_not_a_source_path(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            shutil.copy2(validate.EVIDENCE / 'reference-catalogue.json',
                         evidence / 'reference-catalogue.json')
            (evidence / 'implementation-revision.md').write_text(
                '`scripts/ci/quality_gate.py:19-66`\n\n```text\n'
                + 'fixture execution output\n' * 100 + '```\n')
            with patch.object(validate, 'EVIDENCE', evidence):
                with contextlib.redirect_stdout(io.StringIO()):
                    with self.assertRaises(SystemExit) as result:
                        validate.main()
            self.assertEqual(result.exception.code, 0)
            manifest = json.loads((evidence / 'source-manifest.json').read_text())
            paths = {entry['path'] for entry in manifest['files']}
            self.assertIn('scripts/ci/quality_gate.py', paths)
            self.assertFalse(any('fixture execution output' in path for path in paths))


if __name__ == '__main__':
    unittest.main()
