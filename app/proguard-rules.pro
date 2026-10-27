# The native library looks these up by name through JNI.
-keep class io.github.l0gic0l.andslice.engine.NativeEngine { *; }
-keep interface io.github.l0gic0l.andslice.engine.SliceListener { *; }
-keep class * implements io.github.l0gic0l.andslice.engine.SliceListener { *; }
