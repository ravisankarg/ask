#!/usr/bin/env python3
"""Expand Schemer's ONNX MatMulNBits weights to ordinary float MatMul nodes."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("input", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()

    model = onnx.load(str(args.input), load_external_data=True)
    initializers = {x.name: x for x in model.graph.initializer}
    replaced = 0
    for node in model.graph.node:
        if node.op_type != "MatMulNBits":
            continue
        bits = next((a.i for a in node.attribute if a.name == "bits"), 4)
        block = next((a.i for a in node.attribute if a.name == "block_size"), 64)
        if bits != 4:
            raise ValueError(f"unsupported bits={bits} in {node.name}")
        a_name = node.input[0]
        packed = numpy_helper.to_array(initializers[node.input[1]])
        scales = numpy_helper.to_array(initializers[node.input[2]])
        n, blocks, blob = packed.shape
        if blob * 2 != block:
            raise ValueError(f"unexpected packed shape {packed.shape} for block={block}")
        q = np.empty((n, blocks, blob * 2), dtype=np.float32)
        q[..., 0::2] = (packed & 0x0F).astype(np.float32)
        q[..., 1::2] = (packed >> 4).astype(np.float32)
        # MatMulNBits uses a default packed zero point of 0x88 when the
        # optional zero-point input is absent.
        weights = ((q - 8.0) * scales[..., None]).reshape(n, blocks * blob * 2)
        weight_name = node.name + "_dequant_weight"
        model.graph.initializer.append(
            numpy_helper.from_array(weights.T.astype(np.float32), weight_name)
        )
        node.op_type = "MatMul"
        del node.attribute[:]
        del node.input[:]
        node.input.extend([a_name, weight_name])
        replaced += 1
    onnx.checker.check_model(model)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(args.output))
    print(f"replaced {replaced} MatMulNBits nodes")


if __name__ == "__main__":
    main()
