using System;
using System.Collections.Generic;
using System.Text;

namespace Sample
{
	public static class Sample
	{
		// This somewhat complicated example exists to illustrate how we can circumvent the problem
		// of Mono resolving DllImport before executing the static constructor
		// It is enough to wrap native methods into a dummy object instance to prevent that
		// No need to use wrapper classes if not targeting Mono

		private static readonly ImportsInterfaceImpl _impl = new ImportsInterfaceImpl();
		private static readonly ImportsInterface _interface = _impl;

		// Seem to work the same as direct static method call except object reference will be loaded (but not used)
		public static double Avg(int a, int b) => _impl.Avg(a, b);

		// Calling via interface seems to be as fast despite generated code actually using vtable call
		public static double AvgV2(int a, int b) => _interface.Avg(a, b);

		// Should not reference DllImport class directly (breaks Mono compatibility)
		// Calling this method will fail if it is the first call made
		public static double AvgV3(int a, int b) => Imports.Avg(a, b);
	}
}
