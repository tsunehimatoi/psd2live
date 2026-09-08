package org.umamo.edit

import org.umamo.runtime.model.ParameterId

/**
 * One selected keyform key: the parameter section, the row, and WHICH key on that row.
 *
 * By ordinal rather than by parameter value.  Two keys a hair apart are legal and useful, and resolving a
 * value back to a key would then pick whichever happens to be nearer - dragging one of a pair of
 * near-coincident marks would silently move the other instead.  The parameter is part of the identity
 * because a linked pair renders two sections at once and one item's row key is the same string in both.
 *
 * [rowKey] is an opaque string to this module, and deliberately so.  It is the keyform sheet's row identity,
 * built from the OWNER plus what the row edits (a channel, the geometry, a blend binding) - model identity
 * throughout, so it survives every edit that does not delete the thing it names.  Resolving it back to a
 * track is the sheet's job because only the sheet knows which rows it is currently projecting; a session
 * that tried to would have to duplicate that projection to answer.
 *
 * @property ParameterId parameterId The parameter whose section the key sits in.
 * @property String rowKey The owning row's stable key.
 * @property Int keyIndex The key's ordinal on that row's track.
 */
data class TrackKeyRef(val parameterId: ParameterId, val rowKey: String, val keyIndex: Int)