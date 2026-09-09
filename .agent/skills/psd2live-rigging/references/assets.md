# Artwork and placement

PSD2Live accepts PNG assets; painting, vector rasterization and host image generation are authoring choices. Choose according to style, available tools and the user's preference. The server does not contain an image generator.

A useful path is reference -> artwork -> import -> registration -> trial composite -> layer. It is a menu, not a required loop. Reuse references and candidates.

asset_prepare_reference supplies clean artwork plus separate context and a source frame. Native alpha is usable; if a generator needs a solid matte, choose a contrasting uniform RGB and pass the actual solid_background at import. A drawn checkerboard is not alpha. asset_inspect/reprocess diagnose processing while retaining original pixels.

Frame registration assumes the declared frame was preserved. For crops/padding declare rectangles; for recentered art use matching landmarks. Image dimensions and alpha bounds do not determine canvas placement. Views use top-left X-right Y-down canvas coordinates; geometry edits use parent-local coordinates.

Preview replacements with explicit replace_layer_ids. Imported layers inherit the reference parent or an explicit parent. Placement is adjustable before finalization/dedicated rig edits; existing animation is not automatically relocated. Keep recoverable originals and use history rather than displaying both original and replacement to evaluate assembly.
