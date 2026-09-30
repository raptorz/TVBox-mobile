"""Restore signing inputs on an ephemeral Actions runner without logging their values."""
import base64
import binascii
import os
from pathlib import Path


def property_value(value):
    # Properties.load(InputStream) uses Latin-1; escape UTF-16 code units, including
    # spaces, backslashes and newlines, so arbitrary passwords round-trip exactly.
    encoded = value.encode("utf-16-be")
    return "".join(f"\\u{int.from_bytes(encoded[i:i + 2], 'big'):04x}" for i in range(0, len(encoded), 2))


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("This script is only intended for a GitHub Actions runner.")
    required = ("SIGNING_KEYSTORE_BASE64", "SIGNING_KEY_ALIAS", "SIGNING_STORE_PASSWORD")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise SystemExit("Missing repository Secrets: " + ", ".join(missing))
    paths = (Path("local.properties"), Path("TVBox-media/local.properties"))
    if any(path.exists() for path in paths):
        raise SystemExit("Refusing to overwrite existing local.properties files.")
    try:
        data = base64.b64decode("".join(os.environ[required[0]].split()), validate=True)
    except (ValueError, binascii.Error):
        raise SystemExit("SIGNING_KEYSTORE_BASE64 is not valid Base64.") from None
    if not data:
        raise SystemExit("SIGNING_KEYSTORE_BASE64 contains no keystore data.")
    keystore = Path(os.environ["RUNNER_TEMP"]) / "tvbox-release.jks"
    os.umask(0o077)
    keystore.write_bytes(data)
    values = {
        "sdk.dir": os.environ["ANDROID_HOME"],
        "storeFile": str(keystore),
        "keyAlias": os.environ["SIGNING_KEY_ALIAS"],
        "storePassword": os.environ["SIGNING_STORE_PASSWORD"],
        "keyPassword": os.environ.get("SIGNING_KEY_PASSWORD") or os.environ["SIGNING_STORE_PASSWORD"],
    }
    paths[0].write_text("".join(f"{key}={property_value(value)}\n" for key, value in values.items()), encoding="ascii")
    paths[1].write_text(f"sdk.dir={property_value(values['sdk.dir'])}\n", encoding="ascii")
    print("Signing files prepared (secret values omitted).")


if __name__ == "__main__":
    main()
