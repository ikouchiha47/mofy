# EmbeddingGemma-300m Android Integration

On-device semantic embedding for Mofy using EmbeddingGemma-300m via LiteRT
and DJL Android tokenizers.

---

## Which model file to use

The `litert-community/embeddinggemma-300m` HuggingFace repo has three variants:

| Folder | Format | Size | Notes |
|--------|--------|------|-------|
| `1_Pooling` | — | — | config only, not a model |
| `2_Dense` | `.tflite` (mixed-precision) | ~179 MB | **use this** |
| `3_Dense` | `.litertlm` | ~179 MB | LiteRT Model format — requires newer API |

We use the `.tflite` from `2_Dense` (seq_len=256). Both model repos are gated
on HuggingFace; we host files on the GitHub release to avoid runtime auth:

```
https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/mofy_embedder_seq256.tflite
https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/embeddinggemma_tokenizer.json
```

The tokenizer comes from `google/embeddinggemma-300m` (also gated).

---

## Maven dependencies

```kotlin
// build.gradle.kts

// LiteRT — use litert:1.4.0, NOT 2.x
// litert:2.2.0 + litert-api:2.2.0 both declare namespace com.google.ai.edge.litert → build conflict.
// litert-api:2.2.0 (CompiledModel) aborts in Environment.nativeCreate on Android 16 (Nothing Phone).
// litert:1.4.0 uses namespace org.tensorflow.lite, litert-api:1.4.0 uses org.tensorflow.lite.api
// — no conflict — and bundles a self-contained libtensorflowlite_jni.so.
implementation("com.google.ai.edge.litert:litert:1.4.0")

// DJL tokenizers — two artifacts required: Java API + ARM64 native .so
implementation("ai.djl.huggingface:tokenizers:0.33.0")
implementation("ai.djl.android:tokenizer-native:0.33.0")
```

### Why not litert 2.x?

| Artifact | Namespace | Native lib | Status |
|----------|-----------|------------|--------|
| `litert:2.2.0` | `org.tensorflow.lite` | `libLiteRt.so` | Interpreter classes only |
| `litert-api:2.2.0` | `com.google.ai.edge.litert` | `liblitert_jni.so` | CompiledModel + Interpreter |

`litert:2.2.0` transitively pulls in `litert-api:2.2.0`. Both declare the same
Android namespace → manifest merger fails. Additionally, `Environment.create(context)`
in `litert-api:2.2.0` calls `abort()` via `Java_com_google_ai_edge_litert_Environment_nativeCreate`
on this device — SIGABRT, no workaround found.

`litert:1.4.0` is self-contained (`libtensorflowlite_jni.so`) and stable.

---

## ModelDownloader interface

Both `OnDeviceEmbedder` and `ModelBasedFacetDecoder` use a shared interface:

```kotlin
interface ModelDownloader {
    fun download(url: String, dest: File)
    fun downloadWithProgress(url: String, dest: File, title: String)
}
```

`HttpModelDownloader` implements it with progress notifications, redirect resolution,
and optional `hfToken` Bearer auth. Downloads are triggered on app launch alongside
the facet decoder — both run in parallel `launch(Dispatchers.IO)` blocks.

---

## Interpreter API

```kotlin
val opts = Interpreter.Options().apply { setNumThreads(2) }
val interpreter = Interpreter(modelFile, opts)  // org.tensorflow.lite.Interpreter

// Inspect output shape at runtime (handles [1,768] and [1,seqLen,768])
val outShape = interpreter.getOutputTensor(0).shape()

// Input: int32 shape [1, MAX_SEQ_LEN]
val inputIds = Array(1) { IntArray(MAX_SEQ_LEN) { i -> if (i < ids.size) ids[i].toInt() else 0 } }
```

First inference is ~5 seconds on a Nothing Phone (model load + JIT). Subsequent
calls are faster. Init is called on app launch so the model is warm by the time
the user searches.

---

## Tokenization

```kotlin
val tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
val encoding = tokenizer.encode(PROMPT_PREFIX + text)  // no second boolean arg
val ids: LongArray = encoding.ids
```

### Prompt prefix

```kotlin
const val PROMPT_PREFIX = "task: search result | query: "
```

---

## Output shape and mean pooling

The model outputs `[1, 768]` (already pooled). Handle both cases defensively:

```kotlin
val raw: FloatArray = when {
    outShape.size == 3 -> {
        // [1, seqLen, dim] — mean-pool real tokens
        val buf = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }
        interpreter.run(inputIds, buf)
        FloatArray(EMBEDDING_DIM) { dim ->
            (0 until actualLen).sumOf { t -> buf[0][t][dim].toDouble() }.toFloat() / actualLen
        }
    }
    outShape.size == 2 -> {
        val buf = Array(1) { FloatArray(outShape[1]) }
        interpreter.run(inputIds, buf)
        buf[0]
    }
    else -> {
        val buf = FloatArray(EMBEDDING_DIM)
        interpreter.run(inputIds, buf)
        buf
    }
}
return l2Normalize(raw)
```

---

## Matryoshka truncation for KNN

`catalog_vec.db` stores `float[256]` vectors (built with a 256-dim model).
EmbeddingGemma outputs 768-dim. Truncate to first 256 dims and re-normalize
(Matryoshka property — the model is trained to preserve information in the
leading dimensions):

```kotlin
val raw256 = queryVec.copyOf(256)
val norm = sqrt(raw256.fold(0f) { acc, x -> acc + x * x })
val vec256 = if (norm == 0f) raw256 else FloatArray(256) { raw256[it] / norm }
VecDatabase.knn(context, vec256, k)
```

---

## sqlite-vec KNN query

`catalog_vec` is a `vec0` virtual table. The correct query syntax:

```sql
-- Must use k = ? in WHERE (not LIMIT ?) or vec0 raises:
-- "A LIMIT or 'k = ?' constraint is required on vec0 knn queries."
-- In a JOIN context, wrap in CTE to access the distance column.
WITH knn AS (
  SELECT rowid, distance FROM catalog_vec WHERE embedding MATCH ? AND k = ?
)
SELECT m.tconst, m.title
FROM knn
JOIN catalog_meta m ON m.rowid = knn.rowid
ORDER BY knn.distance
```

Pass the query vector as a `ByteArray` of little-endian float32 values.

---

## Constants

```kotlin
const val MAX_SEQ_LEN = 256
const val EMBEDDING_DIM = 768
const val VEC_DB_DIM = 256  // catalog_vec.db stores float[256]
```
