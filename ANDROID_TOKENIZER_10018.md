# 10018 Android tokenizer compatibility correction

Run 36556756053 passed source preservation, native packaging and compilation but
actual Android synthesis exposed UnsupportedOperationException for the tokenizer's
UNICODE_CHARACTER_CLASS flag. Android always uses Unicode character classes and
does not support setting this desktop-Java flag.

The correction removes that flag and spells whitespace using IsWhite_Space so the
same expression retains Unicode semantics in Android and desktop test execution.
The BPE vocabulary, merges, special tokens, conditioning, models and generation
code are unchanged. The four saved host tokenization goldens still pass, including
accented Latin, CJK, punctuation and native reaction tags. All 97 local unit tests
and both native graphics/tokenizer probes were rerun successfully.

The existing real-model Android test constructs this tokenizer before inference;
it must pass against the corrected APK. No Android success or phone acceptance is
inferred from local checks. The source checkpoint digest is updated for this one
canonical Kotlin change, with all other 40 application/test files preserved.

Reference: https://developer.android.com/reference/java/util/regex/Pattern#UNICODE_CHARACTER_CLASS
