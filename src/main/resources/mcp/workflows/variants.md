# Painted variants and expression switches

A variant combines new artwork with a visibility relationship. Useful notes: what it replaces, what remains shared, attachment, control parameter, and desired transition (discrete switch or crossfade).

Keep unchanged regions when practical. Generate or paint only the missing expression or action; register it to the source frame. New eyes or mouths may need different deformation ownership from the originals.

Use parameter and keyform tools for opacity at explicit states. The runtime interpolates keyforms: two endpoints alone imply a transition, not a true discrete switch. Closely spaced keys approximate a switch but still interpolate between them. Do not describe that as native step interpolation. Check intermediate states when they matter, including overlap with blink/mouth/head parameters.

If a switch shows duplicate features, inspect visibility bindings before repainting. If artwork is aligned only at neutral, inspect parent ownership and inherited shapes. Keep pixel creation and parameter binding as separate editable results.
