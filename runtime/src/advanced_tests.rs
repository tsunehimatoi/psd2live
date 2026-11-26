//! Advanced mode on small rigs whose exact answer is known: a forearm baked at 30° keys, a link baked along an arc.

use crate::advanced::{Advanced, EXACT_LINKS, SKIN};
use crate::eval::Evaluator;
use crate::rig::*;

fn rotate(x: f32, y: f32, degrees: f32) -> [f32; 2] {
    let (s, c) = degrees.to_radians().sin_cos();
    [x * c - y * s, x * s + y * c]
}

fn rotation(id: &str, parent: Option<usize>, pivot: Option<Grid<Pivot>>) -> Deformer {
    Deformer {
        id: id.into(), parent, part: None, visible: true, enabled: true, opacity: 1.0, multiply: WHITE, screen: BLACK, channels: vec![],
        kind: DeformerKind::Rotation { base_angle: 0.0, pivot, flip_x: false, flip_y: false, shapes: vec![] },
    }
}

fn axis_grid<F>(parameter: usize, keys: &[f32], forms: Vec<F>) -> Grid<F> {
    Grid::new(vec![Axis { parameter, keys: keys.to_vec() }], forms.into_iter().enumerate().map(|(i, f)| (vec![i as u16], f)).collect()).unwrap()
}

fn base_rig(parameters: Vec<Parameter>, deformers: Vec<Deformer>, meshes: Vec<Mesh>) -> Rig {
    Rig {
        canvas: Canvas { width: 512.0, height: 512.0, origin_x: 0.0, origin_y: 0.0, pixels_per_unit: None },
        parameters, deformers, parts: vec![], meshes, glues: vec![],
        render: RenderGroup { part: None, draw_order: 500, channels: vec![], composite: None, children: vec![] },
        textures: vec![], physics_fps: 0.0, physics: vec![], clips: vec![], roles: vec![], gui: None, meta: vec![], expressions: vec![], hit_areas: vec![], user_data: vec![], poses: vec![], pose_fade_in: 0.0,
        extensions: Extensions::default(),
    }
}

const KEYS: [f32; 7] = [-90.0, -60.0, -30.0, 0.0, 30.0, 60.0, 90.0];
const ELBOW: [f32; 2] = [0.0, 100.0];

/// An upper arm (home, at the origin) and a forearm turning about the elbow on parameter 0. The mesh hangs
/// from the upper arm: its first two vertices on the upper arm, the rest along the forearm, baked rigidly at
/// every key and so interpolated along chords between them.
pub(crate) fn arm() -> (Rig, Vec<f32>) {
    let forearm: Vec<[f32; 2]> = vec![[10.0, 40.0], [-10.0, 80.0], [0.0, 60.0], [20.0, 120.0], [-15.0, 160.0]];
    let rest: Vec<f32> = forearm.iter().enumerate().flat_map(|(i, p)| if i < 2 { [p[0], p[1]] } else { [p[0] + ELBOW[0], p[1] + ELBOW[1]] }).collect();
    let offsets = KEYS.iter().map(|k| {
        let mut d = vec![0.0; rest.len()];
        for v in 2..forearm.len() {
            let q = rotate(forearm[v][0], forearm[v][1], *k);
            d[v * 2] = q[0] - forearm[v][0];
            d[v * 2 + 1] = q[1] - forearm[v][1];
        }
        d
    }).collect();
    let pivots = KEYS.iter().map(|k| Pivot { x: ELBOW[0], y: ELBOW[1], angle: *k, scale: 1.0 }).collect();
    let mesh = Mesh {
        id: "arm".into(), name: "arm".into(), parent: Some(0), blend: 0, alpha_blend: 0, masked_by: vec![], invert_mask: false, culling: false,
        visible: true, page: -1,
        geometry: Some(Geometry { positions: rest.clone(), uvs: vec![0.0; rest.len()], indices: vec![0, 1, 2, 2, 3, 4] }),
        offsets: Some(axis_grid(0, &KEYS, offsets)), channels: vec![], draw_order: 500.0, opacity: 1.0, multiply: WHITE, screen: BLACK, shapes: vec![],
    };
    let mut rig = base_rig(
        vec![Parameter { id: "Elbow".into(), name: "Elbow".into(), min: -90.0, max: 90.0, default: 0.0, blend: false, repeat: false }],
        vec![rotation("upper", None, None), rotation("forearm", Some(0), Some(axis_grid(0, &KEYS, pivots)))],
        vec![mesh],
    );
    rig.extensions.skins.push(Skin { mesh: 0, axes: vec![(0, KEYS.to_vec())], bones: vec![0, 1], from: vec![], to: vec![], weight: vec![] });
    (rig, forearm.iter().flat_map(|p| *p).collect())
}

/// Where the forearm's vertices truly are at [angle]: rotated rigidly about the elbow.
fn exact(forearm: &[f32], angle: f32) -> Vec<f32> {
    (0..forearm.len() / 2).flat_map(|v| {
        let (x, y) = (forearm[v * 2], forearm[v * 2 + 1]);
        if v < 2 { [x, y] } else { let q = rotate(x, y, angle); [q[0] + ELBOW[0], q[1] + ELBOW[1]] }
    }).collect()
}

fn worst(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| (x - y).abs()).fold(0.0, f32::max)
}

#[test]
fn a_cut_vertex_can_follow_another_vertex_on_its_mesh() {
    let (mut rig, _) = arm();
    let mut evaluator = Evaluator::new();
    let before = evaluator.evaluate(&rig, &[30.0]).vertices[0].clone();
    rig.glues.push(Glue {
        id: "partition-follow".into(), mesh_a: 0, mesh_b: 0, intensity: 1.0,
        pairs: vec![GluePair { a: 2, b: 0, weight_a: 0.5, weight_b: 0.0 }], channels: vec![],
    });
    let after = &evaluator.evaluate(&rig, &[30.0]).vertices[0];
    assert_eq!(&after[..4], &before[..4]);
    assert_eq!(&after[6..], &before[6..]);
    for k in 0..2 {
        assert!((after[4 + k] - (before[4 + k] + before[k]) * 0.5).abs() < 1e-5);
    }
}

#[test]
fn skinning_follows_the_arc_between_keys_and_the_bake_at_them() {
    let (rig, forearm) = arm();
    let mut advanced = Advanced::new();
    assert_eq!(advanced.set(&rig, SKIN | EXACT_LINKS), SKIN);
    let (mut core, mut skinned) = (Evaluator::new(), Evaluator::new());
    for angle in [-90.0, -75.0, -44.0, -30.0, 0.0, 7.5, 15.0, 30.0, 51.0, 90.0] {
        let want = exact(&forearm, angle);
        let baked = core.evaluate(&rig, &[angle]).vertices[0].clone();
        let live = skinned.evaluate_ext(&rig, &[angle], Some(&mut advanced)).vertices[0].clone();
        assert!(worst(&live, &want) < 1e-3, "at {}: {:?} vs {:?}", angle, live, want);
        if KEYS.contains(&angle) {
            assert!(worst(&live, &baked) < 1e-3);
        } else if angle != 7.5 && angle != 51.0 {
            // Halfway between keys the chord of the outermost vertex falls short by r (1 - cos 15°), over a pixel.
            assert!(worst(&baked, &want) > 0.5, "the bake should cut the chord at {}", angle);
        }
    }
}

#[test]
fn off_or_without_extensions_advanced_evaluation_is_the_core_one() {
    let (rig, _) = arm();
    let mut advanced = Advanced::new();
    let (mut core, mut off) = (Evaluator::new(), Evaluator::new());
    for angle in [-80.0, -12.5, 0.0, 44.0] {
        let a = core.evaluate(&rig, &[angle]).vertices[0].clone();
        let b = off.evaluate_ext(&rig, &[angle], Some(&mut advanced)).vertices[0].clone();
        assert_eq!(a.iter().map(|v| v.to_bits()).collect::<Vec<_>>(), b.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
    }
    // Turned on and off again, nothing remains of it.
    advanced.set(&rig, SKIN);
    off.evaluate_ext(&rig, &[20.0], Some(&mut advanced));
    advanced.set(&rig, 0);
    let a = core.evaluate(&rig, &[20.0]).vertices[0].clone();
    let b = off.evaluate_ext(&rig, &[20.0], Some(&mut advanced)).vertices[0].clone();
    assert_eq!(a, b);
}

#[test]
fn a_virtual_bone_skins_as_the_rigs_own_rotation_would() {
    // The forearm folded away, as a skeleton bake leaves it: only the skinning knows it, as deformer 1 (= 1 + 0).
    let (mut rig, forearm) = arm();
    let bone = rig.deformers.pop().unwrap();
    rig.extensions.virtual_bones.push(bone);
    let mut advanced = Advanced::new();
    assert_eq!(advanced.set(&rig, SKIN), SKIN);
    for angle in [-77.0, -30.0, 12.0, 45.0, 88.0] {
        let live = Evaluator::new().evaluate_ext(&rig, &[angle], Some(&mut advanced)).vertices[0].clone();
        assert!(worst(&live, &exact(&forearm, angle)) < 1e-3, "at {}", angle);
    }
    // Hosts can read its frame: the elbow, turned.
    let frame = advanced.bone_frame(&rig, &[45.0], 1).unwrap();
    let elbow = frame.apply(0.0, 0.0);
    assert!((elbow[0] - ELBOW[0]).abs() < 1e-4 && (elbow[1] - ELBOW[1]).abs() < 1e-4);
    assert!((frame.c - 45f32.to_radians().sin()).abs() < 1e-5);
}

/// A triangle hanging from its two top corners, its free corner dropping onto a circle collider below.
fn hanging() -> Rig {
    use crate::sim::Simulation;
    let mesh = Mesh {
        id: "cloth".into(), name: "cloth".into(), parent: None, blend: 0, alpha_blend: 0, masked_by: vec![], invert_mask: false, culling: false,
        visible: true, page: -1,
        geometry: Some(Geometry { positions: vec![0.0, 0.0, 20.0, 0.0, 10.0, 30.0], uvs: vec![0.0; 6], indices: vec![0, 1, 2] }),
        offsets: None, channels: vec![], draw_order: 500.0, opacity: 1.0, multiply: WHITE, screen: BLACK, shapes: vec![],
    };
    let mut rig = base_rig(vec![], vec![], vec![mesh]);
    let sim = Simulation {
        fps: 60.0, substeps: 8, gravity: [0.0, -980.0], wind: [0.0, 0.0], pin_compliance: 1e-4, targets: vec![(0, 0)],
        inv_mass: vec![1.0; 3], damping: vec![1.0; 3], wind_factor: vec![1.0; 3], pin_weight: vec![1.0, 1.0, 0.0],
        goal_compliance: vec![-1.0; 3], goal_offset_x: vec![0.0; 3], goal_offset_y: vec![0.0; 3], anchors: vec![None; 3],
        stretch_a: vec![0, 1], stretch_b: vec![2, 2], stretch_rest: vec![31.6, 31.6], stretch_compliance: vec![1e-8; 2], compression_compliance: vec![1e-3; 2],
        colliders: vec![0],
        ..Default::default()
    };
    rig.extensions.simulations.push(sim);
    rig.extensions.simulation_overrides.push((vec![], vec![]));
    // A circle of radius 8 just left of where the free corner hangs, in canvas pixels (y down).
    rig.extensions.colliders.push(Collider {
        id: "ball".into(), capsule: false, deformer: None, mesh: None, vertex_a: 0, vertex_b: 0,
        a: [4.0, 36.0], b: [4.0, 36.0], radius_a: 8.0, radius_b: 8.0, friction: 0.0,
    });
    rig
}

#[test]
fn a_simulated_mesh_hangs_and_keeps_out_of_its_collider() {
    use crate::advanced::{COLLISION, SIM};
    let rig = hanging();
    let mut advanced = Advanced::new();
    assert_eq!(Advanced::available(&rig), SIM | COLLISION);
    assert_eq!(advanced.set(&rig, SIM | COLLISION), SIM | COLLISION);
    let mut evaluator = Evaluator::new();
    let mut free = [0.0f32; 2];
    for frame in 0..240 {
        evaluator.evaluate_ext(&rig, &[], Some(&mut advanced));
        advanced.step_simulations(&rig, &[], if frame == 0 { 0.0 } else { 1.0 / 60.0 }, &mut evaluator.pose);
        let v = &evaluator.pose.vertices[0];
        // The pinned corners stay put; the free one never enters the ball.
        assert_eq!(&v[..4], &[0.0, 0.0, 20.0, 0.0]);
        free = [v[4], v[5]];
        assert!(free.iter().all(|c| c.is_finite()));
        assert!((free[0] - 4.0).hypot(free[1] - 36.0) >= 8.0 - 1e-3, "frame {} at {:?}", frame, free);
    }
    // At rest it hangs below its pins, pushed off the ball to the right and kept at its length.
    assert!(free[0] > 10.0 && free[1] > 25.0, "{:?}", free);
    assert!((free[0] - 0.0).hypot(free[1]) < 31.6 * 1.05);
    // Without collision it drops back to where it was drawn, straight under its pins.
    advanced.set(&rig, SIM);
    for frame in 0..240 {
        evaluator.evaluate_ext(&rig, &[], Some(&mut advanced));
        advanced.step_simulations(&rig, &[], if frame == 0 { 0.0 } else { 1.0 / 60.0 }, &mut evaluator.pose);
    }
    let v = &evaluator.pose.vertices[0];
    assert!((v[4] - 10.0).abs() < 1.0, "{:?}", v);
}

#[test]
fn behaviors_and_expressions_do_not_pile_up_over_updates() {
    use crate::expression::{Blend, Expression};
    use crate::ffi::*;
    // A head turn with the roles breathing and gaze drive, and an expression that adds to it.
    let (mut rig, _) = arm();
    rig.parameters[0].id = "ParamAngleX".into();
    rig.roles = vec![Role { role: "AngleX".into(), parameters: vec![0] }];
    rig.expressions = vec![Expression { id: "add".into(), name: "add".into(), fade_in: 0.1, fade_out: 0.1, parameters: vec![(0, Blend::Add, 5.0)] }];
    let handle = Box::into_raw(Box::new(Handle::new(std::sync::Arc::new(Model::new(rig)))));
    unsafe {
        p2l_behaviors(handle, 2 | 4);
        p2l_look_at(handle, 1.0, 0.0);
        p2l_expression(handle, 0);
        for _ in 0..3000 {
            p2l_update(handle, 1.0 / 60.0);
        }
        // The host's pose is untouched; what was evaluated is that pose with one frame's layers on it.
        assert_eq!(*p2l_parameter_values(handle), 0.0);
        let current = *p2l_parameter_current(handle);
        assert!(current > 5.0 && current <= 90.0 + 5.0 + 1.0, "{}", current);
        p2l_rig_free(handle);
    }
}

#[test]
fn given_weights_are_used_as_they_are() {
    let (mut rig, forearm) = arm();
    // The fit would find these: the upper arm's two vertices on bone 0, the forearm's on bone 1.
    rig.extensions.skins[0].from = vec![0, 0, 1, 1, 1];
    rig.extensions.skins[0].to = vec![0, 0, 1, 1, 1];
    rig.extensions.skins[0].weight = vec![0.0; 5];
    let mut advanced = Advanced::new();
    advanced.set(&rig, SKIN);
    let live = Evaluator::new().evaluate_ext(&rig, &[45.0], Some(&mut advanced)).vertices[0].clone();
    assert!(worst(&live, &exact(&forearm, 45.0)) < 1e-3);
}

#[test]
fn a_link_baked_along_an_arc_interpolates_on_it() {
    // A child pivot keyed every 60° on a circle of radius 50 about (20, 30) in its parent's space.
    let keys = [-60.0, 0.0, 60.0];
    let at = |a: f32| { let q = rotate(0.0, 50.0, a); Pivot { x: 20.0 + q[0], y: 30.0 + q[1], angle: a, scale: 1.0 } };
    let mut rig = base_rig(
        vec![Parameter { id: "Link".into(), name: "Link".into(), min: -60.0, max: 60.0, default: 0.0, blend: false, repeat: false }],
        vec![rotation("host", None, None), rotation("child", Some(0), Some(axis_grid(0, &keys, keys.iter().map(|k| at(*k)).collect())))],
        vec![],
    );
    rig.extensions.arcs.push(Arc { deformer: 1, parameter: 0, center: [20.0, 30.0] });
    let mut advanced = Advanced::new();
    assert_eq!(advanced.set(&rig, EXACT_LINKS), EXACT_LINKS);
    let origin = |t: &crate::eval::Transform| match t { crate::eval::Transform::Rotation { origin, .. } => *origin, _ => unreachable!() };
    for angle in [-45.0, -20.0, 10.0, 30.0, 59.0] {
        let want = at(angle);
        let live = origin(&Evaluator::new().evaluate_ext(&rig, &[angle], Some(&mut advanced)).deformers[1]);
        assert!((live[0] - want.x).abs() < 1e-3 && (live[1] - want.y).abs() < 1e-3, "{} {:?} vs {:?}", angle, live, (want.x, want.y));
        let chord = origin(&Evaluator::new().evaluate(&rig, &[angle]).deformers[1]);
        assert!((chord[0] - 20.0).hypot(chord[1] - 30.0) < 50.0 - 1e-2);
    }
}
