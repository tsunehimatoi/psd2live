# Umamo IR bridge

GPL-3.0, under the repository [LICENSE](../LICENSE). This module connects the GPL
Umamo engine model to the MIT neutral `format-model` contract. MIT modules must
not depend on this implementation.

`PuppetIr` converts `PuppetModel` fields in both directions, without target
lowering or export compilation. Packed images, page bindings, physics, clips
and parameter roles are explicit inputs to `toIr`; these sidecars do not live
in `PuppetModel`. IR-only data such as rest poses, tile artwork, hit areas and
advanced runtime data remains the caller's responsibility.

Algorithms depend on this module for conversion. Cubism target implementations
consume it for conversion before applying target-specific lowering and writing.
The synthetic tests exercise mapped fields, object identity/order, references,
channels, arrays, source inventory, atlas metadata and explicit sidecars.
