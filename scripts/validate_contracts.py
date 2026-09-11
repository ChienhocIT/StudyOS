"""Validate the frozen public contracts and generated internal contract without network IO."""
from pathlib import Path
import json
import sys
import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


class UniqueKeysLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"Duplicate YAML key: {key}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeysLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def check_refs(node, document):
    if isinstance(node, dict):
        reference = node.get("$ref")
        if reference and reference.startswith("#/"):
            target = document
            for part in reference[2:].split("/"):
                target = target[part.replace("~1", "/").replace("~0", "~")]
        for value in node.values():
            check_refs(value, document)
    elif isinstance(node, list):
        for value in node:
            check_refs(value, document)


def main():
    paths = [ROOT / "openapi-core.yaml", ROOT / "asyncapi-rabbitmq.yaml"]
    internal = ROOT / "packages/contracts/openapi-ai-internal.yaml"
    if internal.exists():
        paths.append(internal)
    for path in paths:
        document = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueKeysLoader)
        check_refs(document, document)
        operations = [operation["operationId"] for item in document.get("paths", {}).values()
                      for operation in item.values() if isinstance(operation, dict) and "operationId" in operation]
        if len(operations) != len(set(operations)):
            raise ValueError(f"Duplicate operationId in {path.name}")
        print(f"PASS {path.name}: references and {len(operations)} unique operations")
    for name in ("event-envelope.schema.json", "websocket-protocol.schema.json"):
        schema = json.loads((ROOT / name).read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        check_refs(schema, schema)
        print(f"PASS {name}: valid JSON schema")
    examples = json.loads((ROOT / "websocket-examples.json").read_text(encoding="utf-8"))
    schema = json.loads((ROOT / "websocket-protocol.schema.json").read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    values = examples if isinstance(examples, list) else examples.values()
    count = 0
    for value in values:
        frames = value if isinstance(value, list) else [value]
        for frame in frames:
            if isinstance(frame, dict) and "protocolVersion" in frame:
                validator.validate(frame)
                count += 1
    print(f"PASS WebSocket examples: {count} frames")
    if "--require-internal" in sys.argv and not internal.exists():
        raise ValueError("Internal AI OpenAPI contract has not been generated")


if __name__ == "__main__":
    main()
