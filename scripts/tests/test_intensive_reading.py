"""Integrity and text-boundary regressions for offline intensive reading."""
import gzip
import importlib.util
import json
from pathlib import Path
import sys
import unittest
import tempfile
from unittest.mock import patch
from types import SimpleNamespace as Object

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import intensive_reading as module
import audit_resource_provenance as audit


class IntensiveReadingTests(unittest.TestCase):
    def test_empty_distribution_requires_policy_and_rejects_leftover_text(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            directory = root/'apps/android/app/src/main/assets/intensive'
            module.write(directory/'catalog.json',dict(schemaVersion=1,contentPolicy='no-exam-texts',articles=[]))
            proof = dict(distribution='no-exam-texts',reason='No redistribution grant',articles=[])
            module.write(root/'content/intensive/provenance.json',proof)
            with patch.object(audit,'ROOT',folder):
                self.assertEqual([],audit.check_intensive())
                leftover=directory/'articles/hidden.json';leftover.parent.mkdir();leftover.write_text('text')
                self.assertTrue(any('残留' in x for x in audit.check_intensive()))
                leftover.unlink();proof.pop('distribution');module.write(root/'content/intensive/provenance.json',proof)
                self.assertTrue(any('明确登记' in x for x in audit.check_intensive()))
    def test_identity_does_not_mix_suite_or_year(self):
        result = module.paper_identity('四级真题/2026.06/真题PDF/cet4_2026_06_2.pdf')
        self.assertEqual('cet4-2026-06-2', result['id'])
        self.assertIsNone(module.paper_identity('六级真题/2026.12/真题PDF/cet6_2026_12_1.pdf'))
        self.assertIsNone(module.paper_identity('四级真题/2025.06/答案PDF/第一套.pdf'))

    def test_utf16_preserves_android_surrogates(self):
        self.assertEqual(5, module.utf16('😀 We'))
        self.assertEqual(3, module.utf16('A’s'))

    def test_page_furniture_removed_but_chinese_gloss_preserved(self):
        self.assertEqual('Before.  After (词义).', module.clean_furniture('Before. 2024年12月英语六级真题第3套 第4页，共7页 After (词义).'))
        self.assertEqual('In 2024年12月, things changed.', module.clean_furniture('In 2024年12月, things changed.'))

    def test_pdf_indent_keeps_paragraphs_not_visual_lines(self):
        text = '      This paragraph wraps over a\nline without a paragraph break.\n      This is a new paragraph.'
        self.assertEqual(['This paragraph wraps over a line without a paragraph break.', 'This is a new paragraph.'], module.layout_paragraphs(text).split('\n\n'))

    def test_provenance_audit_rejects_missing_license_and_modified_pack(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            directory = root/'apps/android/app/src/main/assets/intensive'
            model = 'a'*64
            identifier = 'cet4-2026-06-1-passage1'
            article = dict(complete=True, modelFingerprint=model, grammarDevice='cuda:0', translationDevice='cuda:0')
            data = json.dumps(article).encode()
            row = dict(id=identifier,source='synthetic',sourceUrl='https://example.org/source',license='synthetic-review',
                       licenseUrl='https://example.org/rights',sourceSha256='b'*64,textSha256='c'*64,
                       modelFingerprint=model,sha256=module.digest(data),bytes=len(data))
            catalog = dict(articles=[row])
            proof = dict(articles=[row],grammarSource='https://example.org/grammar',grammarLicense='test',
                         translationSource='https://example.org/translation',translationLicense='test',modelFingerprint=model,
                         modelFiles=[dict(path='synthetic',sha256='d'*64)])
            target = directory/'articles'/(identifier+'.json.gz')
            target.parent.mkdir(parents=True)
            target.write_bytes(gzip.compress(data))
            def save():
                module.write(directory/'catalog.json',catalog)
                module.write(root/'content/intensive/provenance.json',proof)
            save()
            with patch.object(audit,'ROOT',folder):
                self.assertEqual([],audit.check_intensive())
                row['license']=''; save()
                self.assertTrue(any('license' in problem for problem in audit.check_intensive()))
                row['license']='synthetic-review'; save()
                target.write_bytes(gzip.compress(data+b' '))
                self.assertTrue(any('指纹' in problem for problem in audit.check_intensive()))

    def test_expanded_mwt_uses_real_surface_span(self):
        words = [Object(id=1, text='going', start_char=None, end_char=None), Object(id=2, text='to', start_char=None, end_char=None)]
        sentence = Object(tokens=[Object(start_char=10, end_char=15, text='gonna', words=words)])
        self.assertEqual({1:(10,15), 2:(10,15)}, module.aligned_words(sentence))
        words[0].text='ca'; words[1].text="n't"
        sentence.tokens[0].text="can't"
        self.assertEqual({1:(10,12), 2:(12,15)}, module.aligned_words(sentence))

    def test_failed_cloze_does_not_hide_two_valid_passages(self):
        a = 'Students learn together and share their useful ideas. ' * 18
        b = 'Researchers study cities and investigate social connections. ' * 18
        text = ('Reading Comprehension\nSection A\nDirections: Answer Sheet 2.\n[26] incomplete\nSection B\n'
                'Section C\nPassage One\nQuestions 46 to 50 are based on the following passage.\n\n' + a +
                '\n46. What do students do?\nPassage Two\nQuestions 51 to 55 are based on the following passage.\n\n' + b +
                '\n51. What do researchers do?')
        paper = dict(id='cet4-2026-06-1')
        rows = module.extract_articles(text, paper)
        self.assertEqual(['passage1', 'passage2'], [r['kind'] for r in rows])
        self.assertEqual(a.strip(), rows[0]['paragraphs'][0])
        self.assertNotIn('46.', rows[0]['paragraphs'][0])

    def test_all_bundled_offsets_hashes_dependencies_and_coverage(self):
        directory = ROOT / 'apps/android/app/src/main/assets/intensive'
        self.assertTrue((directory / 'catalog.json').is_file(), 'real GPU packs must be bundled')
        catalog = json.loads((directory / 'catalog.json').read_text('utf8'))
        if not catalog['articles']:
            self.assertEqual('no-exam-texts', catalog.get('contentPolicy'))
            self.skipTest('Public source excludes unlicensed exam text packs; GPU pack coverage not asserted')
        count = 0
        for row in catalog['articles']:
            data = gzip.decompress((directory / 'articles' / (row['id'] + '.json.gz')).read_bytes())
            self.assertEqual(row['sha256'], module.digest(data))
            self.assertEqual(row['bytes'], len(data))
            article = json.loads(data)
            self.assertEqual(row['modelFingerprint'], article['modelFingerprint'])
            self.assertTrue(article['grammarDevice'].startswith('cuda'))
            self.assertTrue(article['translationDevice'].startswith('cuda'))
            self.assertEqual(row['textSha256'], module.digest('\n\n'.join(p['text'] for p in article['paragraphs'])))
            sentences = 0
            for p in article['paragraphs']:
                cursor = 0
                for sentence in p['sentences']:
                    start = p['text'].find(sentence['text'], cursor)
                    self.assertGreaterEqual(start, cursor)
                    self.assertFalse(p['text'][cursor:start].strip())
                    cursor = start + len(sentence['text'])
                    self.assertEqual(sentence['sha256'], module.digest(sentence['text']))
                    self.assertRegex(sentence['translation'], '[\u3400-\u9fff]')
                    tokens = sentence['tokens']
                    self.assertEqual(1, sum(t['head'] == 0 for t in tokens))
                    for i, token in enumerate(tokens, 1):
                        self.assertEqual(i, token['id'])
                        self.assertEqual(token['text'], sentence['text'].encode('utf-16-le')[token['start']*2:token['end']*2].decode('utf-16-le'))
                        seen = set(); head = i
                        while head:
                            self.assertNotIn(head, seen)
                            seen.add(head); head = tokens[head-1]['head']
                    sentences += 1
                self.assertFalse(p['text'][cursor:].strip())
            self.assertEqual(row['sentences'], sentences)
            if row['kind'] == 'cloze':
                self.assertEqual(list(range(26,36)), [v['number'] for v in article['fills']])
            count += sentences
        print('Validated', len(catalog['articles']), 'articles and', count, 'sentences')


if __name__ == '__main__':
    unittest.main()
