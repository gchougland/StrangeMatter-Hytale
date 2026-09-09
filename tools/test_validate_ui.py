"""Regression checks for the native markup escape contract and every shipped UI page."""
import unittest
from validate_ui import structure, parameter_declarations, BASE, main as validate_all_pages


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

    def test_rejects_glyph_parameter_overrides_after_anchor(self):
        broken='$G.@Fork #RuneGlow0 { Anchor: (Left: 76, Top: 0, Width: 37, Height: 29); @Ink = #efffff; @Accent = #a7faff; }'
        with self.assertRaisesRegex(ValueError,r'ResearchMachine.ui:1:\d+: UI parameter declaration after a property or child'):
            structure(broken,'ResearchMachine.ui')

    def test_rejects_parameter_declaration_after_child(self):
        with self.assertRaisesRegex(ValueError,'UI parameter declaration after a property or child'):
            structure('@Glyph = Group { Group { Background: #efffff; } @Ink = #efffff; };','late-child.ui')

    def test_accepts_native_leading_template_parameters(self):
        structure('@NumberInput = Group { @Visible = false; @Left = 32; LayoutMode: Left; Anchor: (Left: @Left, Top: 6); };','native-template.ui')
        structure('$C.@NumberField #Input { @Anchor = (Width: 60, Left: 0, Right: 16); Format: (MaxDecimalPlaces: 2, Step: 0.5); }','native-instance.ui')

    def test_parameter_validation_ignores_text_and_comments(self):
        structure('Group { Text: "@Ink = #ffffff;"; // @Ink = #ffffff;\n Group { @Ink = #64dce6; Background: @Ink; } }','ignored.ui')

    @unittest.skipUnless(BASE.exists(),'Supplied native UI examples are unavailable')
    def test_parameter_order_accepts_every_supplied_native_ui(self):
        for path in BASE.rglob('*.ui'):
            with self.subTest(path=path):
                parameter_declarations(path.read_text(encoding='utf-8-sig'),str(path))

    def test_all_shipped_pages(self):
        validate_all_pages()


if __name__ == '__main__':
    unittest.main()
