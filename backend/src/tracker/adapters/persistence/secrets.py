"""Encrypts the stored Jira API token (Fernet)."""

import os
from pathlib import Path

from cryptography.fernet import Fernet


class FernetSecretBox:
    def __init__(self, key: bytes):
        self.f = Fernet(key)

    @classmethod
    def from_key_file(cls, path: Path) -> "FernetSecretBox":
        """Uses TRACKER_SECRET_KEY when set; otherwise a key file, created on first use."""
        env = os.environ.get("TRACKER_SECRET_KEY")
        if env:
            return cls(env.encode())
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(Fernet.generate_key())
            path.chmod(0o600)
        return cls(path.read_bytes().strip())

    def encrypt(self, plain: str) -> str:
        return self.f.encrypt(plain.encode()).decode() if plain else ""

    def decrypt(self, token: str) -> str:
        return self.f.decrypt(token.encode()).decode() if token else ""
