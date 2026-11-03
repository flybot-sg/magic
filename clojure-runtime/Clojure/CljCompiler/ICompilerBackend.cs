/**
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 **/

using System.IO;

namespace clojure.lang
{
    // ClojureCLR's compiler is C# inside Clojure.dll, called directly. MAGIC's is
    // Clojure code compiled against Clojure.dll, which cannot reference it back,
    // so the host must set Compiler.Backend.
    public interface ICompilerBackend
    {
        object Eval(object form);
        object Macroexpand1(object form);
        object LoadFile(FileInfo file, string relativePath);
        object CompileFile(FileInfo file, string relativePath);
    }
}
