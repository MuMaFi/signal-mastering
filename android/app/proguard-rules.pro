# ONNX Runtime reaches into its Java classes from native code.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
