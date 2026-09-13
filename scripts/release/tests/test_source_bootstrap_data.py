import copy
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
from source_bootstrap_fixtures import RELEASE, encoded, initial_document
import source_bootstrap_data as data


class BootstrapDataTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "input.json"
        self.document = initial_document()

    def write(self, document=None):
        self.path.write_bytes(encoded(self.document if document is None else document))

    def test_reference_is_independently_bound_to_reviewed_app_bundle(self):
        source = RELEASE.parents[1] / "composeApp/src/commonMain/kotlin/me/manga/kira/sources/runtime/BundledSourcesConfig.kt"
        raw = source.read_bytes()
        self.assertEqual("d4cc1de96901ced254170d594e7e7f33bb6002fa64ac9c15135cbacd9f0f5702", data.sha256(raw))
        literal = raw.decode().split('const val CONFIG_BACKED_SOURCES_JSON: String = """', 1)[1].split('"""', 1)[0]
        literal = literal.replace("${'$'}", "$")
        self.assertNotIn("${", literal)
        document = json.loads(literal)
        projection = {key: document[key] for key in ("schemaVersion", "revision", "sources")}
        self.assertEqual(projection, json.loads(data.REFERENCE.read_bytes()))
        self.assertEqual(30742, data.REFERENCE.stat().st_size)
        self.assertEqual("42a26ca29182a0c8c1150196ff55979fc41a8d828ed60556e9dcf6062b8b9095",
                         data.sha256(data.REFERENCE.read_bytes()))

    def test_default_and_type_inventory_matches_actual_app_model(self):
        path = RELEASE.parents[1] / "sources/contracts/src/commonMain/kotlin/me/manga/kira/sources/contracts/model/SourceConfig.kt"
        names = dict(zip(("SourceConfigDocument", "SourceConfig", "IconSpec", "PaginationSpec", "EndpointSpec", "FilterSpec",
                          "FieldSpec", "TransformSpec", "FilterDefinition", "FilterOptionSpec", "FilterRequestSpec", "FilterConditionSpec"),
                         ("document", "source", "icon", "pagination", "endpoint", "filter", "field", "transform",
                          "filterDefinition", "option", "request", "condition")))
        found = set()
        for name, body in re.findall(r"data class (\w+)\(\n(.*?)\n\)", path.read_text(), re.DOTALL):
            if name not in names:
                continue
            fields = {}
            for field, declaration in re.findall(r"^\s*val (\w+): ([^\n]+),$", body, re.MULTILINE):
                kind, separator, default = declaration.partition(" = ")
                fields[field] = (model_kind(kind, names), model_default(default) if separator else data.REQUIRED)
            self.assertEqual(data.SCHEMAS[names[name]], fields, name)
            found.add(name)
        self.assertEqual(set(names), found)

    def test_valid_input_keeps_provenance_and_explicit_defaults(self):
        self.document["revision"] = 321
        self.document["generatedAt"] = "input provenance only"
        self.document["sources"][0].update(lifecycle="active", priority=0, displayName="Azora", enabled=False)
        self.document["sources"][0]["endpoints"]["details"]["method"] = "get"
        self.document["sources"][1].pop("displayName")  # api-dependent default is part of the typed model.
        self.write()
        self.assertEqual(self.path.read_bytes(), data.validated_input(self.path))

    def test_legacy_content_and_positions_are_not_an_extra_reference_gate(self):
        generic, legacy = self.document["sources"][:12], self.document["sources"][12:]
        legacy.reverse()
        legacy[0].update(displayName="historical descriptor", headers={"X-Review": "data only"})
        self.document["sources"] = legacy[:3] + generic[:6] + legacy[3:] + generic[6:]
        self.write()
        self.assertEqual(self.path.read_bytes(), data.validated_input(self.path))

    def test_frozen_bytes_are_exact_and_original_replacement_is_irrelevant(self):
        original = b" \n" + encoded(self.document) + b"\n\t"
        self.path.write_bytes(original)
        destination = self.path.with_name("frozen.json")
        data.stage_input(self.path, destination)
        self.path.write_bytes(b"replaced after validation")
        self.assertEqual(original, destination.read_bytes())
        self.assertEqual(0o400, destination.stat().st_mode & 0o777)

    def test_full_generic_field_drift_rejects_before_creating_snapshot(self):
        edits = [
            lambda d: d["sources"][0]["endpoints"]["details"].update(url="{itemUrl}"),
            lambda d: d["sources"][1].update(displayName="different name"),
            lambda d: d["sources"][1].update(headers={"X-Language": "changed"}),
            lambda d: d["sources"][2].update(lifecycle="disabled"),
            lambda d: d["sources"][2].update(priority=1),
            lambda d: d["sources"][3]["endpoints"]["details"].update(method="post-json"),
            lambda d: d["sources"].reverse(),
        ]
        for index, edit in enumerate(edits):
            with self.subTest(index=index):
                value = copy.deepcopy(self.document)
                edit(value)
                self.write(value)
                destination = self.path.with_name("rejected.json")
                with self.assertRaises(data.VerificationError):
                    data.stage_input(self.path, destination)
                self.assertFalse(destination.exists())

    def test_inventory_is_unique_45_not_just_generic_12(self):
        for sources in (self.document["sources"][:12], [], self.document["sources"][:-1],
                        self.document["sources"][:-1] + [self.document["sources"][0]]):
            with self.subTest(count=len(sources)):
                self.write({**self.document, "sources": sources})
                with self.assertRaises(data.VerificationError):
                    data.validated_input(self.path)

    def test_bounded_strict_json_rejects_utf8_duplicates_numbers_and_trailing_input(self):
        valid = encoded(self.document)
        for raw in (b"\xff", valid + b"{}", valid.replace(b'"schemaVersion":1', b'"schemaVersion":1,"schemaVersion":1'),
                    valid.replace(b'"revision":4', b'"revision":true'), valid.replace(b'"revision":4', b'"revision":4.0'),
                    valid.replace(b'"revision":4', b'"revision":NaN'), b" " * (data.MAX_BYTES + 1),
                    valid.replace(b'"revision":4', b'"revision":9223372036854775808'),
                    valid.replace(b'"schemaVersion":1', b'"schemaVersion":2147483648')):
            with self.subTest(prefix=raw[:12]):
                self.path.write_bytes(raw)
                with self.assertRaises(data.VerificationError):
                    data.validated_input(self.path)

    def test_unknown_nested_fields_and_explicit_null_do_not_disappear(self):
        for field, value in (("unrecognized", "ignored would be unsafe"), ("method", None), ("method", True)):
            with self.subTest(field=field, value=value):
                document = copy.deepcopy(self.document)
                document["sources"][0]["endpoints"]["details"][field] = value
                self.write(document)
                with self.assertRaises(data.VerificationError):
                    data.validated_input(self.path)

    def test_missing_or_changed_reference_fails_closed(self):
        self.write()
        reference = self.path.with_name("missing-reference.json")
        with patch.object(data, "REFERENCE", reference):
            with self.assertRaises(OSError):
                data.validated_input(self.path)
            reference.write_bytes(data.REFERENCE_SHA256.encode())
            with self.assertRaises(data.VerificationError):
                data.validated_input(self.path)


def model_kind(value, names):
    value = value.replace(" ", "")
    if value.endswith("?"):
        return model_kind(value[:-1], names) + "?"
    if value.startswith("List<"):
        return "list:" + model_kind(value[5:-1], names)
    if value.startswith("Map<String,"):
        return "map:" + model_kind(value[11:-1], names)
    return {"String": "str", "Int": "int", "Long": "long", "Boolean": "bool", **names}[value]


def model_default(value):
    if value == "api":
        return data.API_DEFAULT
    if value in ("emptyMap()", "PaginationSpec()"):
        return {}
    if value == "emptyList()":
        return []
    if value.startswith("listOf("):
        return json.loads("[" + value[7:-1] + "]")
    return json.loads(value)
