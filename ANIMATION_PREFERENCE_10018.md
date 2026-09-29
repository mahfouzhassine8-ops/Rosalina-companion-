# 10018 animation preference correction

Source review found that the new renderer always enabled its motion budget, ignoring
the existing Animate Rosalina preference. The reviewed correction reads the saved
preference on creation and bind, disables the frame clock and speaking articulation
when the user turns animation off, and keeps the scene and underlying audio/session
state intact. Thermal reductions still retain essential lips while animation is enabled.

A regression test verifies that user-disabled animation stops mouth, hair, breathing,
blinking and gestures without changing the actual Speaking/playback state. All 98
local unit tests pass. Canonical corrections are applied only to the exact prior
source hashes and all results are verified before files are written. The three
modified canonical paths remain in the 41-file source inventory.

The voice models, native linkage repair and tokenizer correction are unchanged.
Physical-phone quality and the full original brief remain unaccepted.
