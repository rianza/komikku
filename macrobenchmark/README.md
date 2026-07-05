# Macrobenchmark

This module benchmarks the app to generate baseline profiles which can then be used to improve app startup time.

To generate a new baseline profile:
```
./gradlew :macrobenchmark:connectedBenchmark
```

The generated baseline profile will be output to `app/build/outputs/baseline-profiles/`.
