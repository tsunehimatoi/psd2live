// psd2live-importer 8
// Imports a PSD2Live .p2lunity file as a prefab. Meshes combine only while they share a texture
// and sit next to each other in draw order, so a part on an earlier page can still render in
// front. The Animator default state is Idle when that clip exists, and the prefab uses the
// sibling .controller once that file exists. Requires Unity 6.0 (6000.0) or newer.
// Keep a single copy of this file under an Editor folder. MIT License.
using System;
using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.Animations;
using UnityEditor.AssetImporters;
using UnityEngine;
using UnityEngine.Rendering;

namespace PSD2Live.UnityExport
{
    [ScriptedImporter(8, "p2lunity")]
    public sealed class Psd2LiveUnityImporter : ScriptedImporter
    {
        public override void OnImportAsset(AssetImportContext ctx)
        {
            var document = JsonUtility.FromJson<Document>(File.ReadAllText(ctx.assetPath));
            if (document == null || document.version != 1) throw new InvalidOperationException("Unsupported .p2lunity version");
            if (document.meshes == null || document.meshes.Length == 0) throw new InvalidOperationException("The Unity export has no meshes");

            var textures = new Texture2D[document.textures == null ? 0 : document.textures.Length];
            for (var i = 0; i < textures.Length; i++)
            {
                var relative = document.textures[i].file;
                var path = Path.GetFullPath(Path.Combine(Path.GetDirectoryName(ctx.assetPath) ?? "", relative));
                ctx.DependsOnSourceAsset(ToAssetPath(ctx.assetPath, relative));
                var texture = LoadTexture(File.ReadAllBytes(path), Path.GetFileNameWithoutExtension(relative));
                textures[i] = texture;
                ctx.AddObjectToAsset("texture_" + i, texture);
            }

            var root = new GameObject(string.IsNullOrEmpty(document.name) ? "PSD2Live" : document.name);
            var bone = new GameObject("Root");
            bone.transform.SetParent(root.transform, false);
            var paths = new Dictionary<string, string>();
            var usedNames = new HashSet<string>();
            var groups = GroupContiguous(document.meshes);

            for (var groupIndex = 0; groupIndex < groups.Count; groupIndex++)
            {
                var group = groups[groupIndex];
                var nodeName = groups.Count == 1 && group.Count > 1
                    ? Unique("Art", usedNames)
                    : group.Count == 1
                        ? Unique(Safe(group[0].id), usedNames)
                        : Unique("Page" + group[0].texture, usedNames);
                foreach (var source in group) paths[source.id] = nodeName;

                var mesh = CombineMeshes(group);
                var material = CreateMaterial(nodeName, TextureOf(textures, group[0].texture), Color.white);
                ctx.AddObjectToAsset("mesh_" + nodeName, mesh);
                ctx.AddObjectToAsset("material_" + nodeName, material);

                var node = new GameObject(nodeName);
                node.transform.SetParent(root.transform, false);
                var renderer = node.AddComponent<SkinnedMeshRenderer>();
                renderer.sharedMesh = mesh;
                renderer.sharedMaterial = material;
                renderer.bones = new[] { bone.transform };
                renderer.rootBone = bone.transform;
                renderer.localBounds = mesh.bounds;
                renderer.updateWhenOffscreen = false;
                renderer.skinnedMotionVectors = false;
                renderer.quality = SkinQuality.Bone1;
                renderer.shadowCastingMode = ShadowCastingMode.Off;
                renderer.receiveShadows = false;
                renderer.lightProbeUsage = LightProbeUsage.Off;
                renderer.reflectionProbeUsage = ReflectionProbeUsage.Off;
                renderer.motionVectorGenerationMode = MotionVectorGenerationMode.ForceNoMotion;
                renderer.allowOcclusionWhenDynamic = true;
                var order = group[0].sortingOrder;
                for (var i = 1; i < group.Count; i++) if (group[i].sortingOrder > order) order = group[i].sortingOrder;
                renderer.sortingOrder = order;
            }

            var motions = BuildClips(document.clips, paths);
            var poses = BuildClips(document.poses, paths);
            AttachAnimator(ctx, root, root.name, motions, poses);
            ctx.AddObjectToAsset("prefab", root);
            ctx.SetMainObject(root);
        }

        static Mesh CombineMeshes(List<MeshSource> sources)
        {
            var counts = new int[sources.Count];
            var total = 0;
            var tinted = false;
            for (var s = 0; s < sources.Count; s++)
            {
                counts[s] = sources[s].vertices.Length / 3;
                total += counts[s];
                if (!IsWhite(sources[s].tint)) tinted = true;
            }

            var vertices = new Vector3[total];
            var uvs = new Vector2[total];
            var colors = tinted ? new Color[total] : null;
            var weights = new BoneWeight[total];
            var weight = new BoneWeight { boneIndex0 = 0, weight0 = 1f };
            var triangles = new List<int>();
            var shapeDeltas = new Dictionary<string, Vector3[]>();
            var shapeOrder = new List<string>();
            var vertex = 0;

            for (var s = 0; s < sources.Count; s++)
            {
                var source = sources[s];
                var count = counts[s];
                var color = ColorOf(source.tint);
                for (var i = 0; i < count; i++)
                {
                    var at = vertex + i;
                    vertices[at] = new Vector3(source.vertices[i * 3], source.vertices[i * 3 + 1], source.vertices[i * 3 + 2]);
                    uvs[at] = new Vector2(source.uvs[i * 2], source.uvs[i * 2 + 1]);
                    weights[at] = weight;
                    if (colors != null) colors[at] = color;
                }
                if (source.triangles != null)
                    for (var i = 0; i < source.triangles.Length; i++) triangles.Add(source.triangles[i] + vertex);
                if (source.blendShapes != null)
                {
                    foreach (var shape in source.blendShapes)
                    {
                        if (!shapeDeltas.TryGetValue(shape.name, out var delta))
                        {
                            delta = new Vector3[total];
                            shapeDeltas[shape.name] = delta;
                            shapeOrder.Add(shape.name);
                        }
                        for (var i = 0; i < count; i++)
                            delta[vertex + i] = new Vector3(shape.delta[i * 3], shape.delta[i * 3 + 1], shape.delta[i * 3 + 2]);
                    }
                }
                vertex += count;
            }

            var mesh = new Mesh { name = sources.Count == 1 ? Safe(sources[0].id) : "Art" };
            if (total > 65535) mesh.indexFormat = IndexFormat.UInt32;
            mesh.vertices = vertices;
            mesh.uv = uvs;
            if (colors != null) mesh.colors = colors;
            mesh.triangles = triangles.ToArray();
            mesh.boneWeights = weights;
            mesh.bindposes = new[] { Matrix4x4.identity };
            var bounds = new Bounds(vertices.Length > 0 ? vertices[0] : Vector3.zero, Vector3.zero);
            for (var i = 0; i < vertices.Length; i++) bounds.Encapsulate(vertices[i]);
            foreach (var name in shapeOrder)
            {
                var delta = shapeDeltas[name];
                mesh.AddBlendShapeFrame(name, 100f, delta, null, null);
                for (var i = 0; i < vertices.Length; i++)
                    if (delta[i].sqrMagnitude > 0f) bounds.Encapsulate(vertices[i] + delta[i]);
            }
            mesh.bounds = bounds;
            return mesh;
        }

        /// <summary>
        /// One renderer per contiguous draw-order run on the same texture. Merging a whole page
        /// would put every part of that page on one side of the other pages.
        /// </summary>
        static List<List<MeshSource>> GroupContiguous(MeshSource[] meshes)
        {
            var groups = new List<List<MeshSource>>();
            List<MeshSource> current = null;
            var texture = int.MinValue;
            if (meshes == null) return groups;
            foreach (var mesh in meshes)
            {
                if (current == null || mesh.texture != texture)
                {
                    current = new List<MeshSource>();
                    groups.Add(current);
                    texture = mesh.texture;
                }
                current.Add(mesh);
            }
            return groups;
        }

        static Texture2D LoadTexture(byte[] bytes, string name)
        {
            var texture = new Texture2D(2, 2, TextureFormat.RGBA32, false, false) { name = name };
            if (!texture.LoadImage(bytes)) throw new InvalidOperationException("Could not read " + name);
            texture.wrapMode = TextureWrapMode.Clamp;
            texture.filterMode = FilterMode.Bilinear;
            texture.anisoLevel = 1;
            texture.alphaIsTransparency = true;
            var width = texture.width;
            var height = texture.height;
            if (width >= 4 && height >= 4 && (width % 4) == 0 && (height % 4) == 0)
            {
                try
                {
                    EditorUtility.CompressTexture(texture, TextureFormat.BC7, TextureCompressionQuality.Normal);
                }
                catch (Exception)
                {
                    try { EditorUtility.CompressTexture(texture, TextureFormat.DXT5, TextureCompressionQuality.Normal); }
                    catch (Exception) { }
                }
            }
            texture.Apply(false, true);
            return texture;
        }

        static bool IsWhite(Tint tint) =>
            tint == null || (tint.r >= 0.999f && tint.g >= 0.999f && tint.b >= 0.999f && tint.a >= 0.999f);

        static List<AnimationClip> BuildClips(ClipSource[] sources, Dictionary<string, string> paths)
        {
            var clips = new List<AnimationClip>();
            if (sources == null) return clips;
            foreach (var source in sources)
            {
                var clip = new AnimationClip { name = source.name, frameRate = source.fps > 0f ? source.fps : 30f };
                var settings = AnimationUtility.GetAnimationClipSettings(clip);
                settings.loopTime = source.loop;
                AnimationUtility.SetAnimationClipSettings(clip, settings);
                if (source.tracks != null)
                {
                    var chosen = new Dictionary<string, TrackSource>();
                    var chosenPath = new Dictionary<string, string>();
                    foreach (var track in source.tracks)
                    {
                        if (!paths.TryGetValue(track.mesh, out var path) || track.times == null || track.times.Length == 0) continue;
                        var key = path + "\n" + track.shape;
                        if (chosen.TryGetValue(key, out var current) && current.times.Length >= track.times.Length) continue;
                        chosen[key] = track;
                        chosenPath[key] = path;
                    }
                    foreach (var pair in chosen)
                    {
                        var track = pair.Value;
                        var binding = new EditorCurveBinding
                        {
                            path = chosenPath[pair.Key],
                            type = typeof(SkinnedMeshRenderer),
                            propertyName = "blendShape." + track.shape
                        };
                        AnimationUtility.SetEditorCurve(clip, binding, LinearCurve(track.times, track.values));
                    }
                }
                if (clip.empty && source.length > 0f)
                {
                    var hold = new AnimationCurve(new Keyframe(0f, 0f), new Keyframe(source.length, 0f));
                    clip.SetCurve("", typeof(Transform), "localPosition.x", hold);
                }
                clips.Add(clip);
            }
            return clips;
        }

        static void AttachAnimator(AssetImportContext ctx, GameObject root, string name, List<AnimationClip> motions, List<AnimationClip> poses)
        {
            for (var i = 0; i < motions.Count; i++) ctx.AddObjectToAsset("clip_" + i, motions[i]);
            for (var i = 0; i < poses.Count; i++) ctx.AddObjectToAsset("pose_" + i, poses[i]);
            var played = motions.Count > 0 ? motions : poses;
            if (played.Count == 0) return;

            var controller = new AnimatorController { name = name + " Controller" };
            var machine = new AnimatorStateMachine { name = "Base", hideFlags = HideFlags.HideInHierarchy };
            ctx.AddObjectToAsset("controller", controller);
            ctx.AddObjectToAsset("stateMachine", machine);
            AnimatorState first = null;
            AnimatorState idle = null;
            for (var i = 0; i < played.Count; i++)
            {
                var state = machine.AddState(played[i].name);
                state.motion = played[i];
                state.hideFlags = HideFlags.HideInHierarchy;
                ctx.AddObjectToAsset("state_" + i, state);
                if (first == null) first = state;
                if (string.Equals(played[i].name, "Idle", StringComparison.OrdinalIgnoreCase)) idle = state;
            }
            machine.defaultState = idle != null ? idle : first;
            controller.layers = new[]
            {
                new AnimatorControllerLayer { name = "Base", defaultWeight = 1f, stateMachine = machine }
            };
            var animator = root.AddComponent<Animator>();
            var external = AssetDatabase.LoadAssetAtPath<AnimatorController>(Path.ChangeExtension(ctx.assetPath, ".controller"));
            animator.runtimeAnimatorController = external != null ? external : controller;
            animator.applyRootMotion = false;
            animator.cullingMode = AnimatorCullingMode.CullCompletely;
        }

        static AnimationCurve LinearCurve(float[] times, float[] values)
        {
            var keys = new Keyframe[times.Length];
            for (var i = 0; i < times.Length; i++)
            {
                var incoming = 0f;
                var outgoing = 0f;
                if (i > 0)
                {
                    var span = times[i] - times[i - 1];
                    incoming = span > 1e-6f ? (values[i] - values[i - 1]) / span : 0f;
                }
                if (i + 1 < times.Length)
                {
                    var span = times[i + 1] - times[i];
                    outgoing = span > 1e-6f ? (values[i + 1] - values[i]) / span : 0f;
                }
                if (i == 0) incoming = outgoing;
                if (i == times.Length - 1) outgoing = incoming;
                keys[i] = new Keyframe(times[i], values[i], incoming, outgoing);
            }
            var curve = new AnimationCurve(keys);
            for (var i = 0; i < curve.length; i++)
            {
                AnimationUtility.SetKeyLeftTangentMode(curve, i, AnimationUtility.TangentMode.Linear);
                AnimationUtility.SetKeyRightTangentMode(curve, i, AnimationUtility.TangentMode.Linear);
            }
            return curve;
        }

        static Material CreateMaterial(string name, Texture2D texture, Color color)
        {
            var shader = Shader.Find("Universal Render Pipeline/Unlit");
            if (shader == null) shader = Shader.Find("Sprites/Default");
            if (shader == null) shader = Shader.Find("Unlit/Transparent");
            var material = new Material(shader) { name = name, color = color };
            if (material.HasProperty("_BaseMap")) material.SetTexture("_BaseMap", texture);
            if (material.HasProperty("_MainTex")) material.SetTexture("_MainTex", texture);
            if (material.HasProperty("_BaseColor")) material.SetColor("_BaseColor", color);
            if (material.HasProperty("_Color")) material.SetColor("_Color", color);
            if (material.HasProperty("_Surface"))
            {
                material.SetFloat("_Surface", 1f);
                material.EnableKeyword("_SURFACE_TYPE_TRANSPARENT");
                material.SetOverrideTag("RenderType", "Transparent");
                material.renderQueue = (int)RenderQueue.Transparent;
            }
            if (material.HasProperty("_Cull")) material.SetInt("_Cull", (int)CullMode.Off);
            material.doubleSidedGI = true;
            return material;
        }

        static Texture2D TextureOf(Texture2D[] textures, int index) =>
            textures != null && index >= 0 && index < textures.Length ? textures[index] : null;

        static Color ColorOf(Tint tint)
        {
            if (tint == null) return Color.white;
            return new Color(tint.r, tint.g, tint.b, tint.a);
        }

        static string ToAssetPath(string assetPath, string relative)
        {
            var directory = Path.GetDirectoryName(assetPath) ?? "";
            return (directory + "/" + relative).Replace('\\', '/');
        }

        static string Safe(string value)
        {
            if (string.IsNullOrEmpty(value)) return "mesh";
            var chars = value.ToCharArray();
            for (var i = 0; i < chars.Length; i++)
            {
                var c = chars[i];
                if (c == '/' || c == '\\' || char.IsControl(c)) chars[i] = '_';
            }
            return new string(chars);
        }

        static string Unique(string name, HashSet<string> used)
        {
            var candidate = name;
            var suffix = 2;
            while (!used.Add(candidate)) candidate = name + "_" + suffix++;
            return candidate;
        }

        [Serializable]
        public class Document
        {
            public int version;
            public string name;
            public float pixelsPerUnit;
            public TextureSource[] textures;
            public MeshSource[] meshes;
            public ClipSource[] clips;
            public ClipSource[] poses;
        }

        [Serializable]
        public class TextureSource
        {
            public string file;
        }

        [Serializable]
        public class MeshSource
        {
            public string id;
            public int texture;
            public int sortingOrder;
            public Tint tint;
            public float[] vertices;
            public float[] uvs;
            public int[] triangles;
            public ShapeSource[] blendShapes;
        }

        [Serializable]
        public class Tint
        {
            public float r;
            public float g;
            public float b;
            public float a;
        }

        [Serializable]
        public class ShapeSource
        {
            public string name;
            public float[] delta;
        }

        [Serializable]
        public class ClipSource
        {
            public string name;
            public bool loop;
            public float length;
            public float fps;
            public TrackSource[] tracks;
        }

        [Serializable]
        public class TrackSource
        {
            public string mesh;
            public string shape;
            public float[] times;
            public float[] values;
        }
    }

    static class Psd2LiveUnityController
    {
        static readonly HashSet<string> pending = new HashSet<string>();
        static readonly HashSet<string> rebuild = new HashSet<string>();
        static readonly HashSet<string> reimportQueued = new HashSet<string>();
        static bool hierarchyQueued;

        [InitializeOnLoadMethod]
        static void RetargetAfterLoad()
        {
            EditorApplication.update += RetargetOnce;
            EditorApplication.hierarchyChanged += QueueHierarchy;
        }

        static void QueueHierarchy()
        {
            if (hierarchyQueued || EditorApplication.isPlayingOrWillChangePlaymode) return;
            hierarchyQueued = true;
            EditorApplication.update += RetargetHierarchy;
        }

        static void RetargetHierarchy()
        {
            EditorApplication.update -= RetargetHierarchy;
            hierarchyQueued = false;
            if (EditorApplication.isPlayingOrWillChangePlaymode) return;
            foreach (var assetPath in Documents())
            {
                var controller = AssetDatabase.LoadAssetAtPath<AnimatorController>(Path.ChangeExtension(assetPath, ".controller"));
                if (Ready(controller)) Retarget(assetPath, controller);
            }
        }

        static void RetargetOnce()
        {
            EditorApplication.update -= RetargetOnce;
            foreach (var assetPath in Documents()) Ensure(assetPath, false);
        }

        static IEnumerable<string> Documents()
        {
            var root = Application.dataPath;
            if (string.IsNullOrEmpty(root) || !Directory.Exists(root)) yield break;
            foreach (var file in Directory.EnumerateFiles(root, "*.p2lunity", SearchOption.AllDirectories))
                yield return "Assets" + file.Substring(root.Length).Replace('\\', '/');
        }

        internal static void Ensure(string assetPath, bool syncClips)
        {
            if (string.IsNullOrEmpty(assetPath)) return;
            if (syncClips) rebuild.Add(assetPath);
            if (!pending.Add(assetPath)) return;
            EditorApplication.update -= Drain;
            EditorApplication.update += Drain;
        }

        static void Drain()
        {
            if (pending.Count == 0)
            {
                EditorApplication.update -= Drain;
                return;
            }
            var paths = new List<string>(pending);
            pending.Clear();
            foreach (var path in paths) Build(path);
            if (pending.Count == 0) EditorApplication.update -= Drain;
        }

        static bool Ready(AnimatorController controller)
        {
            return controller != null
                && controller.layers != null
                && controller.layers.Length > 0
                && controller.layers[0].stateMachine != null
                && controller.layers[0].stateMachine.states.Length > 0
                && controller.layers[0].defaultWeight == 1f;
        }

        static void Build(string assetPath)
        {
            var syncClips = rebuild.Remove(assetPath);
            var controllerPath = Path.ChangeExtension(assetPath, ".controller");
            var controller = AssetDatabase.LoadAssetAtPath<AnimatorController>(controllerPath);
            if (!syncClips && Ready(controller))
            {
                Retarget(assetPath, controller);
                BindPrefab(assetPath, controller);
                return;
            }

            var motions = new List<AnimationClip>();
            foreach (var asset in AssetDatabase.LoadAllAssetsAtPath(assetPath))
            {
                if (asset is AnimationClip clip && !clip.name.StartsWith("param_", StringComparison.Ordinal))
                    motions.Add(clip);
            }
            if (motions.Count == 0) return;

            var changed = false;
            if (controller == null)
            {
                controller = AnimatorController.CreateAnimatorControllerAtPath(controllerPath);
                changed = true;
            }
            if (controller.layers == null || controller.layers.Length == 0 || controller.layers[0].stateMachine == null)
            {
                controller.AddLayer("Base");
                changed = true;
            }
            var layers = controller.layers;
            if (layers[0].defaultWeight != 1f)
            {
                layers[0].defaultWeight = 1f;
                controller.layers = layers;
                changed = true;
            }
            var machine = controller.layers[0].stateMachine;
            if (machine == null) return;

            var present = new HashSet<string>();
            AnimatorState idle = null;
            foreach (var child in machine.states)
            {
                var state = child.state;
                if (state == null || state.motion == null) continue;
                present.Add(state.motion.name);
                if (string.Equals(state.motion.name, "Idle", StringComparison.OrdinalIgnoreCase)) idle = state;
            }
            foreach (var clip in motions)
            {
                if (present.Contains(clip.name)) continue;
                var state = machine.AddState(clip.name);
                state.hideFlags = HideFlags.None;
                state.motion = clip;
                changed = true;
                if (string.Equals(clip.name, "Idle", StringComparison.OrdinalIgnoreCase)) idle = state;
            }
            if (idle != null && machine.defaultState != idle)
            {
                machine.defaultState = idle;
                changed = true;
            }
            else if (idle == null && machine.defaultState == null && machine.states.Length > 0)
            {
                machine.defaultState = machine.states[0].state;
                changed = true;
            }
            if (changed)
            {
                EditorUtility.SetDirty(controller);
                AssetDatabase.SaveAssets();
            }
            Retarget(assetPath, controller);
            BindPrefab(assetPath, controller);
        }

        /// <summary>
        /// The import assigns the sibling controller when it already exists. The first import
        /// still embeds one, so a single reimport binds the prefab. A second miss stops, so a
        /// controller that cannot be referenced cannot reimport forever.
        /// </summary>
        static void BindPrefab(string assetPath, AnimatorController controller)
        {
            if (controller == null) return;
            var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(assetPath);
            var animator = prefab != null ? prefab.GetComponent<Animator>() : null;
            if (animator == null || animator.runtimeAnimatorController == controller)
            {
                reimportQueued.Remove(assetPath);
                return;
            }
            if (!reimportQueued.Add(assetPath)) return;
            AssetDatabase.ImportAsset(assetPath);
        }

        static void Retarget(string assetPath, AnimatorController controller)
        {
            foreach (var animator in UnityEngine.Object.FindObjectsByType<Animator>(FindObjectsInactive.Include))
            {
                if (animator == null) continue;
                var current = animator.runtimeAnimatorController;
                if (current == null) continue;
                if (!string.Equals(AssetDatabase.GetAssetPath(current), assetPath, StringComparison.OrdinalIgnoreCase)) continue;
                animator.runtimeAnimatorController = controller;
                if (animator.cullingMode != AnimatorCullingMode.CullCompletely)
                    animator.cullingMode = AnimatorCullingMode.CullCompletely;
                EditorUtility.SetDirty(animator);
            }
        }
    }

    sealed class Psd2LiveUnityControllerPostprocessor : AssetPostprocessor
    {
        void OnPreprocessTexture()
        {
            if (assetImporter is not TextureImporter importer) return;
            var path = importer.assetPath.Replace('\\', '/');
            var slash = path.LastIndexOf("/Textures/page_", StringComparison.Ordinal);
            if (slash < 0) return;
            var folder = Path.GetFullPath(path.Substring(0, slash));
            var hasDocument = false;
            if (Directory.Exists(folder))
            {
                foreach (var file in Directory.GetFiles(folder, "*.p2lunity"))
                {
                    hasDocument = true;
                    break;
                }
            }
            if (!hasDocument) return;
            if (importer.mipmapEnabled && !importer.isReadable
                && importer.textureCompression == TextureImporterCompression.Compressed
                && importer.wrapMode == TextureWrapMode.Clamp
                && importer.npotScale == TextureImporterNPOTScale.None
                && importer.anisoLevel == 1)
                return;
            importer.mipmapEnabled = true;
            importer.isReadable = false;
            importer.textureCompression = TextureImporterCompression.Compressed;
            importer.npotScale = TextureImporterNPOTScale.None;
            importer.alphaIsTransparency = true;
            importer.wrapMode = TextureWrapMode.Clamp;
            importer.filterMode = FilterMode.Bilinear;
            importer.anisoLevel = 1;
        }

        static void OnPostprocessAllAssets(string[] imported, string[] deleted, string[] moved, string[] movedFrom)
        {
            foreach (var path in imported)
                if (path.EndsWith(".p2lunity", StringComparison.OrdinalIgnoreCase))
                    Psd2LiveUnityController.Ensure(path, true);
        }
    }

    static class Psd2LiveUnityAssetOpener
    {
        [UnityEditor.Callbacks.OnOpenAsset(1)]
        static bool OpenInAnimator(int instanceID, int line)
        {
#pragma warning disable CS0618
            var path = AssetDatabase.GetAssetPath(instanceID);
            if (!path.EndsWith(".p2lunity", StringComparison.OrdinalIgnoreCase)) return false;
            var controller = EditorUtility.InstanceIDToObject(instanceID) as AnimatorController;
#pragma warning restore CS0618
            var external = AssetDatabase.LoadAssetAtPath<AnimatorController>(Path.ChangeExtension(path, ".controller"));
            if (external != null) controller = external;
            else if (controller == null)
            {
                foreach (var asset in AssetDatabase.LoadAllAssetsAtPath(path))
                {
                    if (asset is AnimatorController found)
                    {
                        controller = found;
                        break;
                    }
                }
            }
            if (controller == null) return false;
            Selection.activeObject = controller;
            EditorApplication.ExecuteMenuItem("Window/Animation/Animator");
            return true;
        }
    }
}
