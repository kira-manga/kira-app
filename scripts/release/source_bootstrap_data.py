"""Strict local bootstrap DATA comparison, never a kcj-1 encoder/canonicalizer.

Field types/defaults mirror sources/contracts/.../model/SourceConfig.kt and the
backend sourceconfig/domain/model/SourceConfig.kt. Unknown fields fail closed.
The versioned reference is the approved App2af11787 bundle, unchanged at4b4f9539:
source SHA256 d4cc1de96901ced254170d594e7e7f33bb6002fa64ac9c15135cbacd9f0f5702.
Only normalized Python values are compared; submitted and signed bytes stay raw.
"""

import hashlib
import json
import os
from pathlib import Path

MAX_BYTES = 5 * 1024 * 1024
REFERENCE_BYTES = 30742
REFERENCE_SHA256 = "42a26ca29182a0c8c1150196ff55979fc41a8d828ed60556e9dcf6062b8b9095"
POLICY_ID = "app-bundle-v6-initial-catalog-v1"
REFERENCE = Path(__file__).with_name("reference") / "app-bundle-v6-generic.json"
GENERIC_APIS = (
    "Azora", "Mangamello", "Mangamello Plus", "SwatManga", "Lekmanga", "Team X",
    "DilarV2", "3asq", "Demonicscans", "Mangabuddy", "Zazamanga", "Tapas",
)
LEGACY_APIS = (
    "Lavatoons", "Mangatuk", "Dilar", "Promanga", "Prochan", "Batoto", "Manhwatop",
    "Comick", "Mangapark", "مانجا بارك", "Mangapark-It", "Mangapark-Es",
    "Mangapark-Es-La", "Olympusbiblioteca", "Manhwaweb", "Taurus Fansub", "Inmanga",
    "Komik Cast", "Komiku", "Manga Origine", "Raijinscan", "Manhastro", "Flowermanga",
    "Mediocretoons", "Desu", "Mangahub", "Batcave", "Timenaight", "Webtoontr",
    "Webtoonhatti", "Mangaworld", "Senkuro", "Sussytoons",
)
REQUIRED = object()
API_DEFAULT = object()

# (type, default); required and api-dependent defaults are explicit, not inferred
# from a candidate. This small schema is solely the fixed bootstrap wire version.
SCHEMAS = {
    "document": {
        "schemaVersion": ("int", REQUIRED), "generatedAt": ("str?", None),
        "revision": ("long", 0), "sources": ("list:source", []),
    },
    "source": {
        "api": ("str", REQUIRED), "language": ("str", REQUIRED),
        "displayName": ("str", API_DEFAULT), "baseUrl": ("str", REQUIRED),
        "imageBase": ("str", ""), "enabled": ("bool", False), "priority": ("int", 0),
        "engine": ("str", "legacy"), "minAppVersion": ("str?", None),
        "headers": ("map:str", {}), "usesCapturedHeaders": ("bool", True),
        "pagination": ("pagination", {}), "endpoints": ("map:endpoint", {}),
        "fields": ("map:field", {}), "blacklistGenres": ("list:str", []),
        "siteState": ("str", "WORKING"), "lifecycle": ("str", "active"),
        "previousHosts": ("list:str", []), "previousImageHosts": ("list:str", []),
        "trustedHosts": ("list:str", []), "icon": ("icon?", None),
        "filters": ("list:filterDefinition", []),
    },
    "icon": {"resourceKey": ("str", ""), "remoteUrl": ("str", "")},
    "pagination": {"type": ("str", "page-number"), "param": ("str", "page"), "start": ("int", 1)},
    "endpoint": {
        "url": ("str", REQUIRED), "method": ("str", "get"), "format": ("str", ""),
        "scriptId": ("str", ""), "root": ("str", ""), "rootDirs": ("list:str", []),
        "listSelector": ("str", ""), "formBody": ("map:str", {}), "jsonBody": ("str", ""),
        "listFilters": ("list:filter", []), "pageParam": ("str", ""), "lastPageLocator": ("str", ""),
    },
    "filter": {
        "path": ("str", REQUIRED), "op": ("str", REQUIRED),
        "value": ("str", ""), "mode": ("str", "exclude"),
    },
    "field": {
        "path": ("str", ""), "selector": ("str", ""), "attr": ("str", "text"),
        "fallbackPath": ("str", ""), "fallbackSelectors": ("list:str", []),
        "lazyAttrChain": ("list:str", []), "template": ("str", ""), "vars": ("map:str", {}),
        "listPath": ("str", ""), "listSelector": ("str", ""), "imageStrategy": ("str", ""),
        "dateStrategy": ("str", ""), "transform": ("list:transform", []),
    },
    "transform": {"fn": ("str", REQUIRED), "args": ("map:str", {}), "list": ("list:str", [])},
    "filterDefinition": {
        "id": ("str", REQUIRED), "label": ("str", REQUIRED), "type": ("str", REQUIRED),
        "options": ("list:option", []), "default": ("str", ""), "defaults": ("list:str", []),
        "required": ("bool", False), "request": ("request", REQUIRED),
        "visibleWhen": ("list:condition", []), "excludeOf": ("str", ""),
        "appliesTo": ("list:str", ["search"]),
    },
    "option": {"value": ("str", REQUIRED), "label": ("str", "")},
    "request": {
        "target": ("str", REQUIRED), "param": ("str", REQUIRED), "encode": ("str", "single"),
        "delimiter": ("str", ","), "omitIfEmpty": ("bool", True),
        "trueValue": ("str", "true"), "falseValue": ("str", ""),
    },
    "condition": {"filter": ("str", REQUIRED), "anyOf": ("list:str", REQUIRED)},
    "manifest": {
        "schemaVersion": ("int", REQUIRED), "sourceSchemaVersion": ("int", REQUIRED),
        "catalogRevision": ("long", REQUIRED), "generatedAt": ("str", REQUIRED),
        "sources": ("list:entry", REQUIRED), "removedSources": ("list:removed", []),
    },
    "entry": {
        "api": ("str", REQUIRED), "sourceRevision": ("int", REQUIRED),
        "checksum": ("str", REQUIRED), "order": ("int", REQUIRED),
        "lifecycle": ("str", REQUIRED), "engine": ("str", REQUIRED),
        "sourceSigningKeyId": ("str", REQUIRED), "sourceSignature": ("str", REQUIRED),
    },
    "removed": {"api": ("str", REQUIRED), "lifecycle": ("str", "removed")},
    "receipt": {
        "policyId": ("str", REQUIRED), "referenceSha256": ("str", REQUIRED),
        "payloadSha256": ("str", REQUIRED), "documentRevision": ("long", REQUIRED),
        "documentChecksum": ("str", REQUIRED), "catalogRevision": ("long", REQUIRED),
        "catalogChecksum": ("str", REQUIRED), "completedAt": ("str", REQUIRED),
        "actorId": ("str", REQUIRED),
    },
}


class VerificationError(Exception):
    """Fixed diagnostic only: never include submitted values or file contents."""


class DeliveryProofUnavailable(VerificationError):
    """The immutable origin is not the public latest; no repair is authorized."""


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def read_bytes(path, limit=MAX_BYTES):
    with open(path, "rb") as stream:
        raw = stream.read(limit + 1)
    require(len(raw) <= limit, "local input exceeds its byte limit")
    return raw


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON field")
            result[key] = value
        return result

    def no_float(_):
        raise VerificationError("non-integer JSON number")

    try:
        return json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=pairs,
                          parse_float=no_float, parse_constant=no_float)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise VerificationError("invalid strict UTF-8 JSON") from error


def normalize(kind, value):
    if kind.endswith("?"):
        return None if value is None else normalize(kind[:-1], value)
    if kind.startswith("list:"):
        require(type(value) is list, "JSON field type mismatch")
        return [normalize(kind[5:], item) for item in value]
    if kind.startswith("map:"):
        require(type(value) is dict, "JSON field type mismatch")
        return {normalize("str", key): normalize(kind[4:], item) for key, item in value.items()}
    if kind in ("str", "bool", "int", "long"):
        expected = {"str": str, "bool": bool, "int": int, "long": int}[kind]
        require(type(value) is expected, "JSON field type mismatch")
        if kind == "str":
            value.encode("utf-8", errors="strict")
        elif kind in ("int", "long"):
            bits = 32 if kind == "int" else 64
            require(-(2 ** (bits - 1)) <= value < 2 ** (bits - 1), "JSON integer is out of range")
        return value
    return normalize_object(kind, value)


def normalize_object(kind, value):
    fields = SCHEMAS[kind]
    require(type(value) is dict and not (value.keys() - fields.keys()), "unknown JSON field or object type")
    result = {}
    for name, (field_kind, default) in fields.items():
        selected = value.get(name, default)
        if selected is API_DEFAULT:
            selected = value.get("api", REQUIRED)
        require(selected is not REQUIRED, "required JSON field is missing")
        result[name] = normalize(field_kind, selected)
    return result


def reference_sources():
    raw = read_bytes(REFERENCE, REFERENCE_BYTES)
    require(len(raw) == REFERENCE_BYTES and sha256(raw) == REFERENCE_SHA256,
            "approved bootstrap reference is unavailable or changed")
    document = normalize("document", strict_json(raw))
    require(document["schemaVersion"] == 1 and document["revision"] == 6 and document["generatedAt"] is None,
            "approved bootstrap reference metadata mismatch")
    require(tuple(source["api"] for source in document["sources"]) == GENERIC_APIS,
            "approved bootstrap reference inventory mismatch")
    return document["sources"]


def validated_input(path):
    raw = read_bytes(path)
    document = normalize("document", strict_json(raw))
    require(document["schemaVersion"] == 1, "unsupported bootstrap source schema")
    apis = [source["api"] for source in document["sources"]]
    require(len(apis) == 45 and set(apis) == set(GENERIC_APIS + LEGACY_APIS),
            "bootstrap requires exactly the reviewed 45 unique source APIs")
    generics = [source for source in document["sources"] if source["engine"] == "generic"]
    require(generics == reference_sources(), "bootstrap generic content, lifecycle, priority or order differs")
    return raw


def stage_input(source, destination):
    raw = validated_input(source)  # Read once; write the SAME bytes, never a JSON re-encoding.
    with os.fdopen(os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400), "wb") as stream:
        stream.write(raw)
