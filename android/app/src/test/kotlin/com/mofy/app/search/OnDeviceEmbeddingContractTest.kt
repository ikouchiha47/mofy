package com.mofy.app.search

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Track B3 (docs/tasks/09-recommendation-engine.md, ADR 0002/0007):
 * EmbeddingGemma-300M via LiteRT + DJL tokenizer, on-device query embedding.
 *
 * NOT tested here (deliberately, not an oversight):
 * - There is no real model runnable in pure-JVM test scope — LiteRT and the
 *   `.tflite` EmbeddingGemma-300M artifact require the Android runtime/NDK
 *   and are not available under plain JUnit5 on the JVM. This project also
 *   has no Robolectric/Room-instrumentation test infra to fake that around
 *   (see CLAUDE.md / other test files in this package — pure JVM only).
 * - Faking this with a trivial "same input string -> same output" assertion
 *   over a hand-written stub would be tautological (the stub would just be
 *   an identity/hash function, proving nothing about the real model's
 *   determinism) and was explicitly avoided per task instructions.
 *
 * This test is `@Disabled` and documents the contract the real on-device
 * embedder must satisfy; it becomes a real assertion once B3 has a LiteRT
 * integration to run against, in an instrumented/on-device test target.
 */
class OnDeviceEmbeddingContractTest {

    @Test
    @Disabled(
        "Deferred to instrumented/on-device testing — requires the real LiteRT " +
            "runtime + EmbeddingGemma-300M .tflite artifact + DJL tokenizer, none " +
            "of which run in a pure-JVM unit test. Contract to verify once that " +
            "integration exists: embedding the same input string twice (same model " +
            "version) yields bit-identical (or near-identical within a tight float " +
            "tolerance) output vectors — i.e. the on-device embedder is a pure, " +
            "deterministic function of its input text, with no reliance on RNG " +
            "seeds, thread-timing, or uninitialized buffer state.",
    )
    fun `same input string embedded twice yields identical output vectors`() {
        error("not runnable in pure-JVM scope — see @Disabled reason")
    }
}
