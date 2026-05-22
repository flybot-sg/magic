using UnityEngine;
using Magic.Unity;

// Attach this script to any GameObject in a scene. It renders via IMGUI,
// so no Canvas / EventSystem / Text wiring is needed. On Start it requires
// the smoke.runner Clojure namespace and invokes its `all-pass?` and
// `report-text` vars. The whole report is also written to Player.log.
public class SmokeTestRunner : MonoBehaviour
{
    string _report = "(starting...)";
    bool _ok;

    void Start()
    {
        try
        {
            Clojure.Require("smoke.runner");
            var okVar   = Clojure.GetVar("smoke.runner", "all-pass?");
            var textVar = Clojure.GetVar("smoke.runner", "report-text");
            _ok     = (bool)okVar.invoke();
            _report = (string)textVar.invoke();
        }
        catch (System.Exception e)
        {
            _ok     = false;
            _report = "Bootstrap failure (Clojure runtime did not initialise):\n\n" + e;
        }

        if (_ok) Debug.Log(_report);
        else     Debug.LogError(_report);
    }

    void OnGUI()
    {
        var style = new GUIStyle(GUI.skin.label)
        {
            fontSize = 18,
            wordWrap = true,
            richText = false,
        };
        style.normal.textColor = _ok ? Color.green : new Color(1f, 0.4f, 0.4f);

        const int pad = 16;
        GUI.Label(new Rect(pad, pad, Screen.width - 2 * pad, Screen.height - 2 * pad),
                  _report, style);
    }
}
