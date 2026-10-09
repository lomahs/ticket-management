"""Writes the API's OpenAPI document: python -m tracker.adapters.api.openapi <output.json>"""

import json
import sys
from pathlib import Path

from .app import create_app


def document() -> dict:
    return create_app(None).openapi()  # the schema does not depend on the wired adapters


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(document(), ensure_ascii=False, indent=1) + "\n", "utf-8")
    print(f"wrote {out}")
