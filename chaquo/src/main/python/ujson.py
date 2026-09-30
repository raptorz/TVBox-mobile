"""Pure-Python compatibility for the ujson 5.x API used by spiders.

Chaquopy has no CPython 3.12 ujson wheel. Implement the supported encoder
options instead of silently discarding them. Unknown/obsolete options raise
TypeError, as in ujson 5.x. This is not a byte-for-byte replacement for the C
encoder: floating-point formatting and custom __json__ hooks are not emulated.
"""

import decimal as _decimal
import json as _json
import math as _math
import operator as _operator

__all__ = ["dump", "dumps", "load", "loads", "encode", "decode", "JSONDecodeError"]
JSONDecodeError = _json.JSONDecodeError


def _prepare(obj, reject_bytes, allow_nan):
    if isinstance(obj, float) and not allow_nan and not _math.isfinite(obj):
        raise OverflowError("Invalid value when encoding double")
    if isinstance(obj, bytes):
        if reject_bytes:
            raise TypeError("bytes are not JSON serializable with reject_bytes=True")
        return obj.decode("utf-8")
    if isinstance(obj, dict):
        # ujson accepts UTF-8 bytes keys even when reject_bytes is enabled.
        return {(key.decode("utf-8") if isinstance(key, bytes) else key):
                _prepare(value, reject_bytes, allow_nan) for key, value in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_prepare(value, reject_bytes, allow_nan) for value in obj]
    return obj


def dumps(obj, ensure_ascii=True, encode_html_chars=False,
          escape_forward_slashes=True, sort_keys=False, indent=0,
          allow_nan=True, reject_bytes=True, default=None, separators=None):
    indent = _operator.index(indent)
    if separators is None:
        separators = (",", ": " if indent else ":")
    elif not isinstance(separators, tuple):
        raise TypeError("expected tuple or None as separator")
    elif len(separators) != 2:
        raise ValueError("expected tuple of size 2 as separator")
    elif not all(isinstance(value, str) for value in separators):
        raise TypeError("separators must be strings")

    def encode_default(value):
        if isinstance(value, _decimal.Decimal):
            return _prepare(float(value), reject_bytes, allow_nan)
        if callable(getattr(value, "toDict", None)):
            return _prepare(value.toDict(), reject_bytes, allow_nan)
        if default is not None:
            return _prepare(default(value), reject_bytes, allow_nan)
        raise TypeError(f"{type(value).__name__} is not JSON serializable")

    result = _json.dumps(
        _prepare(obj, reject_bytes, allow_nan), ensure_ascii=ensure_ascii,
        sort_keys=sort_keys, indent=indent if indent > 0 else None,
        allow_nan=allow_nan, default=encode_default, separators=separators,
    )
    if escape_forward_slashes:
        result = result.replace("/", "\\/")
    if encode_html_chars:
        result = result.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    return result


def dump(obj, fp, /, **kwargs):
    fp.write(dumps(obj, **kwargs))


def loads(data, /):
    return _json.loads(data)


def load(fp, /):
    return loads(fp.read())


encode = dumps
decode = loads
