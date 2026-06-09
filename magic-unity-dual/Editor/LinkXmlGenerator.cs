using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Xml;
using UnityEditor;
using UnityEngine;

namespace Magic.Unity
{
    static class LinkXmlGenerator
    {
        public static void BuildLinkXml()
        {
            var cljAssemblies = PlayerCljAssemblies.Paths()
                                        .Select(Path.GetFileNameWithoutExtension)
                                        .Concat(new[] { "Clojure", "Magic.Runtime" });
            BuildLinkXml(cljAssemblies);

        }
        public static void BuildLinkXml(IEnumerable<string> linkXmlEntries)
        {
            var linkPath = "Assets/link.xml";
            if (File.Exists(linkPath))
            {
                Debug.Log("[Magic.Unity/LinkXmlGenerator] Update link.xml");
                var doc = new XmlDocument();
                doc.Load(linkPath);
                var linker = doc.DocumentElement;
                var existed = linker.ChildNodes
                    .Cast<XmlNode>()
                    .Select(assembly => assembly.Attributes?["fullname"].Value)
                    .ToHashSet();
                var added = linkXmlEntries
                    .Where(e => !existed.Contains(e))
                    .ToArray();
                if (added.Any())
                {
                    linker.AppendChild(doc.CreateComment("auto add by builder"));
                    foreach (var e in added)
                    {
                        Debug.Log($"[Magic.Unity/LinkXmlGenerator] Adding entry '{e}'");
                        var ele = doc.CreateElement("assembly");
                        ele.SetAttribute("fullname", e);
                        linker.AppendChild(ele);
                    }
                    doc.Save(linkPath);
                }
                return;
            }
            Debug.Log("[Magic.Unity/LinkXmlGenerator] Generating link.xml");
            var linkXml = XmlWriter.Create(linkPath);
            linkXml.WriteStartElement("linker");
            foreach (var entry in linkXmlEntries)
            {
                Debug.Log($"[Magic.Unity/LinkXmlGenerator] Adding entry '{entry}'");
                linkXml.WriteStartElement("assembly");
                linkXml.WriteAttributeString("fullname", entry);
                linkXml.WriteEndElement();
            }
            linkXml.WriteEndElement();
            linkXml.Close();
        }
    }
}