#if NETSTANDARD1_0 || NETSTANDARD1_1 || NETSTANDARD1_2 || NETSTANDARD1_3 || NETSTANDARD1_4 || NETSTANDARD1_5 || NETSTANDARD1_6 || NETCOREAPP1_0 || NETCOREAPP1_1
#define NETSTANDARD_BELOW_2_0
#endif

using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;

namespace RTMath.Utilities
{
	using static ResourceLoaderUtils.Util;
	using FileStream = ResourceLoaderUtils.FileStream;

	internal class FileJanitor
	{
		private const string LockFileName = "lockfile.$$$";
		private static readonly object CleanupLock = new object();
		private static readonly List<CleanupPath> CleanupDirs = new List<CleanupPath>();

		internal bool IsIgnoredException(Exception e)
		{
			return e is UnauthorizedAccessException || e is System.Security.SecurityException || e is IOException;
		}

		private static bool IsLockFile(string path)
		{
			return path.EndsWith(LockFileName);
		}

		public static FileStream TryOpenWriteable(string path) => TryOpenForWriteTest(path);

		public static string LockFilePath(string dir) => Path.Combine(dir, LockFileName);

		public static FileStream TryCreateLockFile(string dir)
		{
			return TryOpenForWriteTest(LockFilePath(dir), FileOptions.DeleteOnClose);
		}

		public static bool LockFileExists(string dir) => File.Exists(LockFilePath(dir));

		public static DateTime LockFileWriteTime(string dir)
		{
			try
			{
				return File.GetLastWriteTimeUtc(LockFilePath(dir));
			}
			catch
			{
				return DateTime.MinValue;
			}
		}

		/// Delete directory carefully, only if _none_ of the files in it are opened by someone else
		/// If at least one file is locked, the operation silently fails without deleting _anything_
		/// It is safe to call this operation concurrently on a single directory, but if the directory contents are modified
		/// by the code that does not respect our lock file, its contents may still be deleted partially and false will be returned
		public static bool TryDeleteDirectory(string dir)
		{
			bool isSuccess = false;
			using (var lockFile = TryCreateLockFile(dir))
			{
				if (null == lockFile)
					return false;

				var files = Directory.EnumerateFiles(dir);
				var opened = new List<FileStream>();

				foreach (string fname in files)
					if (!IsLockFile(fname))
					{
						var f = TryOpenWriteable(fname);
						if (null == f)
							goto Fail;

						opened.Add(f);
					}

				isSuccess = true;

				Fail:
				foreach (var f in opened)
				{
					f.Dispose();
				}

				if (isSuccess)
				{
					foreach (string fname in files)
					{
						if (!IsLockFile(fname) && !TryDeleteFile(fname))
							return false;
					}
				}
			}

			if (isSuccess)
			{
				try
				{
					Directory.Delete(dir);
					return true;
				}
				catch { }
			}

			return false;
		}


		public static void TryCleanup(string dir, bool cleanDir = true, string subDirRegEx = null)
		{
			new CleanupPath(dir, cleanDir, subDirRegEx).TryCleanup();
		}


		public static void TryCleanup()
		{
			lock (CleanupLock)
			{
				var deleted = new List<CleanupPath>();
				foreach (CleanupPath p in CleanupDirs)
					if (p.TryCleanup())
						deleted.Add(p);

				foreach (CleanupPath p in deleted)
					CleanupDirs.Remove(p);
			}
		}

		/// <summary>
		/// Register path for cleanup.
		/// </summary>
		/// <param name="path">path to clean</param>
		/// <param name="cleanDir">will clean the specified directory, ignoring subdirectories (default)</param>
		/// <param name="subdirRegEx">will apply the same cleanup logic to all subdirectories found in the path (not-recursive), but not the path itself</param>
		public static void AddCleanupPath(string path, bool cleanDir = true, string subdirRegEx = null)
		{
			lock (CleanupLock)
			{
				CleanupDirs.Add(new CleanupPath(path, cleanDir, subdirRegEx));
			}
		}

#if !NETSTANDARD_BELOW_2_0
		/// <summary>
		/// Register cleanup callback
		/// Cleanup will be called automatically on events DomainUnload and ProcessExit, unless netstandard &lt; 2.0 or .net core &lt; 2.0
		/// each added path is only cleaned if none of the files in it are open or locked
		/// </summary>
		public static void RegisterForCleanupOnExit()
		{
			lock (CleanupLock)
			{
				if (!_handlerRegistered)
				{
					AppDomain.CurrentDomain.DomainUnload += (sender, eventArgs) => TryCleanup();
					AppDomain.CurrentDomain.ProcessExit += (sender, eventArgs) => TryCleanup();
					_handlerRegistered = true;

				}
			}
		}

		private static bool _handlerRegistered;
#endif // !NETSTANDARD_BELOW_2_0
	}

	internal class CleanupPath
	{
		private const int CleanDir = 1;

		private readonly String _path;
		private readonly String _subDirRegEx;
		private readonly int _flags;

		public bool TryCleanup()
		{
			try
			{
				if (!Directory.Exists(_path))
					return true;

				bool success = true;
				// Clean subdirs?
				if (null != _subDirRegEx)
				{
					var dirs = Directory.EnumerateDirectories(_path);
					Regex regex = new Regex(_subDirRegEx);
					foreach (var dir in dirs)
						if (regex.IsMatch(Path.GetFileNameWithoutExtension(dir)))
							success &= FileJanitor.TryDeleteDirectory(dir);
				}

				if (0 != (_flags & CleanDir))
					success &= FileJanitor.TryDeleteDirectory(_path);

				return success;
			}
			catch
			{
				return false;
			}
		}

		public CleanupPath(string path, bool cleanDir, string subDirRegEx)
		{
			_path = path;
			_subDirRegEx = subDirRegEx;
			_flags = (cleanDir ? CleanDir : 0);
		}
	}
}
