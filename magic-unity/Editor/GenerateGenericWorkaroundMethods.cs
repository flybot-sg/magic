using System;
using System.Globalization;
using System.Linq;
using System.Collections.Generic;
using Mono.Cecil;
using Mono.Cecil.Rocks;
using Mono.Cecil.Cil;
using UnityEditor.Compilation;

namespace Magic.Unity
{
    public static class GenerateGenericWorkaroundMethods
    {
        struct DynamicCallSiteInfo
        {
            public string name;
            public int arity;
            public bool isStatic;
        }

        static HashSet<DynamicCallSiteInfo> DynamicCallSites = new HashSet<DynamicCallSiteInfo>();
        static List<MethodDefinition> AllMethods = new List<MethodDefinition>();
        static TypeDefinition MagicRuntimeDelegateHelpers = null;

        static HashSet<string> PlayerReferenceNames = null;
        static HashSet<string> PlayerReferenceDirectories = null;

        // The collected assemblies feed AllMethods, the pool of candidate dynamic
        // dispatch targets whose GetMethodDelegateFast instantiations get emitted
        // into the shipped .clj.dlls. A signature referencing an assembly absent
        // from the player build breaks the IL2CPP build; the type-reference
        // closure resolves against the editor's full desktop BCL, which on
        // Windows reaches editor-only assemblies like Mono.WebBrowser. So collect
        // an assembly only if player scripts compile against it. Desktop-only BCL
        // assemblies are never player compilation references, while UnityEngine
        // modules, the player BCL profile, plugins (including .clj.dlls), and
        // user script assemblies all are.
        static HashSet<string> CollectPlayerReferenceNames()
        {
            var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var assembly in CompilationPipeline.GetAssemblies(AssembliesType.PlayerWithoutTestAssemblies))
            {
                names.Add(assembly.name);
                foreach (var reference in assembly.allReferences)
                {
                    names.Add(System.IO.Path.GetFileNameWithoutExtension(reference));
                }
            }
            return names;
        }

        // Player assemblies live in more places than the four directories
        // below: UPM packages resolve under Library/PackageCache and
        // precompiled plugins sit anywhere in Assets. Without their
        // directories the resolver silently drops those assemblies from the
        // reference walk and their signatures get no workarounds. Editor
        // install paths stay excluded on purpose: searching the BCL profile
        // directories makes the netstandard facade resolve, which expands
        // the workaround closure into desktop BCL assemblies (System.Data
        // and friends) and forces them into every player build.
        static HashSet<string> CollectPlayerReferenceDirectories()
        {
            var editorInstall = System.IO.Path.GetDirectoryName(UnityEditor.EditorApplication.applicationPath);
            var directories = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var assembly in CompilationPipeline.GetAssemblies(AssembliesType.PlayerWithoutTestAssemblies))
            {
                foreach (var reference in assembly.allReferences)
                {
                    var physical = System.IO.Path.GetFullPath(PackageExportPath.PhysicalPath(reference));
                    if (!physical.StartsWith(editorInstall, StringComparison.OrdinalIgnoreCase))
                    {
                        directories.Add(System.IO.Path.GetDirectoryName(physical));
                    }
                }
            }
            return directories;
        }

        static bool ShouldCollectReferencedAssembly(AssemblyDefinition assy)
        {
            return PlayerReferenceNames.Contains(assy.Name.Name);
        }

        static HashSet<AssemblyDefinition> CollectAllReferencedAssemblies(AssemblyDefinition assydef, HashSet<AssemblyDefinition> seen = null)
        {
            if(seen == null)
                seen = new HashSet<AssemblyDefinition>();
            seen.Add(assydef);
            var resolver = assydef.MainModule.AssemblyResolver as DefaultAssemblyResolver;
            resolver.AddSearchDirectory("Library/ScriptAssemblies");
            resolver.AddSearchDirectory(PackageExportPath.ExportDirectory);
            resolver.AddSearchDirectory(System.IO.Path.GetDirectoryName(typeof(string).Assembly.Location));
            resolver.AddSearchDirectory(System.IO.Path.GetDirectoryName(typeof(UnityEngine.GameObject).Assembly.Location));
            foreach (var directory in PlayerReferenceDirectories)
            {
                resolver.AddSearchDirectory(directory);
            }

            foreach (var tr in assydef.MainModule.GetTypeReferences())
            {
                try
                {
                    var resolved = tr.Resolve();
                    if(!seen.Contains(resolved.Module.Assembly))
                    {
                        if(ShouldCollectReferencedAssembly(resolved.Module.Assembly))
                        {
                            CollectAllReferencedAssemblies(resolved.Module.Assembly, seen);
                        }
                        else
                        {
                            UnityEngine.Debug.Log($"[CollectAllReferencedAssemblies] Skip {resolved.Module.Assembly} (not a player compilation reference)");
                        }
                    }
                }
                catch(AssemblyResolutionException e)
                {
                    UnityEngine.Debug.Log($"[CollectAllReferencedAssemblies] Failed to resolve Assembly {e.AssemblyReference.FullName}, skipping");
                }
            }

            return seen;
        }

        public static void Init()
        {
            PlayerReferenceNames = CollectPlayerReferenceNames();
            PlayerReferenceDirectories = CollectPlayerReferenceDirectories();
            // A degenerate reference set would silently turn workaround
            // generation into a no-op: the build stays green and devices throw
            // ExecutionEngineException at runtime. Fail the build instead. Any
            // sane player reference set contains a core library and at least
            // one UnityEngine module.
            if (!(PlayerReferenceNames.Contains("mscorlib") || PlayerReferenceNames.Contains("netstandard"))
                || !PlayerReferenceNames.Any(n => n.StartsWith("UnityEngine")))
            {
                throw new InvalidOperationException($"[Magic.Unity] player compilation reference set looks degenerate ({PlayerReferenceNames.Count} entries), refusing to generate IL2CPP workarounds from it");
            }
            UnityEngine.Debug.Log($"[CollectAllReferencedAssemblies] player compilation references: {string.Join(",", PlayerReferenceNames.OrderBy(n => n))}");
            var assemblyCSharp = AssemblyDefinition.ReadAssembly("Library/ScriptAssemblies/Assembly-CSharp.dll");
            var referencedAssemblies = CollectAllReferencedAssemblies(assemblyCSharp);
            UnityEngine.Debug.Log($"[CollectAllReferencedAssemblies] {string.Join(",", referencedAssemblies)}");

            AllMethods = referencedAssemblies
                         .Select(a => a.MainModule)
                         .SelectMany(m => m.Types)
                         .SelectMany(t => t.Methods)
                         .ToList();

            MagicRuntimeDelegateHelpers = AssemblyDefinition
                                            .ReadAssembly(PackageExportPath.MagicRuntimeDll)
                                            .MainModule
                                            .Types
                                            .Where(t => t.FullName == "Magic.DelegateHelpers").Single();
        }

        public static void StartRewriteAssembly(AssemblyDefinition assy)
        {
            DynamicCallSites.Clear();
        }

        public static void FinishRewriteAssembly(AssemblyDefinition assy)
        {
            if (DynamicCallSites.Count > 0)
            {
                MaybeEmitWorkaroundStaticType(assy, DynamicCallSites);
            }
        }

        public static void AnalyzeMethod(MethodDefinition m)
        {
            foreach (var i in m.Body.Instructions)
            {
                if (i.OpCode == OpCodes.Stsfld)
                {
                    var field = i.Operand as FieldReference;
                    if (field.FieldType.FullName.StartsWith("Magic.CallSiteZeroArityMember"))
                    {
                        var ldstrInstruction = i.Previous.Previous;
                        if (ldstrInstruction.OpCode != OpCodes.Ldstr)
                        {
                            throw new BytecodeAssumptionViolatedException($"Could not find name of callsite target member, MAGIC's bytecode patterns may have changed. (expected ldstr at {ldstrInstruction.Offset}, got {ldstrInstruction})");
                        }
                        var name = ldstrInstruction.Operand as string;
                        DynamicCallSites.Add(new DynamicCallSiteInfo
                        {
                            name = name,
                            arity = 0,
                            isStatic = false
                        });
                        DynamicCallSites.Add(new DynamicCallSiteInfo
                        {
                            name = $"get_{name}",
                            arity = 0,
                            isStatic = false
                        });
                    }
                    else if (field.FieldType.FullName.StartsWith("Magic.CallsiteInstanceMethod"))
                    {
                        var ldstrInstruction = i.Previous.Previous;
                        if (ldstrInstruction.OpCode != OpCodes.Ldstr)
                        {
                            throw new BytecodeAssumptionViolatedException($"Could not find name of callsite target member, MAGIC's bytecode patterns may have changed. (expected ldstr at {ldstrInstruction.Offset}, got {ldstrInstruction})");
                        }
                        var name = ldstrInstruction.Operand as string;
                        var invokeMethod = field.FieldType.Resolve().Methods.Where(_m => _m.Name == "Invoke").Single();
                        var arity = invokeMethod.Parameters.Count - 1;
                        DynamicCallSites.Add(new DynamicCallSiteInfo
                        {
                            name = name,
                            arity = arity,
                            isStatic = false
                        });
                    }
                    else if (field.FieldType.FullName.StartsWith("Magic.CallsiteStaticMethod"))
                    {
                        var ldstrInstruction = i.Previous.Previous;
                        if (ldstrInstruction.OpCode != OpCodes.Ldstr)
                        {
                            throw new BytecodeAssumptionViolatedException($"Could not find name of callsite target member, MAGIC's bytecode patterns may have changed. (expected ldstr at {ldstrInstruction.Offset}, got {ldstrInstruction})");
                        }
                        var name = ldstrInstruction.Operand as string;
                        var invokeMethod = field.FieldType.Resolve().Methods.Where(_m => _m.Name == "Invoke").Single();
                        var arity = invokeMethod.Parameters.Count;
                        DynamicCallSites.Add(new DynamicCallSiteInfo
                        {
                            name = name,
                            arity = arity,
                            isStatic = true
                        });
                    }
                }
            }
        }

        public static void MaybeRemoveIL2CPPWorkaround(AssemblyDefinition assy)
        {
            // to make this process idempotent we remove the workaround type if
            // it exists. this happens when we run this process over the same
            // assembly multiple times.
            var existingWorkaroundType = assy.MainModule.Types.Where(t => t.Namespace == "Magic.Unity" && t.Name == "<il2cpp-workaround>").FirstOrDefault();
            if(existingWorkaroundType != null)
            {
                UnityEngine.Debug.LogFormat("[Magic.Unity/GenerateGenericWorkaroundMethods] {1} found existing workaround type, removing {0}", existingWorkaroundType, assy);
                assy.MainModule.Types.Remove(existingWorkaroundType);
            }
        }

        static void MaybeEmitWorkaroundStaticType(AssemblyDefinition assy, IEnumerable<DynamicCallSiteInfo> callSiteInfos)
        {
            var type = new TypeDefinition("Magic.Unity", "<il2cpp-workaround>", TypeAttributes.Public, assy.MainModule.TypeSystem.Object);
            var method = new MethodDefinition("<problematic-generics>", MethodAttributes.Public | MethodAttributes.Static, assy.MainModule.TypeSystem.Void);
            var invocations = 0;
            // have to add type to assembly so that module.Import in
            // EmitWorkaroundInvocation works. we remove if no invocations are
            // needed
            assy.MainModule.Types.Add(type);
            type.Methods.Add(method);
            foreach (var callSiteInfo in callSiteInfos)
            {
                UnityEngine.Debug.LogFormat("[Magic.Unity/GenerateGenericWorkaroundMethods] call site name {0}", callSiteInfo.name);
                var signatures = GetPrecompilationSignatures(callSiteInfo);
                foreach (var signature in signatures)
                {
                    invocations += 1;
                    EmitWorkaroundInvocation(method.Body, signature);
                }
            }
            method.Body.Instructions.Add(Instruction.Create(OpCodes.Ret));
            if(invocations == 0)
            {
                // no actual invocations generated, remove type from assembly
                assy.MainModule.Types.Remove(type);
            }
            else
            {
                UnityEngine.Debug.LogFormat("[Magic.Unity/GenerateGenericWorkaroundMethods] added il2cpp workaround to {1} {0}", type, assy);
            }
        }

        public static MethodDefinition GetRuntimeDelegateHelperMethod(string name)
        {
            foreach (var method in MagicRuntimeDelegateHelpers.Methods)
            {
                if (method.Name == name)
                    return method;
            }

            throw new KeyNotFoundException($"Could not find method {name} in MAGIC runtime delegate helpers");
        }

        private static void EmitWorkaroundInvocation(MethodBody body, TypeReference[] signature)
        {
            UnityEngine.Debug.LogFormat("[Magic.Unity/GenerateGenericWorkaroundMethods] EmitWorkaroundInvocation {0}", string.Join<TypeReference>(",", signature));
            var module = body.Method.Module;
            var arity = signature.Length - 1;
            var name = $"GetMethodDelegateFast{arity:D2}";
            var openGenericMethod = module.ImportReference(GetRuntimeDelegateHelperMethod(name));
            var closedGenericMethod = new Mono.Cecil.GenericInstanceMethod(openGenericMethod);
            foreach (var t in signature)
            {
                closedGenericMethod.GenericArguments.Add(module.ImportReference(t));
            }
            body.Instructions.Add(Instruction.Create(OpCodes.Ldnull));
            body.Instructions.Add(Instruction.Create(OpCodes.Call, closedGenericMethod));
            body.Instructions.Add(Instruction.Create(OpCodes.Pop));
        }

        static TypeReference[] GetPrecompilationSignature(MethodDefinition m)
        {
            var paramTypes = m.Parameters.Select(_m => _m.ParameterType);
            var returnType = m.ReturnType == m.Module.TypeSystem.Void ? m.Module.TypeSystem.Object : m.ReturnType;
            var declaringType = m.DeclaringType;
            return m.IsStatic ? paramTypes.Append(returnType).ToArray()
                              : paramTypes.Prepend(declaringType).Append(returnType).ToArray();
        }

        static List<TypeReference[]> GetPrecompilationSignatures(DynamicCallSiteInfo callSiteInfo)
        {
            return AllMethods.Where(m => m.Name == callSiteInfo.name
                                         && m.Parameters.Count == callSiteInfo.arity
                                         && m.IsStatic == callSiteInfo.isStatic
                                         && IsPrecompilationCandidate(m))
                             .Select(GetPrecompilationSignature)
                             .ToList();
        }

        static bool IsPrecompilationCandidate(MethodDefinition m)
        {
            return m.IsPublic
                    && !m.DeclaringType.HasGenericParameters
                    && !m.HasGenericParameters
                    && !m.IsGenericInstance
                    && !m.ReturnType.HasGenericParameters
                    && !(m.ReturnType == m.Module.TypeSystem.Void)
                    && !m.ReturnType.IsGenericInstance
                    && !m.ReturnType.HasGenericParameters
                    && !m.Parameters.Any(p => p.ParameterType.IsByReference)
                    && !m.Parameters.Any(p => p.ParameterType.HasGenericParameters)
                    && !m.Parameters.Any(p => p.ParameterType.IsGenericInstance)
                    && (m.Parameters.Any(p => p.ParameterType.IsValueType)
                        || m.ReturnType.IsValueType
                        || (m.DeclaringType.IsValueType
                            && !m.DeclaringType.IsByReference
                            && !m.IsStatic));
        }
    }
}