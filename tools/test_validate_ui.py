"""Regression checks for the native markup escape contract and every shipped UI page."""
import unittest
from validate_ui import structure, main as validate_all_pages


class NativeUiSyntaxTests(unittest.TestCase):
    def test_rejects_non_native_string_escapes(self):
        for escape in ('n', 'r', 't', 'u1234', 'x41', '0', 'q'):
            with self.subTest(escape=escape):
                with self.assertRaisesRegex(ValueError, r'bad.ui:2:\d+: unsupported UI string escape'):
                    structure('Group {\n Label { Text: "before' + chr(92) + escape + 'after"; } }', 'bad.ui')

    def test_accepts_escaped_backslashes_and_quotes(self):
        structure(r'Label { Text: "Path \\ and quote \" inside"; }', 'valid.ui')
        # An escaped backslash followed by n is literal text, not a newline escape.
        structure(r'Label { Text: "literal \\n"; }', 'literal.ui')

    def test_ignores_comment_text(self):
        structure('// ignored "' + chr(92) + 'q\nLabel { Text: "valid"; }', 'comment.ui')

    def test_rejects_unterminated_strings(self):
        with self.assertRaises(AssertionError):
            structure('Label { Text: "unfinished' + chr(92), 'unfinished.ui')

    def test_all_shipped_pages(self):
        validate_all_pages()


if __name__ == '__main__':
    unittest.main()
