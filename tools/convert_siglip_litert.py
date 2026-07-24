#!/usr/bin/env python3
"""Convert the agreed SigLIP SO400M checkpoint into Android LiteRT files.

The Android app intentionally consumes two fixed-shape exports rather than a
Transformers checkpoint:

  vision_int8.tflite: INT8   [1, 384, 384, 3] -> INT8 [1, 1152]
  text_int8.tflite:   INT32  [1, 64]          -> INT8 [1, 1152]

The checkpoint contains both towers in one safetensors file. This script loads
one tower at a time, exports it with LiteRT Torch, statically quantizes the
export with AI Edge Quantizer, and validates that no floating-point tensors
remain. Token IDs intentionally remain INT32 because embedding lookup indices
are integer indices, not numerical activations. It never handles HF tokens;
authenticate the separate checkpoint download through the shell environment.
"""

from __future__ import annotations

import argparse
import gc
import json
import hashlib
import subprocess
import sys
from collections import Counter
from pathlib import Path
import tempfile

import numpy as np
import torch
from safetensors import safe_open
from transformers import SiglipConfig, SiglipTextModel, SiglipVisionModel

from ai_edge_litert.interpreter import Interpreter
from ai_edge_quantizer import Quantizer
from ai_edge_quantizer import recipe as quant_recipe
import litert_torch

IMAGE_SIZE = 384
TEXT_LENGTH = 64


class VisionExport(torch.nn.Module):
    def __init__(self, model: SiglipVisionModel):
        super().__init__()
        self.model = model

    def forward(self, pixels_nhwc: torch.Tensor) -> torch.Tensor:
        pixels_nchw = pixels_nhwc.permute(0, 3, 1, 2).to(
            dtype=self.model.embeddings.patch_embedding.weight.dtype
        )
        # This is the FLOAT32 source graph; AI Edge Quantizer changes the
        # floating activation/weight tensors to INT8 after export.
        pooled = self.model(pixel_values=pixels_nchw).pooler_output
        return pooled.float()


class TextExport(torch.nn.Module):
    def __init__(self, model: SiglipTextModel):
        super().__init__()
        self.model = model

    def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
        return self.model(input_ids=input_ids).pooler_output.float()


def load_tower(model: torch.nn.Module, checkpoint: Path, prefix: str) -> None:
    """Load one tower without retaining a randomly initialized copy.

    The model is constructed on ``meta`` by the caller.  ``assign=True`` then
    attaches the checkpoint tensors directly to the module instead of copying
    them into an already allocated parameter set.  This matters here because
    one SigLIP tower is already roughly 1.7 GiB in FLOAT32.
    """
    state: dict[str, torch.Tensor] = {}
    with safe_open(str(checkpoint), framework="pt", device="cpu") as source:
        keys = list(source.keys())
        matching = [key for key in keys if key.startswith(prefix)]
        if not matching:
            raise RuntimeError(
                f"Checkpoint has no keys with prefix {prefix!r}; "
                f"first keys: {keys[:5]}"
            )
        for key in matching:
            tensor = source.get_tensor(key)
            # The source checkpoint is normally FLOAT32.  Convert one tensor
            # at a time if a caller supplies a lower-precision checkpoint, so
            # we never make a second full-tower copy just to change dtype.
            if tensor.is_floating_point() and tensor.dtype != torch.float32:
                tensor = tensor.float()
            state[key[len(prefix) :]] = tensor

    missing, unexpected = model.load_state_dict(
        state,
        strict=False,
        assign=True,
    )
    del state
    # Transformers registers position_ids as a non-persistent buffer, so it
    # is intentionally absent from the safetensors state dict.  A module
    # created on meta leaves that tiny buffer on meta even after all weights
    # are assigned; materialize it explicitly for torch.export.
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
    if missing or unexpected:
        raise RuntimeError(
            f"Could not load {prefix}: missing={missing[:8]}, "
            f"unexpected={unexpected[:8]}"
        )


def convert_one(
    name: str,
    model: torch.nn.Module,
    sample_args: tuple[torch.Tensor, ...],
    output: Path,
) -> dict[str, object]:
    model.eval()
    # Static INT8 calibration starts from a FLOAT32 graph.  The loader already
    # converts checkpoint tensors one at a time, avoiding model.to(float32),
    # which would temporarily retain both the old and new full towers.
    non_float32 = {
        str(tensor.dtype)
        for tensor in model.state_dict().values()
        if tensor.is_floating_point() and tensor.dtype != torch.float32
    }
    if non_float32:
        raise RuntimeError(f"{name} has non-FLOAT32 tensors: {sorted(non_float32)}")

    with torch.no_grad():
        edge_model = litert_torch.convert(
            model,
            sample_args,
            strict_export="auto",
            lightweight_conversion=True,
            enable_x64=False,
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    edge_model.export(str(output))

    # LiteRT Torch returns NumPy arrays from its callable model. This catches
    # an accidental NCHW or integer output before the file is shipped.
    with torch.no_grad():
        edge_output = edge_model(*sample_args)
    if isinstance(edge_output, (tuple, list)):
        if len(edge_output) != 1:
            raise RuntimeError(f"{name} exported {len(edge_output)} outputs")
        edge_output = edge_output[0]
    if tuple(edge_output.shape) != (1, 1152):
        raise RuntimeError(f"{name} output shape is {edge_output.shape}, expected (1, 1152)")
    if edge_output.dtype not in (np.float16, np.float32):
        raise RuntimeError(f"{name} output dtype is {edge_output.dtype}, expected float")
    edge_output_shape = list(edge_output.shape)
    edge_output_dtype = str(edge_output.dtype)

    # Do not let the converted graph remain live while the quantizer reads the
    # multi-gigabyte FLOAT32 FlatBuffer.  The caller also drops the PyTorch
    # tower before entering quantize_int8().
    del edge_output, edge_model
    gc.collect()

    return {
        "name": name,
        "path": str(output),
        "bytes": output.stat().st_size,
        "output_shape": edge_output_shape,
        "output_dtype": edge_output_dtype,
    }


def calibration_samples(name: str, vocab_size: int) -> list[np.ndarray]:
    """Build deterministic representative inputs for static INT8 ranges."""
    rng = np.random.default_rng(20260718)
    if name == "vision":
        gradient = np.linspace(
            -1.0, 1.0, IMAGE_SIZE * IMAGE_SIZE * 3, dtype=np.float32
        ).reshape(1, IMAGE_SIZE, IMAGE_SIZE, 3)
        checker = np.indices((IMAGE_SIZE, IMAGE_SIZE)).sum(axis=0) % 2
        checker = (checker.astype(np.float32) * 2.0 - 1.0)[None, :, :, None]
        checker = np.repeat(checker, 3, axis=3)
        samples = [
            np.zeros((1, IMAGE_SIZE, IMAGE_SIZE, 3), dtype=np.float32),
            np.full((1, IMAGE_SIZE, IMAGE_SIZE, 3), -1.0, dtype=np.float32),
            np.full((1, IMAGE_SIZE, IMAGE_SIZE, 3), 1.0, dtype=np.float32),
            gradient,
            checker,
        ]
        samples.extend(
            rng.uniform(
                -1.0, 1.0, size=(1, IMAGE_SIZE, IMAGE_SIZE, 3)
            ).astype(np.float32)
            for _ in range(3)
        )
        return samples

    if name == "text":
        ids = np.arange(TEXT_LENGTH, dtype=np.int32)[None, :]
        samples = [
            np.zeros((1, TEXT_LENGTH), dtype=np.int32),
            np.full((1, TEXT_LENGTH), 1, dtype=np.int32),
            ids % max(vocab_size, 1),
        ]
        samples.extend(
            rng.integers(
                0, max(vocab_size, 1), size=(1, TEXT_LENGTH), dtype=np.int32
            )
            for _ in range(5)
        )
        return samples

    raise ValueError(f"Unknown calibration sample kind: {name}")


def quantize_int8(
    name: str,
    float_model: Path,
    output: Path,
    samples: list[np.ndarray],
    expected_input_dtype: np.dtype,
) -> dict[str, object]:
    """Run static weight-and-activation INT8 quantization and validate it."""
    # Read only the signature metadata here.  Allocating the FLOAT32 model
    # before constructing Quantizer creates a second large weight arena and
    # was the main source of the host OOM.
    interpreter = Interpreter(model_path=str(float_model))
    signatures = interpreter.get_signature_list()
    if len(signatures) != 1:
        raise RuntimeError(f"{name} has unexpected signatures: {signatures}")
    signature_name, signature = next(iter(signatures.items()))
    input_name = signature["inputs"][0]
    float_input = interpreter.get_input_details()[0]
    if np.dtype(float_input["dtype"]) != expected_input_dtype:
        raise RuntimeError(
            f"{name} float export input is {float_input['dtype']}, "
            f"expected {expected_input_dtype}"
        )
    del interpreter
    gc.collect()

    quantizer = Quantizer(str(float_model), quant_recipe.static_wi8_ai8())
    calibration = None
    try:
        calibration = quantizer.calibrate(
            {
                signature_name: [{input_name: sample} for sample in samples],
            },
            # Calibration preserves intermediate tensors.  One thread keeps
            # its temporary arena and native bookkeeping bounded on this host.
            num_threads=1,
        )
        if not calibration:
            raise RuntimeError(f"{name} produced no calibration statistics")
        quantizer.quantize(
            calibration,
            serialize_to_path=str(output),
            enable_progress_report=False,
        )
    finally:
        # Quantizer owns the memory-mapped FLOAT32 graph and its quantized
        # serialization.  Release both before opening the final validator.
        del calibration, quantizer
        gc.collect()

    quantized = Interpreter(model_path=str(output))
    quantized.allocate_tensors()
    tensors = quantized.get_tensor_details()
    dtype_counts = Counter(np.dtype(detail["dtype"]).name for detail in tensors)
    floating = [
        detail["name"]
        for detail in tensors
        if np.dtype(detail["dtype"]) in (np.dtype(np.float16), np.dtype(np.float32))
    ]
    if floating:
        preview = ", ".join(floating[:8])
        raise RuntimeError(
            f"{name} INT8 export still contains {len(floating)} floating "
            f"tensors: {preview}"
        )

    input_detail = quantized.get_input_details()[0]
    output_detail = quantized.get_output_details()[0]
    if np.dtype(input_detail["dtype"]) != expected_input_dtype:
        raise RuntimeError(
            f"{name} quantized input is {input_detail['dtype']}, "
            f"expected {expected_input_dtype}"
        )
    if np.dtype(output_detail["dtype"]) != np.dtype(np.int8):
        raise RuntimeError(
            f"{name} quantized output is {output_detail['dtype']}, expected int8"
        )
    for detail in (input_detail, output_detail):
        scales = detail["quantization_parameters"]["scales"]
        if len(scales) == 0 or not np.all(np.isfinite(scales)) or np.any(scales <= 0):
            raise RuntimeError(
                f"{name} tensor {detail['name']} has invalid INT8 scales"
            )

    return {
        "name": name,
        "path": str(output),
        "bytes": output.stat().st_size,
        "sha256": file_sha256(output),
        "input_shape": input_detail["shape"].tolist(),
        "input_dtype": np.dtype(input_detail["dtype"]).name,
        "output_shape": output_detail["shape"].tolist(),
        "output_dtype": np.dtype(output_detail["dtype"]).name,
        "tensor_dtype_counts": dict(dtype_counts),
        "calibration_samples": len(samples),
        "quantization": "static_wi8_ai8",
    }


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--config-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--tower",
        choices=("all", "vision", "text"),
        default="all",
        help="Convert one tower in isolation, or both through isolated child processes.",
    )
    parser.add_argument(
        "--manifest-path",
        type=Path,
        help=argparse.SUPPRESS,
    )
    return parser.parse_args()


def manifest_payload(results: list[dict[str, object]]) -> dict[str, object]:
    return {
        "base_model": "google/siglip-so400m-patch14-384",
        "revision": "9fdffc58afc957d1a03a25b10dba0329ab15c2a3",
        "quantization": "static_wi8_ai8",
        "floating_point_tensors_allowed": False,
        "text_input_exception": "INT32 token IDs are required for embedding lookup",
        "artifacts": results,
    }


def write_manifest(path: Path, results: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest_payload(results), indent=2) + "\n")


def construct_and_load_tower(
    name: str,
    config: SiglipConfig,
    checkpoint: Path,
) -> tuple[torch.nn.Module, torch.nn.Module, tuple[torch.Tensor, ...]]:
    """Construct one real tower only after its module graph is on meta."""
    if name == "vision":
        with torch.device("meta"):
            model = SiglipVisionModel(config.vision_config)
        load_tower(model, checkpoint, "vision_model.")
        export = VisionExport(model).eval()
        sample_args = (
            torch.zeros((1, IMAGE_SIZE, IMAGE_SIZE, 3), dtype=torch.float32),
        )
        return model, export, sample_args
    if name == "text":
        with torch.device("meta"):
            model = SiglipTextModel(config.text_config)
        load_tower(model, checkpoint, "text_model.")
        export = TextExport(model).eval()
        # Token IDs remain INT32 because embedding lookup indices are not
        # quantized activations.
        sample_args = (torch.zeros((1, TEXT_LENGTH), dtype=torch.int32),)
        return model, export, sample_args
    raise ValueError(f"Unknown tower: {name}")


def convert_tower(args: argparse.Namespace, name: str) -> dict[str, object]:
    config = SiglipConfig.from_pretrained(str(args.config_dir))
    vocab_size = int(config.text_config.vocab_size)
    output = args.output_dir / f"{name}_int8.tflite"

    # Keep the intermediate FLOAT32 FlatBuffer outside the asset directory;
    # only the validated INT8 output survives the conversion.
    with tempfile.TemporaryDirectory(
        prefix=f"siglip-{name}-float-", dir=str(args.output_dir.parent)
    ) as work_dir:
        work = Path(work_dir)
        print(f"Loading SigLIP {name} tower", flush=True)
        model, export, sample_args = construct_and_load_tower(
            name,
            config,
            args.checkpoint,
        )
        float_model = work / f"{name}_float.tflite"
        convert_one(name, export, sample_args, float_model)

        # This point must precede Quantizer construction.  Keeping the
        # PyTorch tower alive while the 3+ GiB FLOAT32 graph is calibrated
        # defeats the memory reduction from the meta-device load.
        del sample_args, export, model, config
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

        return quantize_int8(
            name,
            float_model,
            output,
            calibration_samples(name, vocab_size),
            np.dtype(np.int32) if name == "text" else np.dtype(np.float32),
        )


def main() -> None:
    args = parse_args()
    if not args.checkpoint.is_file():
        raise FileNotFoundError(args.checkpoint)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    if args.tower == "all":
        # Keep the two large conversion lifetimes in separate OS processes.
        # Native Torch/LiteRT allocators can retain arenas after gc.collect(),
        # so an in-process vision->text loop is not a reliable memory bound.
        with tempfile.TemporaryDirectory(
            prefix="siglip-manifests-", dir=str(args.output_dir.parent)
        ) as manifest_dir:
            manifest_root = Path(manifest_dir)
            for tower in ("vision", "text"):
                command = [
                    sys.executable,
                    str(Path(__file__).resolve()),
                    "--checkpoint",
                    str(args.checkpoint),
                    "--config-dir",
                    str(args.config_dir),
                    "--output-dir",
                    str(args.output_dir),
                    "--tower",
                    tower,
                    "--manifest-path",
                    str(manifest_root / f"{tower}.json"),
                ]
                subprocess.run(command, check=True)
            results = [
                json.loads((manifest_root / f"{tower}.json").read_text())["artifacts"][0]
                for tower in ("vision", "text")
            ]
        write_manifest(args.output_dir / "conversion_manifest.json", results)
        print(json.dumps(results, indent=2), flush=True)
        return

    result = convert_tower(args, args.tower)
    write_manifest(
        args.manifest_path or (args.output_dir / "conversion_manifest.json"),
        [result],
    )
    print(json.dumps([result], indent=2), flush=True)


if __name__ == "__main__":
    main()
