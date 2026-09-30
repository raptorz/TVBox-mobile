"""Run with python3.12 -m unittest discover -s chaquo/tests -v.

Installing ujson==5.11.0 in the test environment also enables comparisons
against the native encoder; it is never installed as an Android dependency.
"""
import importlib.util
import decimal
import io
import pathlib
import unittest

_spec = importlib.util.spec_from_file_location(
    "spider_ujson", pathlib.Path(__file__).parents[1] / "src/main/python/ujson.py")
ujson = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ujson)
try:
    import ujson as reference
except ImportError:
    reference = None


class UjsonCompatibilityTest(unittest.TestCase):
    def test_compact_output_and_escaping(self):
        self.assertEqual(ujson.dumps({"url": "https://a/b", "n": 1}),
                         '{"url":"https:\\/\\/a\\/b","n":1}')
        self.assertEqual(ujson.dumps("<>&/", encode_html_chars=True),
                         '"\\u003c\\u003e\\u0026\\/"')
        self.assertEqual(ujson.dumps("中文/", ensure_ascii=False,
                                    escape_forward_slashes=False), '"中文/"')
        # Escaped backslashes and literal unicode sequences must survive.
        value = r'\u003c\path/<&'
        self.assertEqual(ujson.loads(ujson.dumps(value, encode_html_chars=True)), value)

    def test_bytes(self):
        with self.assertRaises(TypeError):
            ujson.dumps({"nested": [b"abc"]})
        self.assertEqual(ujson.dumps({b"key": [b"abc"]}, reject_bytes=False),
                         '{"key":["abc"]}')
        with self.assertRaises(UnicodeDecodeError):
            ujson.dumps(b"\xff", reject_bytes=False)

    def test_file_api_aliases_and_errors(self):
        output = io.StringIO()
        ujson.dump({"a": [1, True, None]}, output)
        self.assertEqual(ujson.load(io.StringIO(output.getvalue())), {"a": [1, True, None]})
        self.assertEqual(ujson.decode(ujson.encode([1])), [1])
        with self.assertRaises(ujson.JSONDecodeError):
            ujson.loads("{")
        for option in ("double_precision", "precise_float", "unknown"):
            with self.assertRaises(TypeError):
                ujson.dumps({}, **{option: True})
        with self.assertRaises(OverflowError):
            ujson.dumps(float("nan"), allow_nan=False)

    @unittest.skipIf(reference is None, "optional native ujson reference not installed")
    def test_native_reference(self):
        cases = [
            ({"b": [1, 2], "a": "中文/<>&"}, {}),
            ({"b": [1, 2], "a": "中文/<>&"}, {"sort_keys": True, "indent": 2}),
            ("<>&/\\\"\n", {"encode_html_chars": True, "ensure_ascii": False}),
            ({b"a": ["中文".encode()]}, {"reject_bytes": False}),
            ({"a": 1}, {"separators": (", ", ": ")}),
            ({"a": 1}, {"indent": -1}),
            ({"amount": decimal.Decimal("1.25")}, {}),
            (object(), {"default": lambda value: {"url": "https://example.com"}}),
        ]
        for obj, options in cases:
            with self.subTest(obj=obj, options=options):
                self.assertEqual(ujson.dumps(obj, **options), reference.dumps(obj, **options))
        self.assertEqual(ujson.dumps("中文/<>", False, True, False),
                         reference.dumps("中文/<>", False, True, False))

    @unittest.skipIf(reference is None, "optional native ujson reference not installed")
    def test_native_error_contract(self):
        cases = [(float("nan"), {"allow_nan": False}),
                 ({"a": [float("inf")]}, {"allow_nan": False}),
                 (b"abc", {}), (b"\xff", {"reject_bytes": False}),
                 ({}, {"indent": None}),
                 ({}, {"double_precision": 3}), ({}, {"precise_float": True}),
                 ({}, {"separators": [",", ":"]})]
        for obj, options in cases:
            with self.subTest(obj=obj, options=options):
                try:
                    reference.dumps(obj, **options)
                except Exception as error:
                    with self.assertRaises(type(error)):
                        ujson.dumps(obj, **options)
                else:
                    self.fail("native ujson unexpectedly accepted invalid input")


if __name__ == "__main__":
    unittest.main()
