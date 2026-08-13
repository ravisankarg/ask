#!/usr/bin/env python3
"""Small, non-destructive ONNX compatibility rewrites for the Schemer POC."""

from __future__ import annotations

import argparse
from pathlib import Path

import onnx
from onnx import numpy_helper
import numpy as np


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    model = onnx.load(str(args.input), load_external_data=True)
    for node in model.graph.node:
        # onnx2tf 1.29.24 fails while building its temporary Keras model for
        # these fixed broadcast patterns.  The affected Schemer graphs use
        # only constant-shaped broadcasts, so Tile is mathematically
        # equivalent and converts cleanly.
        if node.op_type == "Expand":
            source_name = node.input[0]
            if node.name == "/bool_head/Expand":
                repeats = [2, 1]
            elif node.name.endswith("/cross_attn/Expand"):
                repeats = [1, 8, 1, 1]
            else:
                continue
            repeats_name = node.name + "_broadcast"
            shape = [2, 1] if node.name == "/bool_head/Expand" else [1, 8, 1, 1]
            model.graph.initializer.append(
                numpy_helper.from_array(np.ones(shape, dtype=np.float32), repeats_name)
            )
            node.op_type = "Mul"
            del node.input[:]
            node.input.extend([source_name, repeats_name])

    onnx.checker.check_model(model)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(args.output))


if __name__ == "__main__":
    main()
