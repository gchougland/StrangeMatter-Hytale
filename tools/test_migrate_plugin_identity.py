"""Focused migration tests using temporary configs only; no installed saves."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import migrate_plugin_identity as migration


class PluginIdentityMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sm-identity-tests-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def config(self, name="config.json", document=None, raw=None):
        path = self.root / name
        path.write_bytes(raw if raw is not None else (json.dumps(document, indent=4) + "\n").encode())
        return path

    def run_cli(self, *args):
        output, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            status = migration.main(list(map(str, args)))
        return status, output.getvalue(), error.getvalue()

    def test_dry_run_is_byte_exact_and_does_not_create_backup(self):
        path = self.config(document={"Mods": {migration.OLD_ID: {"Enabled": True}}})
        original = path.read_bytes()
        status, output, _ = self.run_cli(path)
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(output)["files"][0]["status"], "would_update")
        self.assertEqual(path.read_bytes(), original)
        self.assertEqual(list(self.root.iterdir()), [path])

    def test_apply_updates_only_native_fields_and_preserves_exact_backup(self):
        document = {
            "Mods": {"Other:Mod": {"Enabled": False}, migration.OLD_ID: {"Enabled": True, "Nested": [1, "x"]}},
            "Plugins": {migration.OLD_ID: {"Enabled": False}},
            "RequiredPlugins": {migration.OLD_ID: ">=0.8.4", "Other:Mod": "*"},
            "ModLoadOrder": ["Other:Mod", migration.OLD_ID, "Other:Mod", migration.NEW_ID],
            "Description": migration.OLD_ID,
            "Nested": {"Mods": {migration.OLD_ID: 19}},
            "Integer": 123456789123456789, "Boolean": True,
        }
        raw = b"\xef\xbb\xbf" + (json.dumps(document, indent=4) + "\n").replace("\n", "\r\n").encode()
        path = self.config(raw=raw)
        status, output, _ = self.run_cli("--apply", path)
        self.assertEqual(status, 0)
        report = json.loads(output)["files"][0]
        self.assertEqual(set(report["fields"]), {"Mods", "Plugins", "RequiredPlugins", "ModLoadOrder"})
        self.assertEqual(Path(report["backup"]).read_bytes(), raw)
        result = json.loads(path.read_bytes().decode("utf-8-sig"))
        for field in migration.MAP_FIELDS:
            self.assertNotIn(migration.OLD_ID, result[field])
            self.assertEqual(result[field][migration.NEW_ID], document[field][migration.OLD_ID])
        self.assertEqual(result["ModLoadOrder"], ["Other:Mod", migration.NEW_ID, "Other:Mod"])
        for field in ("Description", "Nested", "Integer", "Boolean"):
            self.assertEqual(result[field], document[field])
        self.assertTrue(path.read_bytes().startswith(b"\xef\xbb\xbf"))
        self.assertNotIn(b"\n", path.read_bytes().replace(b"\r\n", b""))
        written = path.read_bytes()
        self.assertEqual(self.run_cli("--apply", path)[0], 0)
        self.assertEqual(path.read_bytes(), written)
        self.assertEqual(len(list(self.root.glob("*.bak"))), 1)

    def test_conflict_preflights_all_files_without_writes(self):
        first = self.config("first.json", {"Mods": {migration.OLD_ID: True}})
        second = self.config("second.json", {"Mods": {migration.OLD_ID: True, migration.NEW_ID: False}})
        original = {p: p.read_bytes() for p in (first, second)}
        status, _, error = self.run_cli("--apply", first, second)
        self.assertEqual(status, 1)
        self.assertIn("conflicting", error)
        self.assertEqual({p: p.read_bytes() for p in (first, second)}, original)
        self.assertEqual(set(self.root.iterdir()), {first, second})

    def test_equivalent_keys_collapse_but_boolean_number_conflicts(self):
        path = self.config(document={"Mods": {migration.NEW_ID: {"Enabled": True}, migration.OLD_ID: {"Enabled": True}}})
        result = json.loads(migration.prepare(path).replacement)
        self.assertEqual(result["Mods"], {migration.NEW_ID: {"Enabled": True}})
        path.write_text(json.dumps({"Plugins": {migration.OLD_ID: True, migration.NEW_ID: 1}}))
        with self.assertRaises(migration.MigrationError):
            migration.prepare(path)

    def test_unchanged_file_keeps_format_and_repeated_path_is_one_plan(self):
        path = self.config(raw=b'{ "Mods" : {"Other:Mod":true}, "Text":"Hexvane:StrangeMatter" }')
        plans = migration.prepare_all([path, path])
        self.assertEqual(len(plans), 1)
        self.assertFalse(plans[0].changed)
        self.assertEqual(plans[0].replacement, path.read_bytes())
        self.assertEqual(migration.apply_plans(plans), {})

    def test_invalid_or_duplicate_json_is_rejected_unchanged(self):
        for index, raw in enumerate((b'{"Mods":{},"Mods":{}}', b'{"Mods":{"A":{"x":1,"x":2}}}',
                                     b'{"Mods":[]}', b'{"ModLoadOrder":[1]}', b'{"x":NaN}', b'[]', b'{broken}')):
            with self.subTest(raw=raw):
                path = self.config(f"bad-{index}.json", raw=raw)
                self.assertEqual(self.run_cli("--apply", path)[0], 1)
                self.assertEqual(path.read_bytes(), raw)
        self.assertFalse(list(self.root.glob("*.bak")))

    def test_concurrent_change_refuses_entire_prepared_batch(self):
        first = self.config("first.json", {"Mods": {migration.OLD_ID: True}})
        second = self.config("second.json", {"Mods": {migration.OLD_ID: True}})
        plans = migration.prepare_all([first, second])
        first_bytes = first.read_bytes()
        second.write_bytes(b'{"ServerNowEditing":true}')
        with self.assertRaises(migration.MigrationError):
            migration.apply_plans(plans)
        self.assertEqual(first.read_bytes(), first_bytes)
        self.assertEqual(second.read_bytes(), b'{"ServerNowEditing":true}')
        self.assertFalse(list(self.root.glob("*.bak")))


if __name__ == "__main__":
    unittest.main()
