#!/usr/bin/env python3
"""Convert the pinned SigLIP2 text tower into a fixed-shape LiteRT model.

This is intentionally a text-only conversion path.  The source repository
contains only the SigLIP2 text tower, so conversion never materializes the
roughly 1.5 GiB combined image-and-text Transformers checkpoint.  We load
each safetensors tensor once, export a FLOAT32 graph, and apply LiteRT's
weight-only INT8 recipe as a separate step:

    INT32 [1, 64] -> FLOAT32 [1, 768]

Token IDs stay INT32 because they are embedding lookup indices.  The Android
side uses the tokenizer.json from the same pinned repository revision.
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import tempfile
from pathlib import Path

import numpy as np
import torch
from safetensors import safe_open
from transformers import SiglipConfig, SiglipTextModel

from ai_edge_litert.interpreter import Interpreter
from ai_edge_quantizer import Quantizer
from ai_edge_quantizer import recipe as quant_recipe
from ai_edge_quantizer.transformations import dequant_insert
import litert_torch


TEXT_LENGTH = 64
EMBEDDING_DIMENSION = 768
TEXT_PREFIX = "text_model."


def install_quantizer_name_compatibility() -> None:
    """Bridge ai-edge-quantizer 0.7 with ai-edge-litert 2.1 tensor names.

    The quantizer's dequant insertion path still assumes FlatBuffer tensor
    names are bytes, while this LiteRT runtime exposes them as Python strings.
    The graph and numerical data are unchanged; only the generated tensor
    name is normalized before the upstream transformation appends its suffix.
    """
    original = dequant_insert.insert_dequant

    def insert_dequant_compat(transformation_input):
        tensor = transformation_input.subgraph.tensors[
            transformation_input.tensor_id
        ]
        if isinstance(tensor.name, str):
            tensor.name = tensor.name.encode()
        return original(transformation_input)

    dequant_insert.insert_dequant = insert_dequant_compat


class TextExport(torch.nn.Module):
    def __init__(self, model: SiglipTextModel):
        super().__init__()
        self.model = model

    def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
        # pooler_output is the projected text embedding used by SigLIP2.  The
        # explicit cast keeps the LiteRT boundary stable for the later
        # weight-only quantization step.
        return self.model(input_ids=input_ids).pooler_output.float()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def materialize_meta_buffers(model: torch.nn.Module) -> None:
    """Materialize non-persistent buffers left on meta by the constructor."""
    for module in model.modules():
        for buffer_name, buffer in module.named_buffers(recurse=False):
            if buffer is None or buffer.device.type != "meta":
                continue
            if buffer_name == "position_ids":
                value = torch.arange(
                    buffer.shape[-1],
                    dtype=buffer.dtype,
                    device="cpu",
                ).expand(buffer.shape).clone()
            else:
                value = torch.zeros(
                    tuple(buffer.shape),
                    dtype=buffer.dtype,
                    device="cpu",
                )
            module._buffers[buffer_name] = value


def load_text_model(config_dir: Path, checkpoint: Path) -> SiglipTextModel:
    config = SiglipConfig.from_pretrained(str(config_dir))
    with torch.device("meta"):
        model = SiglipTextModel(config.text_config)

    # Keep the state dictionary in one dtype from the start.  Converting one
    # source tensor at a time avoids retaining a second full tower copy.
    state: dict[str, torch.Tensor] = {}
    with safe_open(str(checkpoint), framework="pt", device="cpu") as source:
        keys = [key for key in source.keys() if key.startswith(TEXT_PREFIX)]
        if not keys:
            raise RuntimeError(
                f"Checkpoint has no keys with prefix {TEXT_PREFIX!r}"
            )
        for key in keys:
            tensor = source.get_tensor(key)
            if tensor.is_floating_point():
                tensor = tensor.to(dtype=torch.float32)
            state[key[len(TEXT_PREFIX) :]] = tensor

    missing, unexpected = model.load_state_dict(
        state,
        strict=False,
        assign=True,
    )
    del state, config
    materialize_meta_buffers(model)
    if missing or unexpected:
        raise RuntimeError(
            f"Could not load SigLIP2 text tower: "
            f"missing={missing[:8]}, unexpected={unexpected[:8]}"
        )
    model.eval()
    return model


def validate_litert(path: Path) -> dict[str, object]:
    interpreter = Interpreter(model_path=str(path))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    input_shape = input_detail["shape"].tolist()
    output_shape = output_detail["shape"].tolist()
    input_dtype = np.dtype(input_detail["dtype"]).name
    output_dtype = np.dtype(output_detail["dtype"]).name
    if input_shape != [1, TEXT_LENGTH] or input_dtype != "int32":
        raise RuntimeError(
            f"Unexpected text input: shape={input_shape}, dtype={input_dtype}"
        )
    if output_shape != [1, EMBEDDING_DIMENSION] or output_dtype != "float32":
        raise RuntimeError(
            f"Unexpected text output: shape={output_shape}, dtype={output_dtype}"
        )

    sample = np.zeros((1, TEXT_LENGTH), dtype=np.int32)
    interpreter.set_tensor(input_detail["index"], sample)
    interpreter.invoke()
    output = interpreter.get_tensor(output_detail["index"])
    if output.shape != (1, EMBEDDING_DIMENSION) or not np.all(np.isfinite(output)):
        raise RuntimeError("LiteRT text smoke test returned an invalid embedding")
    del interpreter
    gc.collect()
    return {
        "input_shape": input_shape,
        "input_dtype": input_dtype,
        "output_shape": output_shape,
        "output_dtype": output_dtype,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--config-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--source-model", default="m-toman/siglip2-base-patch16-224-text")
    parser.add_argument("--source-revision", required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.checkpoint.is_file():
        raise FileNotFoundError(args.checkpoint)
    args.output.parent.mkdir(parents=True, exist_ok=True)

    torch.set_num_threads(1)
    install_quantizer_name_compatibility()
    print("Loading the SigLIP2 text tower in FLOAT32 on CPU", flush=True)
    model = load_text_model(args.config_dir, args.checkpoint)
    export = TextExport(model).eval()
    sample_args = (torch.zeros((1, TEXT_LENGTH), dtype=torch.int32),)

    with tempfile.TemporaryDirectory(
        prefix="siglip2-text-float-",
        dir=str(args.output.parent),
    ) as temporary_dir:
        float_path = Path(temporary_dir) / "text_float32.tflite"
        print("Converting text tower with LiteRT Torch", flush=True)
        with torch.no_grad():
            edge_model = litert_torch.convert(
                export,
                sample_args,
                strict_export="auto",
                lightweight_conversion=True,
                enable_x64=False,
            )
        edge_model.export(str(float_path))

        # Release the PyTorch graph before opening the large intermediate
        # FlatBuffer and quantizer.  The source tower remains isolated from the
        # combined checkpoint, and the intermediate is deleted on exit.
        del sample_args, edge_model, export, model
        gc.collect()
        float_graph = validate_litert(float_path)

        print("Applying LiteRT weight-only INT8 quantization", flush=True)
        quantizer = Quantizer(float_path, quant_recipe.weight_only_wi8_afp32())
        quantizer.quantize(
            serialize_to_path=str(args.output),
            enable_progress_report=True,
        )
        del quantizer
        gc.collect()

    graph = validate_litert(args.output)
    result = {
        "source_model": args.source_model,
        "source_revision": args.source_revision,
        "checkpoint_sha256": file_sha256(args.checkpoint),
        "output": str(args.output),
        "bytes": args.output.stat().st_size,
        "sha256": file_sha256(args.output),
        "weights": "weight_only_int8",
        "float_intermediate_graph": float_graph,
        "graph": graph,
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
