import subprocess
import sys
from pathlib import Path


def test_layer_contracts_hold():
    root = Path(__file__).resolve().parents[1]
    r = subprocess.run([str(Path(sys.executable).with_name("lint-imports"))], cwd=root, capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
