# LSPosed discovers the module entry point by fully-qualified class name via the plaintext
# assets/xposed_init file, not through anything R8 can see (no manifest reference, no
# reflection call it can trace) — keep this class and its members intact or the module
# silently stops loading with no build error to point at why.
-keep class space.bdeshi.veil.VeilHook { *; }
