use std::collections::HashMap;
use std::env;
use std::fs;
use std::io::{self, BufRead, BufReader, BufWriter, Read, Write};
use std::path::PathBuf;

#[derive(Clone, Debug, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
}

impl Json {
    pub fn get(&self, key: &str) -> Option<&Json> {
        match self {
            Json::Obj(entries) => entries.iter().rev().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Json::Str(s) => Some(s),
            _ => None,
        }
    }
    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Json::Num(n) => Some(*n),
            _ => None,
        }
    }
    pub fn as_i64(&self) -> Option<i64> {
        self.as_f64().map(|n| n as i64)
    }
    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Json::Bool(b) => Some(*b),
            _ => None,
        }
    }
    pub fn as_arr(&self) -> Option<&[Json]> {
        match self {
            Json::Arr(a) => Some(a),
            _ => None,
        }
    }
    pub fn as_obj(&self) -> Option<&[(String, Json)]> {
        match self {
            Json::Obj(o) => Some(o),
            _ => None,
        }
    }

    pub fn parse(input: &str) -> Result<Json, String> {
        let mut p = Parser { bytes: input.as_bytes(), pos: 0 };
        p.skip_ws();
        let val = p.value()?;
        p.skip_ws();
        if p.pos != p.bytes.len() {
            return Err("Trailing characters in JSON".into());
        }
        Ok(val)
    }

    pub fn to_string_compact(&self) -> String {
        let mut out = String::new();
        self.write_to(&mut out);
        out
    }

    fn write_to(&self, out: &mut String) {
        match self {
            Json::Null => out.push_str("null"),
            Json::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            Json::Num(n) => {
                if !n.is_finite() {
                    out.push_str("null");
                } else if *n == 0.0 && n.is_sign_negative() {
                    out.push_str("-0.0");
                } else if n.fract() == 0.0 && *n >= (i64::MIN as f64) && *n <= (i64::MAX as f64) {
                    out.push_str(&(*n as i64).to_string());
                } else {
                    out.push_str(&n.to_string());
                }
            }
            Json::Str(s) => write_escaped_str(s, out),
            Json::Arr(arr) => {
                out.push('[');
                for (i, v) in arr.iter().enumerate() {
                    if i > 0 { out.push(','); }
                    v.write_to(out);
                }
                out.push(']');
            }
            Json::Obj(entries) => {
                out.push('{');
                for (i, (k, v)) in entries.iter().enumerate() {
                    if i > 0 { out.push(','); }
                    write_escaped_str(k, out);
                    out.push(':');
                    v.write_to(out);
                }
                out.push('}');
            }
        }
    }
}

fn write_escaped_str(s: &str, out: &mut String) {
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                use std::fmt::Write;
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

struct Parser<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Parser<'a> {
    fn skip_ws(&mut self) {
        while self.pos < self.bytes.len() && matches!(self.bytes[self.pos], b' ' | b'\t' | b'\r' | b'\n') {
            self.pos += 1;
        }
    }
    fn peek(&self) -> Option<u8> { self.bytes.get(self.pos).copied() }
    fn next(&mut self) -> Result<u8, String> {
        let b = self.peek().ok_or_else(|| "Unexpected EOF".to_string())?;
        self.pos += 1;
        Ok(b)
    }
    fn value(&mut self) -> Result<Json, String> {
        self.skip_ws();
        match self.peek().ok_or_else(|| "Unexpected EOF".to_string())? {
            b'n' => { self.expect(b"null")?; Ok(Json::Null) }
            b't' => { self.expect(b"true")?; Ok(Json::Bool(true)) }
            b'f' => { self.expect(b"false")?; Ok(Json::Bool(false)) }
            b'"' => Ok(Json::Str(self.string()?)),
            b'[' => self.array(),
            b'{' => self.object(),
            b'-' | b'0'..=b'9' => self.number(),
            c => Err(format!("Unexpected byte in JSON: {}", c as char)),
        }
    }
    fn expect(&mut self, lit: &[u8]) -> Result<(), String> {
        if self.bytes.get(self.pos..self.pos + lit.len()) == Some(lit) {
            self.pos += lit.len();
            Ok(())
        } else {
            Err("Invalid JSON literal".into())
        }
    }
    fn string(&mut self) -> Result<String, String> {
        if self.next()? != b'"' { return Err("Expected '\"'".into()); }
        let mut out = String::new();
        loop {
            let b = self.next()?;
            if b == b'"' { return Ok(out); }
            if b == b'\\' {
                match self.next()? {
                    b'"' => out.push('"'),
                    b'\\' => out.push('\\'),
                    b'/' => out.push('/'),
                    b'b' => out.push('\x08'),
                    b'f' => out.push('\x0c'),
                    b'n' => out.push('\n'),
                    b'r' => out.push('\r'),
                    b't' => out.push('\t'),
                    b'u' => {
                        let hex = std::str::from_utf8(self.bytes.get(self.pos..self.pos + 4).ok_or("Invalid \\u")?)
                            .map_err(|_| "Invalid \\u")?;
                        self.pos += 4;
                        let code = u16::from_str_radix(hex, 16).map_err(|_| "Invalid \\u hex")?;
                        out.push(char::from_u32(code as u32).ok_or("Invalid unicode")?);
                    }
                    _ => return Err("Invalid escape".into()),
                }
            } else if b < 0x80 {
                out.push(b as char);
            } else {
                self.pos -= 1;
                let rest = std::str::from_utf8(&self.bytes[self.pos..]).map_err(|_| "Invalid UTF-8")?;
                let ch = rest.chars().next().ok_or("Unexpected EOF")?;
                out.push(ch);
                self.pos += ch.len_utf8();
            }
        }
    }
    fn number(&mut self) -> Result<Json, String> {
        let start = self.pos;
        if self.peek() == Some(b'-') { self.pos += 1; }
        while matches!(self.peek(), Some(b'0'..=b'9')) { self.pos += 1; }
        if self.peek() == Some(b'.') {
            self.pos += 1;
            while matches!(self.peek(), Some(b'0'..=b'9')) { self.pos += 1; }
        }
        if matches!(self.peek(), Some(b'e' | b'E')) {
            self.pos += 1;
            if matches!(self.peek(), Some(b'+' | b'-')) { self.pos += 1; }
            while matches!(self.peek(), Some(b'0'..=b'9')) { self.pos += 1; }
        }
        let s = std::str::from_utf8(&self.bytes[start..self.pos]).map_err(|_| "Invalid number")?;
        s.parse::<f64>().map(Json::Num).map_err(|_| "Invalid float".into())
    }
    fn array(&mut self) -> Result<Json, String> {
        self.pos += 1;
        let mut items = Vec::new();
        self.skip_ws();
        if self.peek() == Some(b']') { self.pos += 1; return Ok(Json::Arr(items)); }
        loop {
            items.push(self.value()?);
            self.skip_ws();
            match self.next()? {
                b',' => continue,
                b']' => return Ok(Json::Arr(items)),
                _ => return Err("Expected ',' or ']'".into()),
            }
        }
    }
    fn object(&mut self) -> Result<Json, String> {
        self.pos += 1;
        let mut entries = Vec::new();
        self.skip_ws();
        if self.peek() == Some(b'}') { self.pos += 1; return Ok(Json::Obj(entries)); }
        loop {
            self.skip_ws();
            let k = self.string()?;
            self.skip_ws();
            if self.next()? != b':' { return Err("Expected ':'".into()); }
            let v = self.value()?;
            entries.push((k, v));
            self.skip_ws();
            match self.next()? {
                b',' => continue,
                b'}' => return Ok(Json::Obj(entries)),
                _ => return Err("Expected ',' or '}'".into()),
            }
        }
    }
}

#[derive(Clone, Debug)]
pub struct DepEntry {
    pub id: String,
    pub version: String,
    pub digest: String,
    pub dir: PathBuf,
}

pub fn load_dependencies() -> Vec<DepEntry> {
    let Ok(path) = env::var("PSD2LIVE_PLUGIN_DEPS") else { return Vec::new(); };
    let Ok(text) = fs::read_to_string(path) else { return Vec::new(); };
    let Ok(Json::Obj(root)) = Json::parse(&text) else { return Vec::new(); };
    let Some(Json::Arr(items)) = root.into_iter().find(|(k, _)| k == "dependencies").map(|(_, v)| v) else {
        return Vec::new();
    };
    items.into_iter().filter_map(|item| {
        let id = item.get("id")?.as_str()?.to_string();
        let version = item.get("version")?.as_str()?.to_string();
        let digest = item.get("digest")?.as_str()?.to_string();
        let dir = PathBuf::from(item.get("dir")?.as_str()?);
        Some(DepEntry { id, version, digest, dir })
    }).collect()
}

pub struct Request {
    pub protocol: i64,
    pub id: i64,
    pub header: Json,
    pub blobs: HashMap<String, Vec<u8>>,
}

impl Request {
    pub fn algo_type(&self) -> &str { self.header.get("type").and_then(Json::as_str).unwrap_or("") }
    pub fn version(&self) -> i64 { self.header.get("version").and_then(Json::as_i64).unwrap_or(1) }
    pub fn config(&self) -> &Json { self.header.get("config").unwrap_or(&Json::Null) }
    pub fn targets(&self) -> &[Json] { self.header.get("targets").and_then(Json::as_arr).unwrap_or(&[]) }
    pub fn parameters(&self) -> &[Json] { self.header.get("parameters").and_then(Json::as_arr).unwrap_or(&[]) }
    pub fn clips(&self) -> &[Json] { self.header.get("clips").and_then(Json::as_arr).unwrap_or(&[]) }
    pub fn parameter_roles(&self) -> &[Json] { self.header.get("parameter_roles").and_then(Json::as_arr).unwrap_or(&[]) }
    pub fn mesh_order(&self) -> &[Json] { self.header.get("mesh_order").and_then(Json::as_arr).unwrap_or(&[]) }
    pub fn meshes(&self) -> &[Json] { self.header.get("meshes").and_then(Json::as_arr).unwrap_or(&[]) }

    pub fn context_values(&self) -> Option<Json> {
        let key = self.header.get("context")?.get("values")?.get("inline")?.as_str()?;
        let bytes = self.blobs.get(key)?;
        let text = std::str::from_utf8(bytes).ok()?;
        Json::parse(text).ok()
    }

    pub fn context_upstream(&self) -> Option<Json> {
        let key = self.header.get("context")?.get("upstream")?.get("inline")?.as_str()?;
        let bytes = self.blobs.get(key)?;
        let text = std::str::from_utf8(bytes).ok()?;
        Json::parse(text).ok()
    }

    pub fn context_resource(&self, name: &str) -> Option<&[u8]> {
        let key = self.header.get("context")?.get("resources")?.get(name)?.get("inline")?.as_str()?;
        self.blobs.get(key).map(|v| v.as_slice())
    }

    pub fn read_f32le(&self, ref_obj: &Json) -> Option<Vec<f32>> {
        let key = ref_obj.get("inline")?.as_str()?;
        let bytes = self.blobs.get(key)?;
        if bytes.len() % 4 != 0 { return None; }
        Some(bytes.chunks_exact(4).map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect())
    }
}

pub enum Response {
    Commands(Vec<Json>),
    Objects { objects: Json, blobs: Vec<(String, Vec<u8>)> },
    Error(String),
}

pub fn run<F>(mut handler: F) -> io::Result<()>
where
    F: FnMut(&Request) -> Response,
{
    let protocol = if env::var("PSD2LIVE_PLUGIN_PROTOCOL").as_deref() == Ok("2") { 2 } else { 1 };
    if protocol == 2 {
        let stdin = io::stdin();
        let stdout = io::stdout();
        let mut reader = BufReader::new(stdin.lock());
        let mut writer = BufWriter::new(stdout.lock());
        while let Some((header, blobs)) = read_frame(&mut reader)? {
            let id = header.get("id").and_then(Json::as_i64).unwrap_or(0);
            let req = Request { protocol: 2, id, header, blobs };
            let mut out_blobs: Vec<(String, Vec<u8>)> = Vec::new();
            let envelope = match handler(&req) {
                Response::Commands(cmds) => Json::Obj(vec![
                    ("protocol".into(), Json::Num(2.0)),
                    ("id".into(), Json::Num(id as f64)),
                    ("commands".into(), Json::Arr(cmds)),
                ]),
                Response::Objects { objects, blobs } => {
                    out_blobs = blobs;
                    Json::Obj(vec![
                        ("protocol".into(), Json::Num(2.0)),
                        ("id".into(), Json::Num(id as f64)),
                        ("objects".into(), objects),
                    ])
                }
                Response::Error(msg) => Json::Obj(vec![
                    ("protocol".into(), Json::Num(2.0)),
                    ("id".into(), Json::Num(id as f64)),
                    ("error".into(), Json::Str(msg)),
                ]),
            };
            let blob_refs: Vec<(&str, &[u8])> = out_blobs.iter().map(|(k, v)| (k.as_str(), v.as_slice())).collect();
            write_frame(&mut writer, &envelope, &blob_refs)?;
        }
    } else {
        let stdin = io::stdin();
        let stdout = io::stdout();
        let mut reader = BufReader::new(stdin.lock());
        let mut writer = BufWriter::new(stdout.lock());
        let mut line = String::new();
        loop {
            line.clear();
            if reader.read_line(&mut line)? == 0 { break; }
            let header = Json::parse(line.trim_end()).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
            let id = header.get("id").and_then(Json::as_i64).unwrap_or(0);
            let req = Request { protocol: 1, id, header, blobs: HashMap::new() };
            let envelope = match handler(&req) {
                Response::Commands(cmds) => Json::Obj(vec![
                    ("protocol".into(), Json::Num(1.0)),
                    ("id".into(), Json::Num(id as f64)),
                    ("commands".into(), Json::Arr(cmds)),
                ]),
                Response::Objects { .. } => Json::Obj(vec![
                    ("protocol".into(), Json::Num(1.0)),
                    ("id".into(), Json::Num(id as f64)),
                    ("error".into(), Json::Str("Objects response requires protocol 2".into())),
                ]),
                Response::Error(msg) => Json::Obj(vec![
                    ("protocol".into(), Json::Num(1.0)),
                    ("id".into(), Json::Num(id as f64)),
                    ("error".into(), Json::Str(msg)),
                ]),
            };
            writer.write_all(envelope.to_string_compact().as_bytes())?;
            writer.write_all(b"\n")?;
            writer.flush()?;
        }
    }
    Ok(())
}

fn read_u32_opt<R: Read>(r: &mut R) -> io::Result<Option<u32>> {
    let mut buf = [0u8; 4];
    match r.read(&mut buf[..1])? {
        0 => return Ok(None),
        1 => {}
        _ => unreachable!(),
    }
    r.read_exact(&mut buf[1..])?;
    Ok(Some(u32::from_le_bytes(buf)))
}

fn read_u32<R: Read>(r: &mut R) -> io::Result<u32> {
    let mut buf = [0u8; 4];
    r.read_exact(&mut buf)?;
    Ok(u32::from_le_bytes(buf))
}

fn read_frame<R: Read>(r: &mut R) -> io::Result<Option<(Json, HashMap<String, Vec<u8>>)>> {
    let Some(header_len) = read_u32_opt(r)? else { return Ok(None); };
    let mut header_bytes = vec![0u8; header_len as usize];
    r.read_exact(&mut header_bytes)?;
    let header_str = std::str::from_utf8(&header_bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let header = Json::parse(header_str).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let blob_count = read_u32(r)? as usize;
    let mut blobs = HashMap::with_capacity(blob_count);
    for _ in 0..blob_count {
        let key_len = read_u32(r)? as usize;
        let mut key_bytes = vec![0u8; key_len];
        r.read_exact(&mut key_bytes)?;
        let key = String::from_utf8(key_bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        let data_len = read_u32(r)? as usize;
        let mut data = vec![0u8; data_len];
        r.read_exact(&mut data)?;
        blobs.insert(key, data);
    }
    Ok(Some((header, blobs)))
}

fn write_frame<W: Write>(w: &mut W, header: &Json, blobs: &[(&str, &[u8])]) -> io::Result<()> {
    let header_str = header.to_string_compact();
    let header_bytes = header_str.as_bytes();
    w.write_all(&(header_bytes.len() as u32).to_le_bytes())?;
    w.write_all(header_bytes)?;
    w.write_all(&(blobs.len() as u32).to_le_bytes())?;
    for (key, data) in blobs {
        let key_bytes = key.as_bytes();
        w.write_all(&(key_bytes.len() as u32).to_le_bytes())?;
        w.write_all(key_bytes)?;
        w.write_all(&(data.len() as u32).to_le_bytes())?;
        w.write_all(data)?;
    }
    w.flush()
}
