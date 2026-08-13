#!/usr/bin/env bash
set -euo pipefail

# Download the gated EmbeddingGemma files without storing the HF credential.
# Usage: HF_TOKEN='hf_...' bash tools/download_embeddinggemma.sh

: "${HF_TOKEN:?Set HF_TOKEN in the environment for this one-time download}"

repo_url="https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main"
output_dir="model-artifacts/embeddinggemma"
mkdir -p "$output_dir"

download_one() {
    local filename="$1"
    local expected_bytes="$2"
    local destination="$output_dir/$filename"
    local partial="$destination.part"

    if [[ -f "$destination" ]] && [[ "$(stat -c '%s' "$destination")" == "$expected_bytes" ]]; then
        echo "Already present: $destination"
        return
    fi

    echo "Downloading $filename"
    # Feed the bearer header on stdin so the token is not part of the curl
    # process arguments or written to the repository.
    printf 'Authorization: Bearer %s\n' "$HF_TOKEN" |
        curl --fail --location --retry 3 --retry-delay 2 --retry-all-errors \
            --proto '=https' --tlsv1.2 -H @- \
            -o "$partial" "$repo_url/$filename?download=true"

    actual_bytes="$(stat -c '%s' "$partial")"
    if [[ "$actual_bytes" != "$expected_bytes" ]]; then
        echo "Unexpected size for $filename: got $actual_bytes, expected $expected_bytes" >&2
        rm -f "$partial"
        return 1
    fi
    mv -f "$partial" "$destination"
    sha256sum "$destination"
}

download_one "embeddinggemma-300M_seq512_mixed-precision.tflite" 179132472
download_one "sentencepiece.model" 4683319

echo "EmbeddingGemma assets are ready under $output_dir."
echo "Build with: gradle :app:assembleDebug"
