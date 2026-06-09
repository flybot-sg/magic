using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Xml;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace Magic.Unity
{
    class BuildPreprocessor : IPreprocessBuildWithReport
    {
        public int callbackOrder => 0;

        public void OnPreprocessBuild(BuildReport report)
        {
            Debug.Log($"[Magic.Unity] preprocessing build at path {report.summary.outputPath} ({report.summary.platform})");
#if UNITY_2022_1_OR_NEWER
            foreach (var file in report.GetFiles())
#else
            foreach (var file in report.files)
#endif
            {
                Debug.Log($"[Magic.Unity] file {file.path}");
            }

            try
            {
                StockClojureCoexistence.Reconcile();
                IL2CPPWorkarounds.RewriteAssemblies();
                LinkXmlGenerator.BuildLinkXml();
            } catch (Exception e)
            {
                throw new BuildPlayerWindow.BuildMethodException(e.Message);
            }
        }
    }
}
