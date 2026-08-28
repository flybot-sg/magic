using System.Reflection;

[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace smoke_csharp
{
    public static class Greeter
    {
        public const string Marker = "smoke-csharp-v1";

        public static string Greet(string who)
        {
            return "hello, " + who;
        }

        public static int Add(int a, int b)
        {
            return a + b;
        }
    }
}
