using System;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using RTMath.Utilities;


namespace Sample
{
	internal static class Const
	{
		// Debug logging flag
		public static bool Verbose = false;
	}

	internal interface ImportsInterface
	{
		double Avg(int a, int b);
	}

	// This interface wrapper is only needed for Mono JIT compatibility. Much simpler otherwise.
	internal class ImportsInterfaceImpl : ImportsInterface
	{
		static ImportsInterfaceImpl()
		{
			Init();
		}

		public static void Init()
		{
			if (_initialized)
				return;

			if (Const.Verbose)
				Console.WriteLine("Will load native lib");

			var rl = ResourceLoader
				// NOTE: Rootnamespace is defined as "Sample" in .csproj
				// NOTE: if you use just numbered path 32/64, msbuild tools will fail to add resources at expected path
				.From("Sample.$(OS).x$(ARCH).*")
				.To(".rtmath/SampleDll/DotNet/$(VERSION)/$(ARCH)")
					.Load();

			if (Const.Verbose)
				Console.WriteLine("Loaded native lib at: {0}", rl.ActualDeploymentPath);

			_initialized = true;
		}

		public double Avg(int a, int b) => Imports.Avg(a, b);
		private static bool _initialized;
	}

	internal class Imports
	{
		internal const string DlName = "Sample";

		static Imports()
		{
			ImportsInterfaceImpl.Init();

			// Assume we need to call a native initialization function once
			var rv = Avg(0, 0);

			if (Const.Verbose)
				Console.WriteLine($"Init method returned: {rv}");
		}

		[DllImport(DlName, EntryPoint="avg")]
		internal static extern double Avg(int a, int b);
	}
}
