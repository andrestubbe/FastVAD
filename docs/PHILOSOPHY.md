# FastVAD Philosophy — Zero-Allocation Streaming & Turn-Taking

1. **Sub-10ms Native Hot Path**: Voice detection must never introduce perceptible lag into conversational agent loops. FastVAD processes 10ms frames in <0.2ms.
2. **Deterministic Debounce**: Neural networks produce probabilistic outputs; FastVAD wraps them in deterministic temporal hysteresis to guarantee rock-solid stability.
3. **Zero Heap Churn**: Audio buffers flow directly across native pointers without creating temporary JVM objects during continuous 24/7 background listening.