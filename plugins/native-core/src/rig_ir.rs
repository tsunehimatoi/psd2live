#![allow(dead_code)]

use psd2live_plugin_sdk::{Json, Request, Response};
use std::collections::{HashMap, HashSet};
use std::io;

pub const RIG_BINARY_MAGIC: u32 = 0x5052_4952; // "PRIR"
pub const RIG_OBJECTS_MAGIC: u32 = 0x5052_494F; // "PRIO"
pub const RIG_BINARY_VERSION: u32 = 6;
pub const RIG_OBJECTS_MIN_VERSION: u32 = 4;
const PLANAR_FLOATS_VERSION: u32 = 5;
const HIT_AREAS_VERSION: u32 = 6;

#[derive(Clone, Debug, PartialEq)]
pub struct Rgb {
    pub r: f32,
    pub g: f32,
    pub b: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Canvas {
    pub width: f32,
    pub height: f32,
    pub origin_x: f32,
    pub origin_y: f32,
    pub pixels_per_unit: Option<f32>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Parameter {
    pub id: String,
    pub name: String,
    pub min: f32,
    pub max: f32,
    pub default: f32,
    pub blend: bool,
    pub repeat: bool,
    pub keys: Option<Vec<f32>>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ParameterLink {
    pub horizontal: String,
    pub vertical: String,
}

#[derive(Clone, Debug, PartialEq)]
pub enum GroupLabel {
    None,
    Preset(String),
    Custom(i32),
}

#[derive(Clone, Debug, PartialEq)]
pub enum ParameterNode {
    Param(String),
    Group {
        id: String,
        name: String,
        open: bool,
        children: Vec<ParameterNode>,
        label: GroupLabel,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct ParameterRole {
    pub role: String,
    pub parameters: Vec<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum ChildRef {
    PartRef(String),
    MeshRef(String),
}

#[derive(Clone, Debug, PartialEq)]
pub struct Composite {
    pub blend: u32,
    pub alpha_blend: u32,
    pub masked_by: Vec<String>,
    pub masked_by_parts: Vec<String>,
    pub invert_mask: bool,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Clone, Debug, PartialEq)]
pub struct KeyAxis {
    pub parameter: String,
    pub keys: Vec<f32>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct KeyCell<F> {
    pub coordinate: Vec<i32>,
    pub form: F,
}

#[derive(Clone, Debug, PartialEq)]
pub struct KeyGrid<F> {
    pub axes: Vec<KeyAxis>,
    pub cells: Vec<KeyCell<F>>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum ChannelValue {
    Scalar(f32),
    Color(Rgb),
    Flag(bool),
}

pub type Channels = Vec<(u32, KeyGrid<ChannelValue>)>;

#[derive(Clone, Debug, PartialEq)]
pub struct BlendLimitPoint {
    pub value: f32,
    pub weight: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct BlendLimit {
    pub parameter: String,
    pub points: Vec<BlendLimitPoint>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct BlendBinding<S> {
    pub parameter: String,
    pub keys: Vec<f32>,
    pub neutral_index: i32,
    pub shapes: Vec<Option<S>>,
    pub limits: Vec<BlendLimit>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PartShape {
    pub draw_order: f32,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Part {
    pub id: String,
    pub name: String,
    pub children: Vec<ChildRef>,
    pub visible: bool,
    pub sketch: bool,
    pub selectable: bool,
    pub group_mode: u32,
    pub draw_order: i32,
    pub channels: Channels,
    pub composite: Composite,
    pub shapes: Vec<BlendBinding<PartShape>>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct LatticeShape {
    pub points: Vec<f32>,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Pivot {
    pub x: f32,
    pub y: f32,
    pub angle: f32,
    pub scale: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PivotShape {
    pub x: f32,
    pub y: f32,
    pub angle: f32,
    pub scale: f32,
    pub flip_x: bool,
    pub flip_y: bool,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Deformer {
    Warp {
        id: String,
        name: String,
        parent: Option<String>,
        part: Option<String>,
        rows: i32,
        columns: i32,
        bilinear: bool,
        lattice: Option<KeyGrid<Vec<f32>>>,
        channels: Channels,
        opacity: f32,
        multiply: Rgb,
        screen: Rgb,
        selectable: bool,
        visible: bool,
        enabled: bool,
        shapes: Vec<BlendBinding<LatticeShape>>,
    },
    Rotation {
        id: String,
        name: String,
        parent: Option<String>,
        part: Option<String>,
        base_angle: f32,
        pivot: Option<KeyGrid<Pivot>>,
        channels: Channels,
        opacity: f32,
        multiply: Rgb,
        screen: Rgb,
        flip_x: bool,
        flip_y: bool,
        selectable: bool,
        visible: bool,
        enabled: bool,
        shapes: Vec<BlendBinding<PivotShape>>,
        handle_length: Option<f32>,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct MeshGeometry {
    pub positions: Vec<f32>,
    pub uvs: Vec<f32>,
    pub indices: Vec<i32>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MeshShape {
    pub deltas: Vec<f32>,
    pub draw_order: f32,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Mesh {
    pub id: String,
    pub name: String,
    pub parent: Option<String>,
    pub blend: u32,
    pub alpha_blend: u32,
    pub masked_by: Vec<String>,
    pub invert_mask: bool,
    pub geometry: Option<MeshGeometry>,
    pub offsets: Option<KeyGrid<Vec<f32>>>,
    pub channels: Channels,
    pub draw_order: f32,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
    pub culling: bool,
    pub visible: bool,
    pub selectable: bool,
    pub texture_source: Option<String>,
    pub page: i32,
    pub tile: Option<String>,
    pub shapes: Vec<BlendBinding<MeshShape>>,
    pub user_data: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct GluePair {
    pub a: i32,
    pub b: i32,
    pub weight_a: f32,
    pub weight_b: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Glue {
    pub id: Option<String>,
    pub mesh_a: String,
    pub mesh_b: String,
    pub pairs: Vec<GluePair>,
    pub channels: Channels,
    pub intensity: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub enum RenderNode {
    Mesh(String),
    Group(RenderGroup),
}

#[derive(Clone, Debug, PartialEq)]
pub struct RenderGroup {
    pub part: Option<String>,
    pub draw_order: i32,
    pub children: Vec<RenderNode>,
    pub channels: Channels,
    pub composite: Option<Composite>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhysicsInput {
    pub parameter: String,
    pub weight: f32,
    pub source: u32,
    pub reflect: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhysicsOutput {
    pub parameter: String,
    pub vertex: i32,
    pub scale: f32,
    pub weight: f32,
    pub source: u32,
    pub reflect: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhysicsSegment {
    pub length: f32,
    pub mobility: f32,
    pub delay: f32,
    pub acceleration: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhysicsNormalization {
    pub position_min: f32,
    pub position_default: f32,
    pub position_max: f32,
    pub angle_min: f32,
    pub angle_default: f32,
    pub angle_max: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhysicsGroup {
    pub id: String,
    pub name: String,
    pub inputs: Vec<PhysicsInput>,
    pub outputs: Vec<PhysicsOutput>,
    pub segments: Vec<PhysicsSegment>,
    pub normalization: PhysicsNormalization,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Physics {
    pub groups: Vec<PhysicsGroup>,
    pub fps: Option<f32>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HitArea {
    pub id: String,
    pub name: String,
    pub meshes: Vec<String>,
}

/// Complete RigIR frame (everything except deformers/meshes when stored as a PRIO frame,
/// or including them when stored as a PRIR rig). Uninspected tail sections (textures, clips,
/// authoring, advanced) are preserved verbatim as raw bytes for zero-loss round-tripping.
#[derive(Clone, Debug, PartialEq)]
pub struct RigIR {
    pub canvas: Canvas,
    pub parameters: Vec<Parameter>,
    pub parameter_links: Vec<ParameterLink>,
    pub parameter_tree: Vec<ParameterNode>,
    pub parameter_roles: Vec<ParameterRole>,
    pub parts: Vec<Part>,
    pub root_children: Vec<ChildRef>,
    pub root_part: Option<String>,
    pub deformers: Vec<Deformer>,
    pub meshes: Vec<Mesh>,
    pub glues: Vec<Glue>,
    pub render_root: RenderGroup,
    pub textures_raw: Vec<u8>,
    pub physics: Physics,
    pub clips_and_tail_raw: Vec<u8>,
}

impl Default for RigIR {
    fn default() -> Self {
        Self {
            canvas: Canvas {
                width: 0.0,
                height: 0.0,
                origin_x: 0.0,
                origin_y: 0.0,
                pixels_per_unit: None,
            },
            parameters: Vec::new(),
            parameter_links: Vec::new(),
            parameter_tree: Vec::new(),
            parameter_roles: Vec::new(),
            parts: Vec::new(),
            root_children: Vec::new(),
            root_part: None,
            deformers: Vec::new(),
            meshes: Vec::new(),
            glues: Vec::new(),
            render_root: RenderGroup {
                part: None,
                draw_order: 0,
                children: Vec::new(),
                channels: Vec::new(),
                composite: None,
            },
            textures_raw: vec![0u8; 29],
            physics: Physics {
                groups: Vec::new(),
                fps: None,
            },
            clips_and_tail_raw: vec![0u8; 46],
        }
    }
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
    version: u32,
}

impl<'a> Reader<'a> {
    fn new(data: &'a [u8], version: u32) -> Self {
        Self { data, pos: 0, version }
    }

    fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.pos)
    }

    fn take(&mut self, n: usize) -> io::Result<&'a [u8]> {
        if self.remaining() < n {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Truncated encoded rig"));
        }
        let s = &self.data[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    fn int(&mut self) -> io::Result<i32> {
        let b = self.take(4)?;
        Ok(i32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn u32_val(&mut self) -> io::Result<u32> {
        let b = self.take(4)?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn float(&mut self) -> io::Result<f32> {
        let b = self.take(4)?;
        Ok(f32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn bool(&mut self) -> io::Result<bool> {
        Ok(self.take(1)?[0] != 0)
    }

    fn count(&mut self) -> io::Result<usize> {
        let n = self.int()?;
        if n < 0 || (n as usize) > self.remaining() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, format!("Invalid length {n}")));
        }
        Ok(n as usize)
    }

    fn string(&mut self) -> io::Result<String> {
        let n = self.count()?;
        let bytes = self.take(n)?;
        String::from_utf8(bytes.to_vec())
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
    }

    fn nullable<T>(&mut self, f: impl FnOnce(&mut Self) -> io::Result<T>) -> io::Result<Option<T>> {
        if self.bool()? {
            Ok(Some(f(self)?))
        } else {
            Ok(None)
        }
    }

    fn list<T>(&mut self, mut f: impl FnMut(&mut Self) -> io::Result<T>) -> io::Result<Vec<T>> {
        let n = self.count()?;
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(f(self)?);
        }
        Ok(out)
    }

    fn floats(&mut self) -> io::Result<Vec<f32>> {
        let n = self.count()?;
        if self.version < PLANAR_FLOATS_VERSION {
            let mut out = Vec::with_capacity(n);
            for _ in 0..n {
                out.push(self.float()?);
            }
            return Ok(out);
        }
        if n * 4 > self.remaining() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, format!("Invalid float length {n}")));
        }
        let planes = self.take(n * 4)?;
        let mut out = Vec::with_capacity(n);
        for i in 0..n {
            let bits = ((planes[i] as u32) << 24)
                | ((planes[n + i] as u32) << 16)
                | ((planes[2 * n + i] as u32) << 8)
                | (planes[3 * n + i] as u32);
            out.push(f32::from_bits(bits));
        }
        Ok(out)
    }

    fn ints(&mut self) -> io::Result<Vec<i32>> {
        self.list(|r| r.int())
    }

    fn bytes_vec(&mut self) -> io::Result<()> {
        let n = self.count()?;
        self.take(n)?;
        Ok(())
    }

    fn rgb(&mut self) -> io::Result<Rgb> {
        Ok(Rgb {
            r: self.float()?,
            g: self.float()?,
            b: self.float()?,
        })
    }

    fn tag(&mut self, max: u32) -> io::Result<u32> {
        let t = self.u32_val()?;
        if t > max {
            return Err(io::Error::new(io::ErrorKind::InvalidData, format!("Invalid tag {t}")));
        }
        Ok(t)
    }

    fn parameter_node(&mut self) -> io::Result<ParameterNode> {
        if self.tag(1)? == 0 {
            Ok(ParameterNode::Param(self.string()?))
        } else {
            let id = self.string()?;
            let name = self.string()?;
            let open = self.bool()?;
            let children = self.list(|r| r.parameter_node())?;
            let label = match self.tag(2)? {
                0 => GroupLabel::None,
                1 => GroupLabel::Preset(self.string()?),
                _ => GroupLabel::Custom(self.int()?),
            };
            Ok(ParameterNode::Group { id, name, open, children, label })
        }
    }

    fn child(&mut self) -> io::Result<ChildRef> {
        if self.tag(1)? == 0 {
            Ok(ChildRef::PartRef(self.string()?))
        } else {
            Ok(ChildRef::MeshRef(self.string()?))
        }
    }

    fn composite(&mut self) -> io::Result<Composite> {
        Ok(Composite {
            blend: self.u32_val()?,
            alpha_blend: self.u32_val()?,
            masked_by: self.list(|r| r.string())?,
            masked_by_parts: self.list(|r| r.string())?,
            invert_mask: self.bool()?,
            opacity: self.float()?,
            multiply: self.rgb()?,
            screen: self.rgb()?,
        })
    }

    fn grid<F>(&mut self, mut form: impl FnMut(&mut Self) -> io::Result<F>) -> io::Result<KeyGrid<F>> {
        let axes = self.list(|r| {
            Ok(KeyAxis {
                parameter: r.string()?,
                keys: r.floats()?,
            })
        })?;
        let cells = self.list(|r| {
            Ok(KeyCell {
                coordinate: r.ints()?,
                form: form(r)?,
            })
        })?;
        Ok(KeyGrid { axes, cells })
    }

    fn channels(&mut self) -> io::Result<Channels> {
        self.list(|r| {
            let channel = r.u32_val()?;
            let grid = r.grid(|r| match r.tag(2)? {
                0 => Ok(ChannelValue::Scalar(r.float()?)),
                1 => Ok(ChannelValue::Color(r.rgb()?)),
                _ => Ok(ChannelValue::Flag(r.bool()?)),
            })?;
            Ok((channel, grid))
        })
    }

    fn binding<S>(&mut self, mut shape: impl FnMut(&mut Self) -> io::Result<S>) -> io::Result<BlendBinding<S>> {
        let parameter = self.string()?;
        let keys = self.floats()?;
        let neutral_index = self.int()?;
        let shapes = self.list(|r| r.nullable(&mut shape))?;
        let limits = self.list(|r| {
            let parameter = r.string()?;
            let points = r.list(|r| {
                Ok(BlendLimitPoint {
                    value: r.float()?,
                    weight: r.float()?,
                })
            })?;
            Ok(BlendLimit { parameter, points })
        })?;
        Ok(BlendBinding { parameter, keys, neutral_index, shapes, limits })
    }

    fn deformer(&mut self) -> io::Result<Deformer> {
        if self.tag(1)? == 0 {
            Ok(Deformer::Warp {
                id: self.string()?,
                name: self.string()?,
                parent: self.nullable(|r| r.string())?,
                part: self.nullable(|r| r.string())?,
                rows: self.int()?,
                columns: self.int()?,
                bilinear: self.bool()?,
                lattice: self.nullable(|r| r.grid(|r| r.floats()))?,
                channels: self.channels()?,
                opacity: self.float()?,
                multiply: self.rgb()?,
                screen: self.rgb()?,
                selectable: self.bool()?,
                visible: self.bool()?,
                enabled: self.bool()?,
                shapes: self.list(|r| {
                    r.binding(|r| {
                        Ok(LatticeShape {
                            points: r.floats()?,
                            opacity: r.float()?,
                            multiply: r.rgb()?,
                            screen: r.rgb()?,
                        })
                    })
                })?,
            })
        } else {
            Ok(Deformer::Rotation {
                id: self.string()?,
                name: self.string()?,
                parent: self.nullable(|r| r.string())?,
                part: self.nullable(|r| r.string())?,
                base_angle: self.float()?,
                pivot: self.nullable(|r| {
                    r.grid(|r| {
                        Ok(Pivot {
                            x: r.float()?,
                            y: r.float()?,
                            angle: r.float()?,
                            scale: r.float()?,
                        })
                    })
                })?,
                channels: self.channels()?,
                opacity: self.float()?,
                multiply: self.rgb()?,
                screen: self.rgb()?,
                flip_x: self.bool()?,
                flip_y: self.bool()?,
                selectable: self.bool()?,
                visible: self.bool()?,
                enabled: self.bool()?,
                shapes: self.list(|r| {
                    r.binding(|r| {
                        Ok(PivotShape {
                            x: r.float()?,
                            y: r.float()?,
                            angle: r.float()?,
                            scale: r.float()?,
                            flip_x: r.bool()?,
                            flip_y: r.bool()?,
                            opacity: r.float()?,
                            multiply: r.rgb()?,
                            screen: r.rgb()?,
                        })
                    })
                })?,
                handle_length: self.nullable(|r| r.float())?,
            })
        }
    }

    fn mesh(&mut self) -> io::Result<Mesh> {
        Ok(Mesh {
            id: self.string()?,
            name: self.string()?,
            parent: self.nullable(|r| r.string())?,
            blend: self.u32_val()?,
            alpha_blend: self.u32_val()?,
            masked_by: self.list(|r| r.string())?,
            invert_mask: self.bool()?,
            geometry: self.nullable(|r| {
                Ok(MeshGeometry {
                    positions: r.floats()?,
                    uvs: r.floats()?,
                    indices: r.ints()?,
                })
            })?,
            offsets: self.nullable(|r| r.grid(|r| r.floats()))?,
            channels: self.channels()?,
            draw_order: self.float()?,
            opacity: self.float()?,
            multiply: self.rgb()?,
            screen: self.rgb()?,
            culling: self.bool()?,
            visible: self.bool()?,
            selectable: self.bool()?,
            texture_source: self.nullable(|r| r.string())?,
            page: self.int()?,
            tile: self.nullable(|r| r.string())?,
            shapes: self.list(|r| {
                r.binding(|r| {
                    Ok(MeshShape {
                        deltas: r.floats()?,
                        draw_order: r.float()?,
                        opacity: r.float()?,
                        multiply: r.rgb()?,
                        screen: r.rgb()?,
                    })
                })
            })?,
            user_data: self.string()?,
        })
    }

    fn render_group(&mut self) -> io::Result<RenderGroup> {
        let part = self.nullable(|r| r.string())?;
        let draw_order = self.int()?;
        let children = self.list(|r| {
            if r.tag(1)? == 0 {
                Ok(RenderNode::Mesh(r.string()?))
            } else {
                Ok(RenderNode::Group(r.render_group()?))
            }
        })?;
        let channels = self.channels()?;
        let composite = self.nullable(|r| r.composite())?;
        Ok(RenderGroup { part, draw_order, children, channels, composite })
    }

    fn skip_textures(&mut self) -> io::Result<Vec<u8>> {
        let start = self.pos;
        let page_count = self.count()?;
        for _ in 0..page_count {
            self.int()?;
            self.int()?;
            self.bytes_vec()?;
        }
        let bind_count = self.count()?;
        for _ in 0..bind_count {
            let n = self.count()?;
            self.take(n)?;
            self.int()?;
        }
        let tp_count = self.count()?;
        for _ in 0..tp_count {
            self.int()?;
            self.int()?;
        }
        let tile_count = self.count()?;
        for _ in 0..tile_count {
            let n1 = self.count()?; self.take(n1)?;
            let n2 = self.count()?; self.take(n2)?;
            self.int()?;
            self.int()?;
            if self.bool()? {
                self.int()?; self.float()?; self.float()?; self.float()?; self.float()?; self.float()?;
            }
            if self.bool()? {
                let s1 = self.count()?; self.take(s1)?;
                let s2 = self.count()?; self.take(s2)?;
                self.bool()?;
            }
            self.bool()?;
            if self.bool()? {
                let r = self.count()?; self.take(r)?;
            }
        }
        self.bool()?;
        self.int()?;
        self.int()?;
        let art_count = self.count()?;
        for _ in 0..art_count {
            let n = self.count()?; self.take(n)?;
            self.int()?;
            self.int()?;
            self.bytes_vec()?;
        }
        Ok(self.data[start..self.pos].to_vec())
    }

    fn physics(&mut self) -> io::Result<Physics> {
        let groups = self.list(|r| {
            Ok(PhysicsGroup {
                id: r.string()?,
                name: r.string()?,
                inputs: r.list(|r| {
                    Ok(PhysicsInput {
                        parameter: r.string()?,
                        weight: r.float()?,
                        source: r.u32_val()?,
                        reflect: r.bool()?,
                    })
                })?,
                outputs: r.list(|r| {
                    Ok(PhysicsOutput {
                        parameter: r.string()?,
                        vertex: r.int()?,
                        scale: r.float()?,
                        weight: r.float()?,
                        source: r.u32_val()?,
                        reflect: r.bool()?,
                    })
                })?,
                segments: r.list(|r| {
                    Ok(PhysicsSegment {
                        length: r.float()?,
                        mobility: r.float()?,
                        delay: r.float()?,
                        acceleration: r.float()?,
                    })
                })?,
                normalization: PhysicsNormalization {
                    position_min: r.float()?,
                    position_default: r.float()?,
                    position_max: r.float()?,
                    angle_min: r.float()?,
                    angle_default: r.float()?,
                    angle_max: r.float()?,
                },
            })
        })?;
        let fps = self.nullable(|r| r.float())?;
        Ok(Physics { groups, fps })
    }

    fn rig(&mut self) -> io::Result<RigIR> {
        let canvas = Canvas {
            width: self.float()?,
            height: self.float()?,
            origin_x: self.float()?,
            origin_y: self.float()?,
            pixels_per_unit: self.nullable(|r| r.float())?,
        };
        let parameters = self.list(|r| {
            Ok(Parameter {
                id: r.string()?,
                name: r.string()?,
                min: r.float()?,
                max: r.float()?,
                default: r.float()?,
                blend: r.bool()?,
                repeat: r.bool()?,
                keys: r.nullable(|r| r.floats())?,
            })
        })?;
        let parameter_links = self.list(|r| {
            Ok(ParameterLink {
                horizontal: r.string()?,
                vertical: r.string()?,
            })
        })?;
        let parameter_tree = self.list(|r| r.parameter_node())?;
        let parameter_roles = self.list(|r| {
            Ok(ParameterRole {
                role: r.string()?,
                parameters: r.list(|r| r.string())?,
            })
        })?;
        let parts = self.list(|r| {
            Ok(Part {
                id: r.string()?,
                name: r.string()?,
                children: r.list(|r| r.child())?,
                visible: r.bool()?,
                sketch: r.bool()?,
                selectable: r.bool()?,
                group_mode: r.u32_val()?,
                draw_order: r.int()?,
                channels: r.channels()?,
                composite: r.composite()?,
                shapes: r.list(|r| {
                    r.binding(|r| {
                        Ok(PartShape {
                            draw_order: r.float()?,
                            opacity: r.float()?,
                            multiply: r.rgb()?,
                            screen: r.rgb()?,
                        })
                    })
                })?,
            })
        })?;
        let root_children = self.list(|r| r.child())?;
        let root_part = self.nullable(|r| r.string())?;
        let deformers = self.list(|r| r.deformer())?;
        let meshes = self.list(|r| r.mesh())?;
        let glues = self.list(|r| {
            Ok(Glue {
                id: r.nullable(|r| r.string())?,
                mesh_a: r.string()?,
                mesh_b: r.string()?,
                pairs: r.list(|r| {
                    Ok(GluePair {
                        a: r.int()?,
                        b: r.int()?,
                        weight_a: r.float()?,
                        weight_b: r.float()?,
                    })
                })?,
                channels: r.channels()?,
                intensity: r.float()?,
            })
        })?;
        let render_root = self.render_group()?;
        let textures_raw = self.skip_textures()?;
        let physics = self.physics()?;
        let clips_and_tail_raw = self.data[self.pos..].to_vec();
        self.pos = self.data.len();
        let _ = HIT_AREAS_VERSION;
        Ok(RigIR {
            canvas,
            parameters,
            parameter_links,
            parameter_tree,
            parameter_roles,
            parts,
            root_children,
            root_part,
            deformers,
            meshes,
            glues,
            render_root,
            textures_raw,
            physics,
            clips_and_tail_raw,
        })
    }
}

struct Writer {
    out: Vec<u8>,
}

impl Writer {
    fn new() -> Self {
        Self { out: Vec::with_capacity(4096) }
    }

    fn int(&mut self, v: i32) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }

    fn u32_val(&mut self, v: u32) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }

    fn float(&mut self, v: f32) {
        self.out.extend_from_slice(&v.to_bits().to_be_bytes());
    }

    fn bool(&mut self, v: bool) {
        self.out.push(if v { 1 } else { 0 });
    }

    fn string(&mut self, s: &str) {
        let bytes = s.as_bytes();
        self.int(bytes.len() as i32);
        self.out.extend_from_slice(bytes);
    }

    fn nullable<T>(&mut self, v: Option<&T>, f: impl FnOnce(&mut Self, &T)) {
        self.bool(v.is_some());
        if let Some(inner) = v {
            f(self, inner);
        }
    }

    fn list<T>(&mut self, values: &[T], mut f: impl FnMut(&mut Self, &T)) {
        self.int(values.len() as i32);
        for v in values {
            f(self, v);
        }
    }

    fn floats(&mut self, values: &[f32]) {
        let n = values.len();
        self.int(n as i32);
        let start = self.out.len();
        self.out.resize(start + n * 4, 0);
        for (i, &v) in values.iter().enumerate() {
            let bits = v.to_bits();
            self.out[start + i] = (bits >> 24) as u8;
            self.out[start + n + i] = (bits >> 16) as u8;
            self.out[start + 2 * n + i] = (bits >> 8) as u8;
            self.out[start + 3 * n + i] = bits as u8;
        }
    }

    fn ints(&mut self, values: &[i32]) {
        self.list(values, |w, &v| w.int(v));
    }

    fn rgb(&mut self, c: &Rgb) {
        self.float(c.r);
        self.float(c.g);
        self.float(c.b);
    }

    fn parameter_node(&mut self, node: &ParameterNode) {
        match node {
            ParameterNode::Param(id) => {
                self.u32_val(0);
                self.string(id);
            }
            ParameterNode::Group { id, name, open, children, label } => {
                self.u32_val(1);
                self.string(id);
                self.string(name);
                self.bool(*open);
                self.list(children, |w, c| w.parameter_node(c));
                match label {
                    GroupLabel::None => self.u32_val(0),
                    GroupLabel::Preset(s) => {
                        self.u32_val(1);
                        self.string(s);
                    }
                    GroupLabel::Custom(argb) => {
                        self.u32_val(2);
                        self.int(*argb);
                    }
                }
            }
        }
    }

    fn child(&mut self, c: &ChildRef) {
        match c {
            ChildRef::PartRef(id) => {
                self.u32_val(0);
                self.string(id);
            }
            ChildRef::MeshRef(id) => {
                self.u32_val(1);
                self.string(id);
            }
        }
    }

    fn composite(&mut self, c: &Composite) {
        self.u32_val(c.blend);
        self.u32_val(c.alpha_blend);
        self.list(&c.masked_by, |w, s| w.string(s));
        self.list(&c.masked_by_parts, |w, s| w.string(s));
        self.bool(c.invert_mask);
        self.float(c.opacity);
        self.rgb(&c.multiply);
        self.rgb(&c.screen);
    }

    fn grid<F>(&mut self, g: &KeyGrid<F>, mut form: impl FnMut(&mut Self, &F)) {
        self.list(&g.axes, |w, a| {
            w.string(&a.parameter);
            w.floats(&a.keys);
        });
        self.list(&g.cells, |w, c| {
            w.ints(&c.coordinate);
            form(w, &c.form);
        });
    }

    fn channels(&mut self, ch: &Channels) {
        self.list(ch, |w, (channel, grid)| {
            w.u32_val(*channel);
            w.grid(grid, |w, val| match val {
                ChannelValue::Scalar(v) => {
                    w.u32_val(0);
                    w.float(*v);
                }
                ChannelValue::Color(c) => {
                    w.u32_val(1);
                    w.rgb(c);
                }
                ChannelValue::Flag(b) => {
                    w.u32_val(2);
                    w.bool(*b);
                }
            });
        });
    }

    fn binding<S>(&mut self, b: &BlendBinding<S>, mut shape: impl FnMut(&mut Self, &S)) {
        self.string(&b.parameter);
        self.floats(&b.keys);
        self.int(b.neutral_index);
        self.list(&b.shapes, |w, s| w.nullable(s.as_ref(), &mut shape));
        self.list(&b.limits, |w, l| {
            w.string(&l.parameter);
            w.list(&l.points, |w, p| {
                w.float(p.value);
                w.float(p.weight);
            });
        });
    }

    fn deformer(&mut self, d: &Deformer) {
        match d {
            Deformer::Warp {
                id, name, parent, part, rows, columns, bilinear, lattice,
                channels, opacity, multiply, screen, selectable, visible, enabled, shapes,
            } => {
                self.u32_val(0);
                self.string(id);
                self.string(name);
                self.nullable(parent.as_ref(), |w, s| w.string(s));
                self.nullable(part.as_ref(), |w, s| w.string(s));
                self.int(*rows);
                self.int(*columns);
                self.bool(*bilinear);
                self.nullable(lattice.as_ref(), |w, g| w.grid(g, |w, pts| w.floats(pts)));
                self.channels(channels);
                self.float(*opacity);
                self.rgb(multiply);
                self.rgb(screen);
                self.bool(*selectable);
                self.bool(*visible);
                self.bool(*enabled);
                self.list(shapes, |w, b| {
                    w.binding(b, |w, s| {
                        w.floats(&s.points);
                        w.float(s.opacity);
                        w.rgb(&s.multiply);
                        w.rgb(&s.screen);
                    });
                });
            }
            Deformer::Rotation {
                id, name, parent, part, base_angle, pivot, channels, opacity, multiply, screen,
                flip_x, flip_y, selectable, visible, enabled, shapes, handle_length,
            } => {
                self.u32_val(1);
                self.string(id);
                self.string(name);
                self.nullable(parent.as_ref(), |w, s| w.string(s));
                self.nullable(part.as_ref(), |w, s| w.string(s));
                self.float(*base_angle);
                self.nullable(pivot.as_ref(), |w, g| {
                    w.grid(g, |w, p| {
                        w.float(p.x);
                        w.float(p.y);
                        w.float(p.angle);
                        w.float(p.scale);
                    });
                });
                self.channels(channels);
                self.float(*opacity);
                self.rgb(multiply);
                self.rgb(screen);
                self.bool(*flip_x);
                self.bool(*flip_y);
                self.bool(*selectable);
                self.bool(*visible);
                self.bool(*enabled);
                self.list(shapes, |w, b| {
                    w.binding(b, |w, s| {
                        w.float(s.x);
                        w.float(s.y);
                        w.float(s.angle);
                        w.float(s.scale);
                        w.bool(s.flip_x);
                        w.bool(s.flip_y);
                        w.float(s.opacity);
                        w.rgb(&s.multiply);
                        w.rgb(&s.screen);
                    });
                });
                self.nullable(handle_length.as_ref(), |w, &v| w.float(v));
            }
        }
    }

    fn mesh(&mut self, m: &Mesh) {
        self.string(&m.id);
        self.string(&m.name);
        self.nullable(m.parent.as_ref(), |w, s| w.string(s));
        self.u32_val(m.blend);
        self.u32_val(m.alpha_blend);
        self.list(&m.masked_by, |w, s| w.string(s));
        self.bool(m.invert_mask);
        self.nullable(m.geometry.as_ref(), |w, g| {
            w.floats(&g.positions);
            w.floats(&g.uvs);
            w.ints(&g.indices);
        });
        self.nullable(m.offsets.as_ref(), |w, g| w.grid(g, |w, pts| w.floats(pts)));
        self.channels(&m.channels);
        self.float(m.draw_order);
        self.float(m.opacity);
        self.rgb(&m.multiply);
        self.rgb(&m.screen);
        self.bool(m.culling);
        self.bool(m.visible);
        self.bool(m.selectable);
        self.nullable(m.texture_source.as_ref(), |w, s| w.string(s));
        self.int(m.page);
        self.nullable(m.tile.as_ref(), |w, s| w.string(s));
        self.list(&m.shapes, |w, b| {
            w.binding(b, |w, s| {
                w.floats(&s.deltas);
                w.float(s.draw_order);
                w.float(s.opacity);
                w.rgb(&s.multiply);
                w.rgb(&s.screen);
            });
        });
        self.string(&m.user_data);
    }

    fn render_group(&mut self, g: &RenderGroup) {
        self.nullable(g.part.as_ref(), |w, s| w.string(s));
        self.int(g.draw_order);
        self.list(&g.children, |w, c| match c {
            RenderNode::Mesh(id) => {
                w.u32_val(0);
                w.string(id);
            }
            RenderNode::Group(sub) => {
                w.u32_val(1);
                w.render_group(sub);
            }
        });
        self.channels(&g.channels);
        self.nullable(g.composite.as_ref(), |w, c| w.composite(c));
    }

    fn physics(&mut self, p: &Physics) {
        self.list(&p.groups, |w, g| {
            w.string(&g.id);
            w.string(&g.name);
            w.list(&g.inputs, |w, pin| {
                w.string(&pin.parameter);
                w.float(pin.weight);
                w.u32_val(pin.source);
                w.bool(pin.reflect);
            });
            w.list(&g.outputs, |w, pout| {
                w.string(&pout.parameter);
                w.int(pout.vertex);
                w.float(pout.scale);
                w.float(pout.weight);
                w.u32_val(pout.source);
                w.bool(pout.reflect);
            });
            w.list(&g.segments, |w, seg| {
                w.float(seg.length);
                w.float(seg.mobility);
                w.float(seg.delay);
                w.float(seg.acceleration);
            });
            w.float(g.normalization.position_min);
            w.float(g.normalization.position_default);
            w.float(g.normalization.position_max);
            w.float(g.normalization.angle_min);
            w.float(g.normalization.angle_default);
            w.float(g.normalization.angle_max);
        });
        self.nullable(p.fps.as_ref(), |w, &v| w.float(v));
    }

    fn rig(&mut self, ir: &RigIR) {
        self.float(ir.canvas.width);
        self.float(ir.canvas.height);
        self.float(ir.canvas.origin_x);
        self.float(ir.canvas.origin_y);
        self.nullable(ir.canvas.pixels_per_unit.as_ref(), |w, &v| w.float(v));
        self.list(&ir.parameters, |w, p| {
            w.string(&p.id);
            w.string(&p.name);
            w.float(p.min);
            w.float(p.max);
            w.float(p.default);
            w.bool(p.blend);
            w.bool(p.repeat);
            w.nullable(p.keys.as_ref(), |w, k| w.floats(k));
        });
        self.list(&ir.parameter_links, |w, l| {
            w.string(&l.horizontal);
            w.string(&l.vertical);
        });
        self.list(&ir.parameter_tree, |w, n| w.parameter_node(n));
        self.list(&ir.parameter_roles, |w, r| {
            w.string(&r.role);
            w.list(&r.parameters, |w, s| w.string(s));
        });
        self.list(&ir.parts, |w, p| {
            w.string(&p.id);
            w.string(&p.name);
            w.list(&p.children, |w, c| w.child(c));
            w.bool(p.visible);
            w.bool(p.sketch);
            w.bool(p.selectable);
            w.u32_val(p.group_mode);
            w.int(p.draw_order);
            w.channels(&p.channels);
            w.composite(&p.composite);
            w.list(&p.shapes, |w, b| {
                w.binding(b, |w, s| {
                    w.float(s.draw_order);
                    w.float(s.opacity);
                    w.rgb(&s.multiply);
                    w.rgb(&s.screen);
                });
            });
        });
        self.list(&ir.root_children, |w, c| w.child(c));
        self.nullable(ir.root_part.as_ref(), |w, s| w.string(s));
        self.list(&ir.deformers, |w, d| w.deformer(d));
        self.list(&ir.meshes, |w, m| w.mesh(m));
        self.list(&ir.glues, |w, g| {
            w.nullable(g.id.as_ref(), |w, s| w.string(s));
            w.string(&g.mesh_a);
            w.string(&g.mesh_b);
            w.list(&g.pairs, |w, p| {
                w.int(p.a);
                w.int(p.b);
                w.float(p.weight_a);
                w.float(p.weight_b);
            });
            w.channels(&g.channels);
            w.float(g.intensity);
        });
        self.render_group(&ir.render_root);
        self.out.extend_from_slice(&ir.textures_raw);
        self.physics(&ir.physics);
        self.out.extend_from_slice(&ir.clips_and_tail_raw);
    }
}

pub fn decode_rig_binary(bytes: &[u8]) -> io::Result<RigIR> {
    let mut r = Reader::new(bytes, RIG_BINARY_VERSION);
    let magic = r.u32_val()?;
    if magic != RIG_BINARY_MAGIC {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Not an encoded rig"));
    }
    let version = r.u32_val()?;
    if version != RIG_BINARY_VERSION {
        return Err(io::Error::new(io::ErrorKind::InvalidData, format!("Unsupported encoded rig version {version}")));
    }
    r.version = version;
    r.rig()
}

pub fn encode_rig_binary(ir: &RigIR) -> Vec<u8> {
    let mut w = Writer::new();
    w.u32_val(RIG_BINARY_MAGIC);
    w.u32_val(RIG_BINARY_VERSION);
    w.rig(ir);
    w.out
}

pub fn decode_object_frame(bytes: &[u8]) -> io::Result<RigIR> {
    let mut r = decode_object_header(bytes, 0)?;
    r.rig()
}

pub fn decode_object_deformer(bytes: &[u8]) -> io::Result<Deformer> {
    let mut r = decode_object_header(bytes, 1)?;
    let d = r.deformer()?;
    if r.remaining() != 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Trailing bytes after deformer object"));
    }
    Ok(d)
}

pub fn decode_object_mesh(bytes: &[u8]) -> io::Result<Mesh> {
    let mut r = decode_object_header(bytes, 2)?;
    let m = r.mesh()?;
    if r.remaining() != 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Trailing bytes after mesh object"));
    }
    Ok(m)
}

pub fn encode_object_frame(ir: &RigIR) -> Vec<u8> {
    let mut w = Writer::new();
    w.u32_val(RIG_OBJECTS_MAGIC);
    w.u32_val(RIG_BINARY_VERSION);
    w.u32_val(0);
    let frame = RigIR {
        deformers: Vec::new(),
        meshes: Vec::new(),
        ..ir.clone()
    };
    w.rig(&frame);
    w.out
}

pub fn encode_object_deformer(d: &Deformer) -> Vec<u8> {
    let mut w = Writer::new();
    w.u32_val(RIG_OBJECTS_MAGIC);
    w.u32_val(RIG_BINARY_VERSION);
    w.u32_val(1);
    w.deformer(d);
    w.out
}

pub fn encode_object_mesh(m: &Mesh) -> Vec<u8> {
    let mut w = Writer::new();
    w.u32_val(RIG_OBJECTS_MAGIC);
    w.u32_val(RIG_BINARY_VERSION);
    w.u32_val(2);
    w.mesh(m);
    w.out
}

fn decode_object_header<'a>(bytes: &'a [u8], expected_kind: u32) -> io::Result<Reader<'a>> {
    let mut r = Reader::new(bytes, RIG_BINARY_VERSION);
    if r.u32_val()? != RIG_OBJECTS_MAGIC {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Not a stored rig object"));
    }
    let version = r.u32_val()?;
    if !(RIG_OBJECTS_MIN_VERSION..=RIG_BINARY_VERSION).contains(&version) {
        return Err(io::Error::new(io::ErrorKind::InvalidData, format!("Unsupported stored rig object version {version}")));
    }
    if r.u32_val()? != expected_kind {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Unexpected stored rig object kind"));
    }
    r.version = version;
    Ok(r)
}

#[derive(Default)]
pub struct ObjectCache {
    objects: HashMap<String, Vec<u8>>,
}

impl ObjectCache {
    pub fn sync(&mut self, req: &Request) -> Result<(), String> {
        if req.header.get("reset").and_then(Json::as_bool) == Some(true) {
            self.objects.clear();
        }
        if let Some(removed) = req.header.get("removed").and_then(Json::as_arr) {
            for r in removed {
                if let Some(k) = r.as_str() {
                    self.objects.remove(k);
                }
            }
        }
        if let Some(changed) = req.header.get("changed").and_then(Json::as_obj) {
            for (k, v) in changed {
                let inline = v
                    .get("inline")
                    .and_then(Json::as_str)
                    .ok_or_else(|| format!("Missing inline blob ref for {k}"))?;
                let bytes = req
                    .blobs
                    .get(inline)
                    .ok_or_else(|| format!("Missing blob {inline} for {k}"))?;
                self.objects.insert(k.clone(), bytes.clone());
            }
        }
        Ok(())
    }

    pub fn get(&self, key: &str) -> Result<&[u8], String> {
        self.objects
            .get(key)
            .map(Vec::as_slice)
            .ok_or_else(|| format!("Missing cached rig object: {key}"))
    }
}

pub fn objects_response(
    changed_blobs: HashMap<String, Vec<u8>>,
    mesh_order: Vec<String>,
    deformer_order: Vec<String>,
) -> Response {
    let mut blobs_vec: Vec<(String, Vec<u8>)> = Vec::with_capacity(changed_blobs.len());
    let mut changed_json: Vec<(String, Json)> = Vec::with_capacity(changed_blobs.len());
    for (idx, (key, bytes)) in changed_blobs.into_iter().enumerate() {
        let bkey = format!("r{idx}");
        changed_json.push((
            key,
            Json::Obj(vec![
                ("inline".into(), Json::Str(bkey.clone())),
                ("bytes".into(), Json::Num(bytes.len() as f64)),
            ]),
        ));
        blobs_vec.push((bkey, bytes));
    }
    Response::Objects {
        objects: Json::Obj(vec![
            ("changed".into(), Json::Obj(changed_json)),
            ("removed".into(), Json::Arr(vec![])),
            ("mesh_order".into(), Json::Arr(mesh_order.into_iter().map(Json::Str).collect())),
            ("deformer_order".into(), Json::Arr(deformer_order.into_iter().map(Json::Str).collect())),
        ]),
        blobs: blobs_vec,
    }
}

pub fn ascii_stem(id: &str) -> String {
    let out: String = id
        .chars()
        .map(|ch| if ch.is_ascii_alphanumeric() || ch == '_' { ch } else { '_' })
        .collect();
    if out.is_empty() { "Edit".into() } else { out }
}

pub fn with_parameter_created(frame: &mut RigIR, id: &str, name: &str, blend: bool) {
    if frame.parameters.iter().any(|p| p.id == id) {
        return;
    }
    if frame.parameter_tree.is_empty() && !frame.parameters.is_empty() {
        frame.parameter_tree = frame
            .parameters
            .iter()
            .map(|p| ParameterNode::Param(p.id.clone()))
            .collect();
    }
    let param = if blend {
        Parameter {
            id: id.to_string(),
            name: name.to_string(),
            min: 0.0,
            max: 1.0,
            default: 0.0,
            blend: true,
            repeat: false,
            keys: Some(vec![0.0, 1.0]),
        }
    } else {
        Parameter {
            id: id.to_string(),
            name: name.to_string(),
            min: -1.0,
            max: 1.0,
            default: 0.0,
            blend: false,
            repeat: false,
            keys: None,
        }
    };
    frame.parameters.push(param);
    frame.parameter_tree.insert(0, ParameterNode::Param(id.to_string()));
}

fn collect_param_ids(nodes: &[ParameterNode], out: &mut Vec<String>) {
    for n in nodes {
        match n {
            ParameterNode::Param(id) => out.push(id.clone()),
            ParameterNode::Group { children, .. } => collect_param_ids(children, out),
        }
    }
}

pub fn sync_parameters_from_tree(frame: &mut RigIR) {
    if frame.parameter_tree.is_empty() {
        return;
    }
    let mut order = Vec::new();
    collect_param_ids(&frame.parameter_tree, &mut order);
    if order.is_empty() {
        return;
    }
    let original = std::mem::take(&mut frame.parameters);
    let mut by_id: HashMap<String, Parameter> = original
        .iter()
        .map(|p| (p.id.clone(), p.clone()))
        .collect();
    let mut next = Vec::with_capacity(original.len());
    let mut seen = HashSet::new();
    for id in &order {
        if let Some(p) = by_id.remove(id) {
            seen.insert(id.clone());
            next.push(p);
        }
    }
    for p in original {
        if !seen.contains(&p.id) {
            next.push(p);
        }
    }
    frame.parameters = next;
}

pub fn bind_bracket(keys: &[f32], value: f32) -> Option<(usize, f32)> {
    const EPS_KEY: f32 = 0.001;
    const EPS_SPAN: f32 = 0.0015;
    let count = keys.len();
    if count <= 1 {
        if count == 0 {
            return Some((0, 0.0));
        }
        return (value > keys[0] - EPS_KEY && value < keys[0] + EPS_KEY).then_some((0, 0.0));
    }
    if value < keys[0] - EPS_KEY || value >= keys[count - 1] + EPS_KEY {
        return None;
    }
    if value < keys[0] + EPS_KEY {
        return Some((0, 0.0));
    }
    let mut hi = 1usize;
    while hi < count && keys[hi] + EPS_KEY <= value {
        hi += 1;
    }
    if value <= keys[hi] - EPS_KEY {
        let span = keys[hi] - keys[hi - 1];
        let frac = if span >= EPS_SPAN { (value - keys[hi - 1]) / span } else { 0.0 };
        Some((hi - 1, frac))
    } else {
        Some((hi, 0.0))
    }
}

pub fn grid_corners<F>(grid: &KeyGrid<F>, defaults: &HashMap<String, f32>) -> Option<Vec<(usize, f32)>> {
    let mut strides = Vec::with_capacity(grid.axes.len());
    let mut s = 1usize;
    for a in &grid.axes {
        strides.push(s);
        s *= a.keys.len().max(1);
    }
    let mut corners = vec![(0usize, 1.0f32)];
    for (idx, axis) in grid.axes.iter().enumerate() {
        let v = defaults.get(&axis.parameter).copied().unwrap_or(0.0);
        let (kidx, frac) = bind_bracket(&axis.keys, v)?;
        let st = strides[idx];
        if frac == 0.0 {
            for (lin, _) in &mut corners {
                *lin += kidx * st;
            }
        } else {
            let mut next = Vec::with_capacity(corners.len() * 2);
            let w0 = 1.0 - frac;
            for (lin, w) in corners {
                next.push((lin + kidx * st, w * w0));
                next.push((lin + (kidx + 1) * st, w * frac));
            }
            corners = next;
        }
    }
    Some(corners)
}

pub fn linear_cell_map<F>(grid: &KeyGrid<F>) -> HashMap<usize, usize> {
    let mut strides = Vec::with_capacity(grid.axes.len());
    let mut s = 1usize;
    for a in &grid.axes {
        strides.push(s);
        s *= a.keys.len().max(1);
    }
    let mut map = HashMap::with_capacity(grid.cells.len());
    for (ci, cell) in grid.cells.iter().enumerate() {
        let mut lin = 0usize;
        for (ai, &c) in cell.coordinate.iter().enumerate() {
            lin += c as usize * strides[ai];
        }
        map.insert(lin, ci);
    }
    map
}

pub fn eval_scalar_channel(channels: &[(u32, KeyGrid<ChannelValue>)], channel: u32, fallback: f32, defaults: &HashMap<String, f32>) -> f32 {
    let Some((_, grid)) = channels.iter().find(|(ch, _)| *ch == channel) else {
        return fallback;
    };
    let Some(corners) = grid_corners(grid, defaults) else {
        return fallback;
    };
    let cmap = linear_cell_map(grid);
    let mut acc = 0.0f32;
    for (lin, w) in corners {
        if let Some(&ci) = cmap.get(&lin) {
            if let ChannelValue::Scalar(v) = grid.cells[ci].form {
                acc += w * v;
            }
        }
    }
    acc
}

pub fn eval_color_channel(channels: &[(u32, KeyGrid<ChannelValue>)], channel: u32, fallback: &Rgb, defaults: &HashMap<String, f32>) -> Rgb {
    let Some((_, grid)) = channels.iter().find(|(ch, _)| *ch == channel) else {
        return fallback.clone();
    };
    let Some(corners) = grid_corners(grid, defaults) else {
        return fallback.clone();
    };
    let cmap = linear_cell_map(grid);
    let (mut r, mut g, mut b) = (0.0f32, 0.0f32, 0.0f32);
    for (lin, w) in corners {
        if let Some(&ci) = cmap.get(&lin) {
            if let ChannelValue::Color(ref c) = grid.cells[ci].form {
                r += w * c.r;
                g += w * c.g;
                b += w * c.b;
            }
        }
    }
    Rgb { r, g, b }
}

pub fn eval_floats_grid(grid: &KeyGrid<Vec<f32>>, defaults: &HashMap<String, f32>, len: usize) -> Option<Vec<f32>> {
    let corners = grid_corners(grid, defaults)?;
    let cmap = linear_cell_map(grid);
    let mut out = vec![0.0f32; len];
    for (lin, w) in corners {
        if let Some(&ci) = cmap.get(&lin) {
            let src = &grid.cells[ci].form;
            for i in 0..len.min(src.len()) {
                out[i] += w * src[i];
            }
        }
    }
    Some(out)
}

pub const CHANNEL_DRAW_ORDER: u32 = 0;
pub const CHANNEL_OPACITY: u32 = 1;
pub const CHANNEL_MULTIPLY_COLOR: u32 = 2;
pub const CHANNEL_SCREEN_COLOR: u32 = 3;

pub fn eval_grid_scalar(grid: Option<&KeyGrid<ChannelValue>>, fallback: f32, pose: &HashMap<String, f32>) -> f32 {
    let Some(g) = grid else { return fallback };
    let Some(corners) = grid_corners(g, pose) else { return fallback };
    let cmap = linear_cell_map(g);
    let mut acc = 0.0f32;
    for (lin, w) in corners {
        if let Some(&ci) = cmap.get(&lin) {
            if let ChannelValue::Scalar(v) = g.cells[ci].form {
                acc += v * w;
            }
        }
    }
    acc
}

pub fn eval_grid_color(grid: Option<&KeyGrid<ChannelValue>>, fallback: &Rgb, pose: &HashMap<String, f32>) -> Rgb {
    let Some(g) = grid else { return fallback.clone() };
    let Some(corners) = grid_corners(g, pose) else { return fallback.clone() };
    let cmap = linear_cell_map(g);
    let (mut r, mut gr, mut b) = (0.0f32, 0.0f32, 0.0f32);
    for (lin, w) in corners {
        if let Some(&ci) = cmap.get(&lin) {
            if let ChannelValue::Color(ref c) = g.cells[ci].form {
                r += c.r * w;
                gr += c.g * w;
                b += c.b * w;
            }
        }
    }
    Rgb { r, g: gr, b }
}

pub fn eval_grid_flag(grid: Option<&KeyGrid<ChannelValue>>, fallback: bool, pose: &HashMap<String, f32>) -> bool {
    let Some(g) = grid else { return fallback };
    let Some(corners) = grid_corners(g, pose) else { return fallback };
    let cmap = linear_cell_map(g);
    let mut best_w = -1.0f32;
    let mut best = fallback;
    for (lin, w) in corners {
        if w > best_w {
            if let Some(&ci) = cmap.get(&lin) {
                if let ChannelValue::Flag(b) = g.cells[ci].form {
                    best_w = w;
                    best = b;
                }
            }
        }
    }
    best
}

pub fn union_channel_axes(grids: [Option<&KeyGrid<ChannelValue>>; 3], live: &HashMap<String, f32>) -> Vec<KeyAxis> {
    const EPS_KEY: f32 = 0.001;
    let mut order: Vec<String> = Vec::new();
    let mut by_param: HashMap<String, Vec<f32>> = HashMap::new();
    for g in grids.into_iter().flatten() {
        for axis in &g.axes {
            if !live.contains_key(&axis.parameter) {
                continue;
            }
            let entry = by_param.entry(axis.parameter.clone()).or_insert_with(|| {
                order.push(axis.parameter.clone());
                Vec::new()
            });
            for &k in &axis.keys {
                if entry.iter().all(|&e| (e - k).abs() >= EPS_KEY) {
                    entry.push(k);
                }
            }
        }
    }
    order
        .into_iter()
        .map(|p| {
            let mut keys = by_param.remove(&p).unwrap_or_default();
            keys.sort_by(f32::total_cmp);
            KeyAxis { parameter: p, keys }
        })
        .collect()
}

fn residual_pose(
    grid: Option<&KeyGrid<ChannelValue>>,
    previous: Option<&KeyGrid<ChannelValue>>,
    base_pose: &HashMap<String, f32>,
    live: &HashMap<String, f32>,
) -> HashMap<String, f32> {
    let mut pose = HashMap::with_capacity(live.len());
    let prev_params: HashSet<&str> = previous
        .map(|g| g.axes.iter().map(|a| a.parameter.as_str()).collect())
        .unwrap_or_default();
    let grid_axes: HashMap<&str, &[f32]> = grid
        .map(|g| g.axes.iter().map(|a| (a.parameter.as_str(), a.keys.as_slice())).collect())
        .unwrap_or_default();
    for (id, &def) in live {
        let mut v = base_pose.get(id).copied().unwrap_or(def);
        if prev_params.contains(id.as_str()) {
            if let Some(keys) = grid_axes.get(id.as_str()) {
                if let (Some(&lo), Some(&hi)) = (keys.first(), keys.last()) {
                    v = v.clamp(lo, hi);
                }
            }
        }
        pose.insert(id.clone(), v);
    }
    pose
}

#[allow(clippy::too_many_arguments)]
pub fn residual_channel_grid(
    live: &HashMap<String, f32>,
    actual_grid: Option<&KeyGrid<ChannelValue>>,
    actual_static: &ChannelValue,
    prev_grid: Option<&KeyGrid<ChannelValue>>,
    prev_static: &ChannelValue,
    des_grid: Option<&KeyGrid<ChannelValue>>,
    des_static: &ChannelValue,
) -> KeyGrid<ChannelValue> {
    let axes = union_channel_axes([actual_grid, prev_grid, des_grid], live);
    let mut total = 1usize;
    for a in &axes {
        total *= a.keys.len().max(1);
    }
    let mut cells = Vec::with_capacity(total);
    for index in 0..total {
        let mut rem = index;
        let mut coord = Vec::with_capacity(axes.len());
        let mut base_pose = HashMap::with_capacity(axes.len());
        for a in &axes {
            let c = rem % a.keys.len();
            rem /= a.keys.len();
            coord.push(c as i32);
            base_pose.insert(a.parameter.clone(), a.keys[c]);
        }
        let pose_a = residual_pose(actual_grid, prev_grid, &base_pose, live);
        let pose_p = residual_pose(prev_grid, prev_grid, &base_pose, live);
        let pose_d = residual_pose(des_grid, prev_grid, &base_pose, live);

        let form = match (actual_static, prev_static, des_static) {
            (ChannelValue::Scalar(sa), ChannelValue::Scalar(sp), ChannelValue::Scalar(sd)) => {
                let va = eval_grid_scalar(actual_grid, *sa, &pose_a);
                let vp = eval_grid_scalar(prev_grid, *sp, &pose_p);
                let vd = eval_grid_scalar(des_grid, *sd, &pose_d);
                ChannelValue::Scalar(va - vp + vd)
            }
            (ChannelValue::Color(ca), ChannelValue::Color(cp), ChannelValue::Color(cd)) => {
                let va = eval_grid_color(actual_grid, ca, &pose_a);
                let vp = eval_grid_color(prev_grid, cp, &pose_p);
                let vd = eval_grid_color(des_grid, cd, &pose_d);
                ChannelValue::Color(Rgb {
                    r: va.r - vp.r + vd.r,
                    g: va.g - vp.g + vd.g,
                    b: va.b - vp.b + vd.b,
                })
            }
            (ChannelValue::Flag(fa), ChannelValue::Flag(fp), ChannelValue::Flag(fd)) => {
                let va = eval_grid_flag(actual_grid, *fa, &pose_a);
                let vp = eval_grid_flag(prev_grid, *fp, &pose_p);
                let vd = eval_grid_flag(des_grid, *fd, &pose_d);
                ChannelValue::Flag(if va != vp { va } else { vd })
            }
            _ => actual_static.clone(),
        };
        cells.push(KeyCell { coordinate: coord, form });
    }
    KeyGrid { axes, cells }
}

pub fn channel_static(mesh: &Mesh, ch: u32) -> ChannelValue {
    match ch {
        CHANNEL_OPACITY => ChannelValue::Scalar(mesh.opacity),
        CHANNEL_DRAW_ORDER => ChannelValue::Scalar(mesh.draw_order as f32),
        CHANNEL_MULTIPLY_COLOR => ChannelValue::Color(mesh.multiply.clone()),
        CHANNEL_SCREEN_COLOR => ChannelValue::Color(mesh.screen.clone()),
        _ => ChannelValue::Flag(false),
    }
}

pub fn grid_ref(channels: &[(u32, KeyGrid<ChannelValue>)], ch: u32) -> Option<&KeyGrid<ChannelValue>> {
    channels.iter().find(|(c, _)| *c == ch).map(|(_, g)| g)
}

pub fn set_channel(channels: &mut Vec<(u32, KeyGrid<ChannelValue>)>, ch: u32, grid: Option<KeyGrid<ChannelValue>>) {
    channels.retain(|(c, _)| *c != ch);
    if let Some(g) = grid {
        channels.push((ch, g));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deformer_and_mesh_objects_roundtrip() {
        let warp = Deformer::Warp {
            id: "w1".into(),
            name: "Warp 1".into(),
            parent: Some("p0".into()),
            part: Some("part0".into()),
            rows: 2,
            columns: 2,
            bilinear: true,
            lattice: Some(KeyGrid {
                axes: vec![KeyAxis { parameter: "ParamA".into(), keys: vec![0.0, 1.0] }],
                cells: vec![
                    KeyCell { coordinate: vec![0], form: vec![0.0, 0.0, 1.0, 1.0] },
                    KeyCell { coordinate: vec![1], form: vec![0.5, 0.5, 1.5, 1.5] },
                ],
            }),
            channels: vec![],
            opacity: 1.0,
            multiply: Rgb { r: 1.0, g: 1.0, b: 1.0 },
            screen: Rgb { r: 0.0, g: 0.0, b: 0.0 },
            selectable: true,
            visible: true,
            enabled: true,
            shapes: vec![],
        };
        let encoded = encode_object_deformer(&warp);
        let decoded = decode_object_deformer(&encoded).expect("decode deformer");
        assert_eq!(warp, decoded);

        let mesh = Mesh {
            id: "m1".into(),
            name: "Mesh 1".into(),
            parent: Some("w1".into()),
            blend: 0,
            alpha_blend: 0,
            masked_by: vec!["mask0".into()],
            invert_mask: false,
            geometry: Some(MeshGeometry {
                positions: vec![0.0, 0.0, 10.0, 0.0, 10.0, 10.0],
                uvs: vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0],
                indices: vec![0, 1, 2],
            }),
            offsets: None,
            channels: vec![],
            draw_order: 500.0,
            opacity: 1.0,
            multiply: Rgb { r: 1.0, g: 1.0, b: 1.0 },
            screen: Rgb { r: 0.0, g: 0.0, b: 0.0 },
            culling: false,
            visible: true,
            selectable: true,
            texture_source: None,
            page: 0,
            tile: None,
            shapes: vec![],
            user_data: String::new(),
        };
        let m_enc = encode_object_mesh(&mesh);
        let m_dec = decode_object_mesh(&m_enc).expect("decode mesh");
        assert_eq!(mesh, m_dec);
    }
}
