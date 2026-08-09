use jni::objects::{JClass, JFloatArray, JLongArray, JObject, JString, JValue};
use jni::sys::{
    jboolean, jint, jintArray, jlong, jlongArray, jobject, JNI_FALSE, JNI_TRUE,
};
use jni::JNIEnv;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::Mutex;
use tokenizers::utils::padding::{PaddingDirection, PaddingParams, PaddingStrategy};
use tokenizers::utils::truncation::{
    TruncationDirection, TruncationParams, TruncationStrategy,
};
use tokenizers::Tokenizer;
use sentencepiece_rs::SentencePieceProcessor;
use turbovec::{codebook, IdMapIndex};

struct NativeIndex {
    path: PathBuf,
    index: Mutex<IdMapIndex>,
    // The diversity path reads the compact codes already on disk. It never
    // opens SigLIP's image tower or decodes gallery bitmaps at query time.
    quantized: Mutex<QuantizedVectors>,
}

struct NativeTokenizer {
    tokenizer: Tokenizer,
}

struct NativeSentencePiece {
    processor: SentencePieceProcessor,
}

/// Minimal read-only view of the persisted TurboQuant payload used for
/// representative selection. IdMapIndex intentionally keeps its storage
/// private, so this small parser mirrors the stable TVIM wire format without
/// duplicating the full index or changing the existing retrieval path.
struct QuantizedVectors {
    bit_width: usize,
    dim: usize,
    packed_codes: Vec<u8>,
    tqplus_shift: Vec<f32>,
    tqplus_scale: Vec<f32>,
    slot_to_id: Vec<u64>,
    id_to_slot: HashMap<u64, usize>,
    centroids: Vec<f32>,
}

impl QuantizedVectors {
    fn empty(bit_width: usize, dim: usize) -> Self {
        Self {
            bit_width,
            dim,
            packed_codes: Vec::new(),
            tqplus_shift: Vec::new(),
            tqplus_scale: Vec::new(),
            slot_to_id: Vec::new(),
            id_to_slot: HashMap::new(),
            centroids: Vec::new(),
        }
    }

    fn load(path: &Path) -> Result<Self, String> {
        let bytes = fs::read(path).map_err(|error| format!("Could not read TVIM: {error}"))?;
        let mut offset = 0usize;
        let magic = take(&bytes, &mut offset, 4)?;
        if magic != b"TVIM" {
            return Err("Not a TVIM file".to_string());
        }
        let version = take(&bytes, &mut offset, 1)?[0];
        if version != 2 && version != 3 {
            return Err(format!("Unsupported TVIM version {version}"));
        }
        let bit_width = take(&bytes, &mut offset, 1)?[0] as usize;
        let dim = read_u32(&bytes, &mut offset)? as usize;
        let count = read_u32(&bytes, &mut offset)? as usize;
        if !(2..=4).contains(&bit_width) || dim == 0 || dim % 8 != 0 {
            return Err("Invalid TVIM schema".to_string());
        }
        let packed_len = count
            .checked_mul(dim)
            .and_then(|value| value.checked_mul(bit_width))
            .and_then(|value| value.checked_div(8))
            .ok_or_else(|| "TVIM packed-code size overflow".to_string())?;
        let packed_codes = take(&bytes, &mut offset, packed_len)?.to_vec();
        let scales_len = count
            .checked_mul(4)
            .ok_or_else(|| "TVIM scale size overflow".to_string())?;
        let _ = take(&bytes, &mut offset, scales_len)?;

        let (tqplus_shift, tqplus_scale) = if version == 3 {
            let calibration_count = read_u32(&bytes, &mut offset)? as usize;
            if calibration_count != 0 && calibration_count != dim {
                return Err("Invalid TVIM calibration length".to_string());
            }
            (
                read_f32s(&bytes, &mut offset, calibration_count)?,
                read_f32s(&bytes, &mut offset, calibration_count)?,
            )
        } else {
            (Vec::new(), Vec::new())
        };
        let mut slot_to_id = Vec::with_capacity(count);
        for _ in 0..count {
            let raw = take(&bytes, &mut offset, 8)?;
            slot_to_id.push(u64::from_le_bytes([
                raw[0], raw[1], raw[2], raw[3],
                raw[4], raw[5], raw[6], raw[7],
            ]));
        }
        let id_to_slot = slot_to_id
            .iter()
            .enumerate()
            .map(|(slot, id)| (*id, slot))
            .collect::<HashMap<_, _>>();
        if id_to_slot.len() != slot_to_id.len() {
            return Err("TVIM contains duplicate ids".to_string());
        }
        let (_, centroids) = codebook::codebook(bit_width, dim);
        let (tqplus_shift, tqplus_scale) = if tqplus_shift.is_empty() {
            (vec![0.0; dim], vec![1.0; dim])
        } else {
            (tqplus_shift, tqplus_scale)
        };
        Ok(Self {
            bit_width,
            dim,
            packed_codes,
            tqplus_shift,
            tqplus_scale,
            slot_to_id,
            id_to_slot,
            centroids,
        })
    }

    /// Reconstruct one normalized vector in the rotated coordinate space.
    /// TurboQuant's rotation is orthogonal, so cosine similarity is identical
    /// before and after rotation; no 768x768 inverse matrix multiply is needed.
    fn reconstruct(&self, slot: usize) -> Option<Vec<f32>> {
        if slot >= self.slot_to_id.len() || self.centroids.is_empty() {
            return None;
        }
        let bytes_per_plane = self.dim / 8;
        let bytes_per_row = self.bit_width * bytes_per_plane;
        let row = self
            .packed_codes
            .get(slot.checked_mul(bytes_per_row)?..(slot + 1).checked_mul(bytes_per_row)?)?;
        let mut vector = vec![0.0f32; self.dim];
        for d in 0..self.dim {
            let byte_in_plane = d / 8;
            let bit = 7 - (d % 8);
            let mut code = 0usize;
            for plane in 0..self.bit_width {
                if row[plane * bytes_per_plane + byte_in_plane] & (1 << bit) != 0 {
                    code |= 1 << plane;
                }
            }
            vector[d] = self.centroids[code] / self.tqplus_scale[d] - self.tqplus_shift[d];
        }
        let norm = vector
            .iter()
            .map(|value| (*value as f64) * (*value as f64))
            .sum::<f64>()
            .sqrt() as f32;
        if !norm.is_finite() || norm <= 1.0e-8 {
            return None;
        }
        vector.iter_mut().for_each(|value| *value /= norm);
        Some(vector)
    }

    fn select_diverse(&self, candidate_ids: &[u64], max_count: usize) -> Vec<u64> {
        if max_count == 0 || candidate_ids.is_empty() {
            return Vec::new();
        }
        if candidate_ids.len() <= max_count {
            return candidate_ids.to_vec();
        }
        let candidates = candidate_ids
            .iter()
            .enumerate()
            .filter_map(|(rank, id)| {
                let slot = *self.id_to_slot.get(id)?;
                Some((rank, *id, self.reconstruct(slot)?))
            })
            .collect::<Vec<_>>();
        if candidates.is_empty() {
            return candidate_ids.iter().take(max_count).copied().collect();
        }
        if candidates.len() <= max_count {
            let mut selected = candidates.iter().map(|(_, id, _)| *id).collect::<Vec<_>>();
            for id in candidate_ids {
                if selected.len() >= max_count || selected.contains(id) {
                    continue;
                }
                selected.push(*id);
            }
            return selected;
        }

        let mut remaining = (1..candidates.len()).collect::<Vec<_>>();
        let mut selected = vec![0usize];
        while selected.len() < max_count && !remaining.is_empty() {
            let mut distinct = remaining
                .iter()
                .copied()
                .filter(|index| {
                    selected.iter().all(|chosen| {
                        cosine(&candidates[*index].2, &candidates[*chosen].2)
                            < SAME_GROUP_SIMILARITY
                    })
                })
                .collect::<Vec<_>>();
            if distinct.is_empty() {
                distinct = remaining.clone();
            }
            let next = distinct
                .into_iter()
                .max_by(|left, right| {
                    let left_score = diversity_score(*left, &selected, &candidates);
                    let right_score = diversity_score(*right, &selected, &candidates);
                    left_score
                        .partial_cmp(&right_score)
                        .unwrap_or(std::cmp::Ordering::Equal)
                        .then_with(|| candidates[*right].0.cmp(&candidates[*left].0))
                })
                .expect("diversity pool is not empty");
            selected.push(next);
            remaining.retain(|index| *index != next);
        }
        let mut output = selected
            .into_iter()
            .map(|index| candidates[index].1)
            .collect::<Vec<_>>();
        for id in candidate_ids {
            if output.len() >= max_count || output.contains(id) {
                continue;
            }
            output.push(*id);
        }
        output
    }
}

fn take<'a>(bytes: &'a [u8], offset: &mut usize, count: usize) -> Result<&'a [u8], String> {
    let end = offset
        .checked_add(count)
        .ok_or_else(|| "TVIM offset overflow".to_string())?;
    if end > bytes.len() {
        return Err("Truncated TVIM file".to_string());
    }
    let result = &bytes[*offset..end];
    *offset = end;
    Ok(result)
}

fn read_u32(bytes: &[u8], offset: &mut usize) -> Result<u32, String> {
    let raw = take(bytes, offset, 4)?;
    Ok(u32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]))
}

fn read_f32s(bytes: &[u8], offset: &mut usize, count: usize) -> Result<Vec<f32>, String> {
    (0..count)
        .map(|_| {
            let raw = take(bytes, offset, 4)?;
            Ok(f32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]))
        })
        .collect()
}

fn cosine(left: &[f32], right: &[f32]) -> f32 {
    left.iter()
        .zip(right)
        .map(|(a, b)| a * b)
        .sum::<f32>()
        .clamp(-1.0, 1.0)
}

fn diversity_score(
    candidate: usize,
    selected: &[usize],
    candidates: &[(usize, u64, Vec<f32>)],
) -> f32 {
    let nearest_distance = selected
        .iter()
        .map(|chosen| 1.0 - cosine(&candidates[candidate].2, &candidates[*chosen].2))
        .fold(f32::INFINITY, f32::min);
    // Retrieval order is a relevance signal, not just a tie-breaker. The old
    // reciprocal-rank bonus was too small for a low-ranked visual outlier to
    // lose against a merely different but relevant image. Normalize rank over
    // this bounded candidate window so diversity remains useful without
    // replacing relevance.
    let relevance = 1.0
        - candidates[candidate].0 as f32 / candidates.len().max(1) as f32;
    nearest_distance * DIVERSITY_WEIGHT + relevance * RANK_WEIGHT
}

const SAME_GROUP_SIMILARITY: f32 = 0.90;
const DIVERSITY_WEIGHT: f32 = 0.65;
const RANK_WEIGHT: f32 = 0.35;

fn throw(env: &mut JNIEnv<'_>, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/IllegalStateException", message.as_ref());
}

fn error_object(env: &mut JNIEnv<'_>, message: impl AsRef<str>) -> jobject {
    throw(env, message);
    ptr::null_mut()
}

unsafe fn index_ref<'a>(handle: jlong) -> Result<&'a NativeIndex, String> {
    if handle == 0 {
        return Err("Native index handle is null".to_string());
    }
    Ok(&*(handle as *const NativeIndex))
}

unsafe fn tokenizer_ref<'a>(handle: jlong) -> Result<&'a NativeTokenizer, String> {
    if handle == 0 {
        return Err("Native tokenizer handle is null".to_string());
    }
    Ok(&*(handle as *const NativeTokenizer))
}

fn read_floats(env: &mut JNIEnv<'_>, values: &JFloatArray) -> Result<Vec<f32>, String> {
    let length = env
        .get_array_length(values)
        .map_err(|error| format!("Could not read float array length: {error}"))?;
    let mut output = vec![0.0_f32; length as usize];
    env.get_float_array_region(values, 0, &mut output)
        .map_err(|error| format!("Could not read float array: {error}"))?;
    Ok(output)
}

fn read_longs(env: &mut JNIEnv<'_>, values: &JLongArray) -> Result<Vec<u64>, String> {
    let length = env
        .get_array_length(values)
        .map_err(|error| format!("Could not read long array length: {error}"))?;
    let mut raw = vec![0_i64; length as usize];
    env.get_long_array_region(values, 0, &mut raw)
        .map_err(|error| format!("Could not read long array: {error}"))?;
    raw.into_iter()
        .map(|id| {
            u64::try_from(id).map_err(|_| format!("Vector id must be non-negative: {id}"))
        })
        .collect()
}

fn persist(index: &NativeIndex, value: &IdMapIndex) -> Result<(), String> {
    let parent = index
        .path
        .parent()
        .ok_or_else(|| "Index path has no parent".to_string())?;
    fs::create_dir_all(parent).map_err(|error| format!("Could not create index directory: {error}"))?;
    let temporary = index.path.with_extension("tvim.tmp");
    value
        .write(&temporary)
        .map_err(|error| format!("Could not write TurboQuant index: {error}"))?;
    fs::rename(&temporary, &index.path)
        .map_err(|error| format!("Could not atomically replace TurboQuant index: {error}"))
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeTokenizer_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let path_text: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(error) => {
            return error_object(&mut env, format!("Could not read tokenizer path: {error}"))
                as jlong
        }
    };
    let mut tokenizer = match Tokenizer::from_file(&path_text) {
        Ok(value) => value,
        Err(error) => {
            return error_object(
                &mut env,
                format!("Could not load SigLIP2 tokenizer: {error}"),
            ) as jlong
        }
    };
    if let Err(error) = tokenizer.with_truncation(Some(TruncationParams {
        direction: TruncationDirection::Right,
        max_length: 64,
        strategy: TruncationStrategy::OnlyFirst,
        stride: 0,
    })) {
        return error_object(
            &mut env,
            format!("Could not configure SigLIP2 tokenizer truncation: {error}"),
        ) as jlong;
    }
    tokenizer.with_padding(Some(PaddingParams {
        strategy: PaddingStrategy::Fixed(64),
        direction: PaddingDirection::Right,
        pad_to_multiple_of: None,
        pad_id: 0,
        pad_type_id: 0,
        pad_token: "<pad>".to_string(),
    }));
    Box::into_raw(Box::new(NativeTokenizer { tokenizer })) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeTokenizer_nativeEncode(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
) -> jintArray {
    let native = match unsafe { tokenizer_ref(handle) } {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, error) as jintArray,
    };
    let text: String = match env.get_string(&text) {
        Ok(value) => value.into(),
        Err(error) => {
            return error_object(&mut env, format!("Could not read query text: {error}"))
                as jintArray
        }
    };
    let encoding = match native.tokenizer.encode(text.as_str(), true) {
        Ok(value) => value,
        Err(error) => {
            return error_object(
                &mut env,
                format!("Could not tokenize SigLIP2 query: {error}"),
            ) as jintArray
        }
    };
    const TEXT_LENGTH: usize = 64;
    let mut ids = vec![0_i32; TEXT_LENGTH];
    for (index, id) in encoding.get_ids().iter().take(TEXT_LENGTH).enumerate() {
        ids[index] = match i32::try_from(*id) {
            Ok(value) => value,
            Err(_) => {
                return error_object(
                    &mut env,
                    format!("Tokenizer ID does not fit INT32: {id}"),
                ) as jintArray
            }
        };
    }
    let output = match env.new_int_array(TEXT_LENGTH as jint) {
        Ok(value) => value,
        Err(error) => {
            return error_object(&mut env, format!("Could not allocate token IDs: {error}"))
                as jintArray
        }
    };
    if let Err(error) = env.set_int_array_region(&output, 0, &ids) {
        return error_object(&mut env, format!("Could not write token IDs: {error}")) as jintArray;
    }
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeTokenizer_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut NativeTokenizer) });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeSentencePiece_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let path_text: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(error) => return error_object(&mut env, format!("Could not read tokenizer path: {error}")) as jlong,
    };
    let mut processor = match SentencePieceProcessor::open(&path_text) {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, format!("Could not load SentencePiece model: {error}")) as jlong,
    };
    // Gemma tokenization adds BOS but not EOS. The LiteRT graph uses 0 as PAD.
    if let Err(error) = processor.set_encode_extra_options("bos") {
        return error_object(&mut env, format!("Could not configure Gemma tokenizer: {error}")) as jlong;
    }
    Box::into_raw(Box::new(NativeSentencePiece { processor })) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeSentencePiece_nativeEncode(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
    max_length: jint,
) -> jintArray {
    if handle == 0 || max_length <= 0 {
        return error_object(&mut env, "Invalid SentencePiece handle or length".to_string()) as jintArray;
    }
    let native = unsafe { &*(handle as *const NativeSentencePiece) };
    let text: String = match env.get_string(&text) {
        Ok(value) => value.into(),
        Err(error) => return error_object(&mut env, format!("Could not read text: {error}")) as jintArray,
    };
    let ids = match native.processor.encode_to_ids(&text) {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, format!("Could not tokenize EmbeddingGemma input: {error}")) as jintArray,
    };
    let length = max_length as usize;
    let mut output_ids = vec![0_i32; length];
    for (index, id) in ids.iter().take(length).enumerate() {
        output_ids[index] = match i32::try_from(*id) {
            Ok(value) => value,
            Err(_) => return error_object(&mut env, format!("SentencePiece ID does not fit INT32: {id}")) as jintArray,
        };
    }
    let output = match env.new_int_array(max_length) {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, format!("Could not allocate token IDs: {error}")) as jintArray,
    };
    if let Err(error) = env.set_int_array_region(&output, 0, &output_ids) {
        return error_object(&mut env, format!("Could not write token IDs: {error}")) as jintArray;
    }
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeSentencePiece_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut NativeSentencePiece) });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dimension: jint,
    bit_width: jint,
) -> jlong {
    let path_text: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(error) => return error_object(&mut env, format!("Could not read index path: {error}")) as jlong,
    };
    if dimension <= 0 || bit_width <= 0 {
        return error_object(&mut env, "Index dimension and bit width must be positive") as jlong;
    }
    let path_buf = PathBuf::from(path_text);
    let index = if Path::new(&path_buf).is_file() {
        match IdMapIndex::load(&path_buf) {
            Ok(value) => value,
            Err(error) => {
                return error_object(&mut env, format!("Could not load TurboQuant index: {error}"))
                    as jlong
            }
        }
    } else {
        match IdMapIndex::new(dimension as usize, bit_width as usize) {
            Ok(value) => value,
            Err(error) => {
                return error_object(&mut env, format!("Could not create TurboQuant index: {error}"))
                    as jlong
            }
        }
    };

    if index.dim() != dimension as usize || index.bit_width() != bit_width as usize {
        return error_object(
            &mut env,
            format!(
                "Index schema mismatch: file is {}D/{}-bit, requested {}D/{}-bit",
                index.dim(),
                index.bit_width(),
                dimension,
                bit_width
            ),
        ) as jlong;
    }
    index.prepare();
    let quantized = QuantizedVectors::load(&path_buf)
        .unwrap_or_else(|_| QuantizedVectors::empty(bit_width as usize, dimension as usize));
    Box::into_raw(Box::new(NativeIndex {
        path: path_buf,
        index: Mutex::new(index),
        quantized: Mutex::new(quantized),
    })) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let native = unsafe { Box::from_raw(handle as *mut NativeIndex) };
    let persist_result = match native.index.lock() {
        Ok(value) => {
            persist(&native, &value)
        }
        Err(_) => Err("Native index lock is poisoned".to_string()),
    };
    if let Err(error) = persist_result {
        throw(&mut env, error);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeSize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    let native = match unsafe { index_ref(handle) } {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, error) as jlong,
    };
    match native.index.lock() {
        Ok(value) => value.len() as jlong,
        Err(_) => error_object(&mut env, "Native index lock is poisoned") as jlong,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeDim(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let native = match unsafe { index_ref(handle) } {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, error) as jint,
    };
    match native.index.lock() {
        Ok(value) => value.dim() as jint,
        Err(_) => error_object(&mut env, "Native index lock is poisoned") as jint,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeBitWidth(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let native = match unsafe { index_ref(handle) } {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, error) as jint,
    };
    match native.index.lock() {
        Ok(value) => value.bit_width() as jint,
        Err(_) => error_object(&mut env, "Native index lock is poisoned") as jint,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeUpsert(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jlong,
    values: JFloatArray,
) -> jboolean {
    let native = match unsafe { index_ref(handle) } {
        Ok(value) => value,
        Err(error) => {
            throw(&mut env, error);
            return JNI_FALSE;
        }
    };
    let id = match u64::try_from(id) {
        Ok(value) => value,
        Err(_) => {
            throw(&mut env, "Vector id must be non-negative");
            return JNI_FALSE;
        }
    };
    let values = match read_floats(&mut env, &values) {
        Ok(value) => value,
        Err(error) => {
            throw(&mut env, error);
            return JNI_FALSE;
        }
    };
    let mut index = match native.index.lock() {
        Ok(value) => value,
        Err(_) => {
            throw(&mut env, "Native index lock is poisoned");
            return JNI_FALSE;
        }
    };
    if index.contains(id) {
        index.remove(id);
    }
    if let Err(error) = index.add_with_ids(&values, &[id]) {
        throw(&mut env, format!("TurboQuant upsert failed: {error}"));
        return JNI_FALSE;
    }
    index.prepare();
    if let Err(error) = persist(native, &index) {
        throw(&mut env, error);
        return JNI_FALSE;
    }
    if let Ok(updated) = QuantizedVectors::load(&native.path) {
        if let Ok(mut quantized) = native.quantized.lock() {
            *quantized = updated;
        }
    }
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeUpsertBatch(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    ids: JLongArray,
    values: JFloatArray,
) -> jboolean {
    let native = match unsafe { index_ref(handle) } {
        Ok(value) => value,
        Err(error) => {
            throw(&mut env, error);
            return JNI_FALSE;
        }
    };
    let ids = match read_longs(&mut env, &ids) {
        Ok(value) if !value.is_empty() => value,
        Ok(_) => {
            throw(&mut env, "TurboQuant batch must contain at least one id");
            return JNI_FALSE;
        }
        Err(error) => {
            throw(&mut env, error);
            return JNI_FALSE;
        }
    };
    let values = match read_floats(&mut env, &values) {
        Ok(value) => value,
        Err(error) => {
            throw(&mut env, error);
            return JNI_FALSE;
        }
    };
    let mut seen = std::collections::HashSet::with_capacity(ids.len());
    if ids.iter().any(|id| !seen.insert(*id)) {
        throw(&mut env, "TurboQuant batch contains duplicate ids");
        return JNI_FALSE;
    }
    let mut index = match native.index.lock() {
        Ok(value) => value,
        Err(_) => {
            throw(&mut env, "Native index lock is poisoned");
            return JNI_FALSE;
        }
    };
    let expected = match ids.len().checked_mul(index.dim()) {
        Some(value) => value,
        None => {
            throw(&mut env, "TurboQuant batch size overflow");
            return JNI_FALSE;
        }
    };
    if values.len() != expected {
        throw(
            &mut env,
            format!(
                "TurboQuant batch expected {} coordinates, got {}",
                expected,
                values.len()
            ),
        );
        return JNI_FALSE;
    }
    for id in &ids {
        if index.contains(*id) {
            index.remove(*id);
        }
    }
    if let Err(error) = index.add_with_ids(&values, &ids) {
        throw(&mut env, format!("TurboQuant batch upsert failed: {error}"));
        return JNI_FALSE;
    }
    index.prepare();
    if let Err(error) = persist(native, &index) {
        throw(&mut env, error);
        return JNI_FALSE;
    }
    if let Ok(updated) = QuantizedVectors::load(&native.path) {
        if let Ok(mut quantized) = native.quantized.lock() {
            *quantized = updated;
        }
    }
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeRemoveBatch(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    ids: JLongArray,
) -> jboolean {
    let native = match unsafe { index_ref(handle) } {
        Ok(value) => value,
        Err(error) => {
            throw(&mut env, error);
            return JNI_FALSE;
        }
    };
    let raw_ids = match read_longs(&mut env, &ids) {
        Ok(value) if !value.is_empty() => value,
        Ok(_) => {
            throw(&mut env, "TurboQuant remove batch must contain at least one id");
            return JNI_FALSE;
        }
        Err(error) => {
            throw(&mut env, error);
            return JNI_FALSE;
        }
    };
    let mut ids = Vec::with_capacity(raw_ids.len());
    let mut seen = std::collections::HashSet::with_capacity(raw_ids.len());
    for id in raw_ids {
        let id = match u64::try_from(id) {
            Ok(value) => value,
            Err(_) => {
                throw(&mut env, "Vector id must be non-negative");
                return JNI_FALSE;
            }
        };
        if seen.insert(id) {
            ids.push(id);
        }
    }
    let mut index = match native.index.lock() {
        Ok(value) => value,
        Err(_) => {
            throw(&mut env, "Native index lock is poisoned");
            return JNI_FALSE;
        }
    };
    for id in ids {
        if index.contains(id) {
            index.remove(id);
        }
    }
    index.prepare();
    if let Err(error) = persist(native, &index) {
        throw(&mut env, error);
        return JNI_FALSE;
    }
    if let Ok(updated) = QuantizedVectors::load(&native.path) {
        if let Ok(mut quantized) = native.quantized.lock() {
            *quantized = updated;
        }
    }
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeSearch(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: JFloatArray,
    k: jint,
    allowlist: JLongArray,
) -> jobject {
    let native = match unsafe { index_ref(handle) } {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, error),
    };
    if k <= 0 {
        return error_object(&mut env, "Search k must be positive");
    }
    let query = match read_floats(&mut env, &query) {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, error),
    };
    let allowlist_values = if allowlist.is_null() {
        None
    } else {
        match read_longs(&mut env, &allowlist) {
            Ok(value) if !value.is_empty() => Some(value),
            Ok(_) => return error_object(&mut env, "Search allowlist cannot be empty"),
            Err(error) => return error_object(&mut env, error),
        }
    };
    let index = match native.index.lock() {
        Ok(value) => value,
        Err(_) => return error_object(&mut env, "Native index lock is poisoned"),
    };
    // Face/date scopes come from MediaStore/SQLite and can include rows whose
    // visual embedding was skipped or is not present in the persisted index.
    // Ignore only those absent IDs; rejecting the whole allowlist would turn a
    // valid hard-scoped search into an empty semantic channel.
    let allowlist_values = allowlist_values.map(|ids| {
        ids.into_iter()
            .filter(|id| index.contains(*id))
            .collect::<Vec<_>>()
    });
    let (scores, ids) = if allowlist_values.as_ref().is_some_and(Vec::is_empty) {
        (Vec::new(), Vec::new())
    } else {
        index.search_with_allowlist(&query, k as usize, allowlist_values.as_deref())
    };
    let ids_as_i64: Vec<i64> = ids.into_iter().map(|id| id as i64).collect();
    let ids_array = match env.new_long_array(ids_as_i64.len() as jint) {
        Ok(array) => array,
        Err(error) => return error_object(&mut env, format!("Could not allocate result ids: {error}")),
    };
    let scores_array = match env.new_float_array(scores.len() as jint) {
        Ok(array) => array,
        Err(error) => return error_object(&mut env, format!("Could not allocate result scores: {error}")),
    };
    if let Err(error) = env.set_long_array_region(&ids_array, 0, &ids_as_i64) {
        return error_object(&mut env, format!("Could not write result ids: {error}"));
    }
    if let Err(error) = env.set_float_array_region(&scores_array, 0, &scores) {
        return error_object(&mut env, format!("Could not write result scores: {error}"));
    }
    let ids_object = JObject::from(ids_array);
    let scores_object = JObject::from(scores_array);
    let result_class = match env.find_class("com/ravi/askgalaxy/NativeSearchResult") {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, format!("Could not find search result class: {error}")),
    };
    match env.new_object(
        result_class,
        "([J[F)V",
        &[JValue::Object(&ids_object), JValue::Object(&scores_object)],
    ) {
        Ok(result) => result.into_raw(),
        Err(error) => error_object(&mut env, format!("Could not construct search result: {error}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ravi_askgalaxy_NativeVectorIndex_nativeSelectDiverse(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    candidate_ids: JLongArray,
    max_count: jint,
) -> jlongArray {
    let native = match unsafe { index_ref(handle) } {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, error) as jlongArray,
    };
    if max_count <= 0 {
        return error_object(&mut env, "Diversity max count must be positive") as jlongArray;
    }
    let candidate_ids = match read_longs(&mut env, &candidate_ids) {
        Ok(value) => value,
        Err(error) => return error_object(&mut env, error) as jlongArray,
    };
    let selected = match native.quantized.lock() {
        Ok(value) => value.select_diverse(&candidate_ids, max_count as usize),
        Err(_) => return error_object(&mut env, "Native diversity lock is poisoned") as jlongArray,
    };
    let selected_as_i64 = selected
        .into_iter()
        .map(|id| id as i64)
        .collect::<Vec<_>>();
    let output = match env.new_long_array(selected_as_i64.len() as jint) {
        Ok(value) => value,
        Err(error) => {
            return error_object(
                &mut env,
                format!("Could not allocate diversity ids: {error}"),
            ) as jlongArray
        }
    };
    if let Err(error) = env.set_long_array_region(&output, 0, &selected_as_i64) {
        return error_object(&mut env, format!("Could not write diversity ids: {error}"))
            as jlongArray;
    }
    output.into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn turboquant_index_round_trips_search() {
        let mut index = IdMapIndex::new(8, 4).expect("valid index schema");
        index
            .add_with_ids(
                &[1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                  0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
                &[11, 22],
            )
            .expect("vectors add");
        let (scores, ids) = index.search(&[1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0], 1);
        assert_eq!(scores.len(), 1);
        assert_eq!(ids, vec![11]);
    }

    #[test]
    fn siglip2_tokenizer_matches_pinned_contract() {
        let path = std::env::var("ASKGALAXY_TOKENIZER")
            .expect("ASKGALAXY_TOKENIZER must point to tokenizer.json");
        let tokenizer = Tokenizer::from_file(path).expect("tokenizer loads");
        let encoding = tokenizer
            .encode("a photo of two cats", true)
            .expect("query tokenizes");
        assert_eq!(encoding.get_ids().len(), 64);
        assert_eq!(
            &encoding.get_ids()[..6],
            &[235250, 2686, 576, 1378, 19493, 1]
        );
        assert!(encoding.get_ids()[6..].iter().all(|id| *id == 0));
    }
}
