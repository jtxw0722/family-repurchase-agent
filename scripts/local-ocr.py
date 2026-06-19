#!/usr/bin/env python3
"""Run RapidOCR locally and emit recognized text lines to stdout."""

from __future__ import annotations

import argparse
import contextlib
import sys
from pathlib import Path
from typing import Any, Iterable, List


ALLOWED_IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp"}


def parse_args() -> argparse.Namespace:
    """Parse local OCR command-line arguments."""
    parser = argparse.ArgumentParser(
        description="Recognize text from a local image with RapidOCR."
    )
    parser.add_argument("--image", required=True, help="Path to the local image file.")
    parser.add_argument(
        "--lang",
        default="ch",
        help="Reserved OCR language option; currently defaults to ch.",
    )
    return parser.parse_args()


def validate_image_path(image_path: Path) -> Path:
    """Validate the local image path and supported suffix."""
    if not image_path.exists():
        raise ValueError(f"image file not found: {image_path}")
    if not image_path.is_file():
        raise ValueError(f"image path is not a regular file: {image_path}")
    suffix = image_path.suffix.lower()
    if suffix not in ALLOWED_IMAGE_SUFFIXES:
        raise ValueError(f"unsupported image suffix: {suffix or '<none>'}")
    return image_path.resolve()


def load_ocr_engine() -> Any:
    """Load RapidOCR lazily so validation errors do not require the dependency."""
    try:
        # Third-party initialization output must not pollute the OCR stdout contract.
        with contextlib.redirect_stdout(sys.stderr):
            from rapidocr_onnxruntime import RapidOCR

            return RapidOCR()
    except ImportError as exception:
        raise RuntimeError(
            "rapidocr_onnxruntime is not installed. Please run: "
            "python -m pip install -r requirements-ocr.txt"
        ) from exception


def recognize_text(image_path: Path) -> Any:
    """Run RapidOCR against the validated local image."""
    engine = load_ocr_engine()
    # Move any engine progress or diagnostic prints to stderr.
    with contextlib.redirect_stdout(sys.stderr):
        output = engine(str(image_path))
    if isinstance(output, tuple):
        return output[0]
    return output


def normalize_lines(result: Any) -> List[str]:
    """Extract non-empty text lines from supported RapidOCR result shapes."""
    if result is None:
        return []

    texts = getattr(result, "txts", None)
    if texts is not None:
        return _normalize_text_values(texts)

    if not isinstance(result, (list, tuple)):
        return []

    lines: List[str] = []
    for item in result:
        if not isinstance(item, (list, tuple)) or len(item) < 2:
            continue
        text = item[1]
        if isinstance(text, str) and text.strip():
            lines.append(text.strip())
    return lines


def _normalize_text_values(values: Iterable[Any]) -> List[str]:
    """Normalize a direct iterable of OCR text values."""
    return [value.strip() for value in values if isinstance(value, str) and value.strip()]


def _configure_stream_encoding() -> None:
    """Use UTF-8 for the Java adapter's default process-output charset."""
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")


def main() -> int:
    """Validate input, run local OCR, and honor the stdout/stderr contract."""
    _configure_stream_encoding()
    try:
        args = parse_args()
        if not args.image or not args.image.strip():
            raise ValueError("image path must not be blank")
        image_path = validate_image_path(Path(args.image).expanduser())
        lines = normalize_lines(recognize_text(image_path))
        if not lines:
            raise RuntimeError("OCR returned no text")
        sys.stdout.write("\n".join(lines) + "\n")
        return 0
    except Exception as exception:
        print(f"local-ocr error: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
