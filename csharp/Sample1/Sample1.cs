using RTMath.Utilities;
using System;
using System.Diagnostics;
using System.Diagnostics.Contracts;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;

namespace Sample
{
	internal class Imports
	{
		internal const string DlName = "Sample";

		static Imports()
		{
			var rl = ResourceLoader
				// If <RootNamespace> property is not defined, root namespace matches module name
				.From("Sample1.$(OS).x$(ARCH).*")
				.To("$(TEMP)/.rtmath/Sample/DotNet/$(VERSION)/$(ARCH)")
				.Load();

			Console.WriteLine("Loaded native lib at: {0}", rl.ActualDeploymentPath);
		}

		[DllImport(DlName, EntryPoint = "avg")]
		internal static extern double Avg(int a, int b);
		[DllImport(DlName)]
		internal static extern int ptrSize();
	}	

	public class Sample
	{
		static void Main(string[] args)
		{
			Console.WriteLine("Basic Sample. Only contains x86 & x64 Windows versions of the sample lib. May not work with Mono(see sample 2 for workaround)");
			Console.WriteLine("Calling test function: Avg ({0}, {1}) = {2},   ptrSize()= {3}", 2, 3, Imports.Avg(2, 3), Imports.ptrSize() * 8);
		}

	}
}
