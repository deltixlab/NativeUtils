using System;
using System.Diagnostics;
using System.Diagnostics.Contracts;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using NUnit.Framework;

namespace Sample
{
	public class Sample
	{
		public static string ThisPath = Path.GetDirectoryName(typeof(Sample).Assembly.Location);
		public static string ThisConfiguration = Path.GetFileName(Path.GetDirectoryName(ThisPath));
		public static string ThisFilename = Path.GetFileName(ThisPath);
		private static bool Verbose = true;

		// Dynamically load Dll and execute a function from it
		static public void InvokeDll()
		{
			string framework = ThisFilename == "netcoreapp2.0" ? "netstandard2.0" : ThisFilename;
			string Location = Path.GetFullPath(Path.Combine(ThisPath, "..", "..", "..", "..", "SampleDll", "bin",
				ThisConfiguration, framework, "SampleDll.dll"));

			Assembly AssemblyObject = Assembly.LoadFile(Location);
			Type FunctionsType = AssemblyObject.GetType("Sample.Sample");
			MethodInfo TestFunction = FunctionsType.GetMethod("Avg");

			double x = (double)TestFunction.Invoke(null, new object[] { 1, 2 });

			if (Verbose)
				Console.WriteLine($"f(1, 2) = {x}");
		}

		static void Main(string[] args)
		{
			InvokeDll();
		}

		[Test]
		public void TestLoad()
		{
			InvokeDll();
		}
	}
}
